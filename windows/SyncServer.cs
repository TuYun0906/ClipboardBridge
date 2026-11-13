using System;
using System.ComponentModel;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Threading;

internal sealed class SyncServer : IDisposable
{
    private readonly TcpListener listener;
    private readonly Identity identity;
    private readonly Action<Frame> receive;
    private readonly Action<bool> status;
    private readonly object gate = new object();
    private bool closed, writing;
    private TcpClient socket;
    private TlsLink link;
    private Frame pending, sent;
    private readonly Queue<Frame> history = new Queue<Frame>();
    private readonly Func<IEnumerable<Frame>> replay;
    private bool replayNeeded;

    internal void Publish(Frame frame)
    {
        lock (gate)
        {
            if (closed || link == null) return;
            history.Enqueue(frame);
            ScheduleWrite();
        }
    }
    private byte[] lastApplied;

    internal SyncServer(IPAddress address, int port, Identity identity, Action<Frame> receive, Action<bool> status, Func<IEnumerable<Frame>> replay = null)
    {
        this.identity = identity;
        this.receive = receive;
        this.status = status;
        this.replay = replay;
        listener = new TcpListener(address, port);
    }

    internal int Start()
    {
        listener.Start(4);
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        new Thread(ReadLoop) { IsBackground = true, Name = "Clipboard receive" }.Start();
        return port;
    }

    internal void Submit(Frame frame)
    {
        lock (gate)
        {
            if (closed) return;
            pending = frame;
            ScheduleWrite();
        }
    }

    private void ReadLoop()
    {
        while (!IsClosed())
        {
            bool connected = false;
            TcpClient incoming = null;
            try
            {
                incoming = listener.AcceptTcpClient();
                lock (gate)
                {
                    if (closed) return;
                    socket = incoming;
                }
                using (TlsLink current = TlsLink.Accept(incoming, identity.Certificate, identity.Key))
                {
                    lock (gate)
                    {
                        if (closed) return;
                        link = current;
                        sent = null;
                        replayNeeded = replay != null;
                        ScheduleWrite();
                    }
                    connected = true;
                    status(true);
                    for (Frame frame; !IsClosed() && (frame = current.Read()) != null;)
                    {
                        if (frame.Kind == Wire.Ack)
                        {
                            lock (gate)
                                if (pending != null && pending.Id.SequenceEqual(frame.Id)) pending = null;
                        }
                        else
                        {
                            if (frame.Kind < Wire.Text || frame.Kind > Wire.Delete)
                                throw new IOException("Unexpected message");
                            if (frame.Kind >= Wire.HistoryText || lastApplied == null || !lastApplied.SequenceEqual(frame.Id))
                            {
                                receive(frame);
                                if (frame.Kind < Wire.HistoryText) lastApplied = frame.Id;
                            }
                            if (frame.Kind < Wire.HistoryText) current.Send(new Frame(Wire.Ack, frame.Id, new byte[0]));
                        }
                    }
                }
            }
            catch (IOException) { }
            catch (SocketException) { }
            catch (AuthenticationException) { }
            catch (Win32Exception) { }
            catch (ObjectDisposedException) { }
            finally
            {
                if (incoming != null) incoming.Close();
                lock (gate)
                {
                    socket = null;
                    link = null;
                    history.Clear();
                }
                if (connected && !IsClosed()) status(false);
            }
        }
    }

    private bool IsClosed() { lock (gate) return closed; }

    private void ScheduleWrite()
    {
        if (closed || writing || link == null || (history.Count == 0 && !replayNeeded && (pending == null || pending == sent))) return;
        writing = true;
        ThreadPool.QueueUserWorkItem(delegate { WriteLatest(); });
    }

    private void WriteLatest()
    {
        while (true)
        {
            TlsLink current;
            Frame frame = null;
            bool restore = false;
            lock (gate)
            {
                current = link;
                if (closed || current == null || (history.Count == 0 && !replayNeeded && (pending == null || pending == sent)))
                {
                    writing = false;
                    return;
                }
                if (pending != null && pending != sent) { frame = pending; sent = frame; }
                else if (replayNeeded) { restore = true; replayNeeded = false; }
                else frame = history.Dequeue();
            }
            try
            {
                if (restore) foreach (Frame entry in replay()) current.Send(entry);
                else current.Send(frame);
            }
            catch (IOException) { Drop(current); }
            catch (ObjectDisposedException) { Drop(current); }
        }
    }

    private void Drop(TlsLink current)
    {
        lock (gate)
        {
            if (link != current) return;
            link = null;
            if (socket != null) socket.Close();
        }
    }

    public void Dispose()
    {
        lock (gate)
        {
            if (closed) return;
            closed = true;
            listener.Stop();
            if (socket != null) socket.Close();
            pending = null;
        }
    }
}

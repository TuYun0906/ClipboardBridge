using System;
using System.ComponentModel;
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
    private byte[] lastApplied;

    internal SyncServer(IPAddress address, int port, Identity identity, Action<Frame> receive, Action<bool> status)
    {
        this.identity = identity;
        this.receive = receive;
        this.status = status;
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
                            if (frame.Kind != Wire.Text && frame.Kind != Wire.Image)
                                throw new IOException("Unexpected message");
                            if (lastApplied == null || !lastApplied.SequenceEqual(frame.Id))
                            {
                                receive(frame);
                                lastApplied = frame.Id;
                            }
                            current.Send(new Frame(Wire.Ack, frame.Id, new byte[0]));
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
                }
                if (connected && !IsClosed()) status(false);
            }
        }
    }

    private bool IsClosed() { lock (gate) return closed; }

    private void ScheduleWrite()
    {
        if (closed || writing || link == null || pending == null || pending == sent) return;
        writing = true;
        ThreadPool.QueueUserWorkItem(delegate { WriteLatest(); });
    }

    private void WriteLatest()
    {
        while (true)
        {
            TlsLink current;
            Frame frame;
            lock (gate)
            {
                current = link;
                frame = pending;
                if (closed || current == null || frame == null || frame == sent)
                {
                    writing = false;
                    return;
                }
                sent = frame;
            }
            try { current.Send(frame); }
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

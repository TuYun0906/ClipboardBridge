using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Threading;

internal interface ISyncConnection : IDisposable
{
    void Submit(Frame frame);
    void Publish(Frame frame);
}

internal sealed class SyncClient : ISyncConnection
{
    private readonly object gate = new object();
    private readonly Uri endpoint;
    private readonly byte[] pin, key;
    private readonly Action<Frame, Action<Frame>> receive;
    private readonly Action<bool> status;
    private readonly Func<IEnumerable<Frame>> replay;
    private readonly Func<byte[]> inventory;
    private readonly Queue<Frame> history = new Queue<Frame>();
    private bool closed, writing, replayNeeded;
    private TcpClient socket;
    private TlsLink link;
    private Frame pending, sent;
    private readonly Queue<Frame> images = new Queue<Frame>();
    private Frame sentImage;
    private byte[] lastReceived;

    internal SyncClient(Uri endpoint, byte[] pairing, Action<Frame, Action<Frame>> receive,
        Action<bool> status, Func<IEnumerable<Frame>> replay, Func<byte[]> inventory = null)
    {
        this.endpoint = endpoint;
        pin = pairing.Take(32).ToArray();
        key = pairing.Skip(32).ToArray();
        this.receive = receive;
        this.status = status;
        this.replay = replay;
        this.inventory = inventory;
    }

    internal static bool TryEndpoint(string value, out Uri endpoint)
    {
        value = value.Trim();
        if (!Uri.TryCreate("tcp://" + value, UriKind.Absolute, out endpoint)) return false;
        return endpoint.Host.Length > 0 && endpoint.HostNameType != UriHostNameType.Unknown &&
            endpoint.UserInfo == "" && endpoint.AbsolutePath == "/" && endpoint.Query == "" && endpoint.Fragment == "" &&
            value.IndexOfAny(new[] { '/', '\\', '?', '#', '@' }) < 0 && !value.EndsWith(":") && endpoint.Port != 0;
    }

    internal static bool TryPairing(string code, out byte[] pairing)
    {
        pairing = null;
        code = code.Trim();
        if (code.Length != 86 || code.Any(c => !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') &&
            !(c >= '0' && c <= '9') && c != '-' && c != '_')) return false;
        try { pairing = Convert.FromBase64String(code.Replace('-', '+').Replace('_', '/') + "=="); }
        catch (FormatException) { return false; }
        return pairing.Length == 64;
    }

    internal void Start()
    {
        new Thread(Run) { IsBackground = true, Name = "Clipboard client" }.Start();
    }

    public void Submit(Frame frame)
    {
        lock (gate)
        {
            if (closed) return;
            if (frame.Kind == Wire.Image) images.Enqueue(frame);
            else pending = frame;
            ScheduleWrite();
        }
    }

    public void Publish(Frame frame)
    {
        lock (gate)
        {
            if (closed || link == null) return;
            if (frame.Kind == Wire.HistoryImage &&
                images.Any(image => image.SaveImage && image.Id.SequenceEqual(frame.Id))) return;
            history.Enqueue(frame);
            ScheduleWrite();
        }
    }

    private void Run()
    {
        while (true)
        {
            TcpClient connecting;
            lock (gate)
            {
                if (closed) return;
                socket = connecting = new TcpClient();
            }
            try
            {
                int port = endpoint.IsDefaultPort ? 47832 : endpoint.Port;
                using (var current = TlsLink.Connect(connecting, endpoint.DnsSafeHost, port, pin, key, inventory))
                {
                    lock (gate)
                    {
                        if (closed) return;
                        link = current;
                        sent = sentImage = null;
                        replayNeeded = true;
                        ScheduleWrite();
                    }
                    status(true);
                    for (Frame frame; (frame = current.Read()) != null;)
                    {
                        if (frame.Kind == Wire.Ack)
                        {
                            lock (gate)
                            {
                                if (sentImage != null && sentImage.Id.SequenceEqual(frame.Id) && images.Count > 0)
                                {
                                    images.Dequeue();
                                    sentImage = null;
                                    ScheduleWrite();
                                }
                                if (pending != null && pending.Id.SequenceEqual(frame.Id)) pending = null;
                            }
                        }
                        else if (frame.Kind >= Wire.HistoryText) receive(frame, Publish);
                        else if (frame.Kind == Wire.Text || frame.Kind == Wire.Image)
                        {
                            if (lastReceived == null || !lastReceived.SequenceEqual(frame.Id))
                            {
                                receive(frame, Publish);
                                lastReceived = frame.Id;
                            }
                            current.Acknowledge(frame.Id);
                        }
                        else throw new IOException("Unexpected message");
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
                connecting.Close();
                lock (gate)
                {
                    link = null;
                    socket = null;
                    history.Clear();
                }
            }
            lock (gate)
            {
                if (closed) return;
                status(false);
                Monitor.Wait(gate, 850);
            }
        }
    }

    private void ScheduleWrite()
    {
        if (writing || link == null) return;
        writing = true;
        ThreadPool.QueueUserWorkItem(delegate { Write(); });
    }

    private void Write()
    {
        while (true)
        {
            Frame frame;
            TlsLink current;
            lock (gate)
            {
                current = link;
                bool nextImage = images.Count > 0 && sentImage == null;
                if (current == null || (!nextImage && !replayNeeded && history.Count == 0 &&
                    (pending == null || pending == sent)))
                {
                    writing = false;
                    return;
                }
                if (pending != null && pending != sent) frame = sent = pending;
                else if (nextImage) frame = sentImage = images.Peek();
                else if (replayNeeded) { replayNeeded = false; frame = null; }
                else frame = history.Dequeue();
            }
            try
            {
                if (frame != null) current.Send(frame);
                else foreach (var entry in replay())
                {
                    Frame urgent = null;
                    lock (gate)
                    {
                        if (link != current) throw new IOException("Connection changed");
                        if (images.Count > 0 && sentImage == null) urgent = sentImage = images.Peek();
                    }
                    if (urgent != null) current.Send(urgent);
                    current.Send(entry);
                }
            }
            catch (IOException) { Disconnect(current); }
            catch (ObjectDisposedException) { Disconnect(current); }
        }
    }

    private void Disconnect(TlsLink current)
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
            closed = true;
            images.Clear();
            link = null;
            if (socket != null) socket.Close();
            Monitor.PulseAll(gate);
        }
    }
}

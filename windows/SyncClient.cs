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
    bool RefreshHistory();
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
    private byte[] refreshInventory;
    private string deviceName;
    private Action<string> roster;

    internal void ConfigureDevices(string name, Action<string> update)
    {
        deviceName = name;
        roster = update;
    }

    public bool RefreshHistory()
    {
        lock (gate)
        {
            if (link == null || link.PeerDevice == null) return false;
            link.EnqueueControl(new Frame(Wire.Refresh, new byte[Wire.IdSize], new byte[0]));
            return true;
        }
    }

    private void DropManual()
    {
        for (int left = images.Count; left > 0; left--)
        {
            Frame frame = images.Dequeue();
            if (frame.Completion == null) images.Enqueue(frame);
            else
            {
                if (sentImage == frame) sentImage = null;
                frame.Complete(false);
            }
        }
    }
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
            if (closed || (frame.Completion != null && link == null)) { frame.Complete(false); return; }
            if (frame.Kind == Wire.Image || frame.Completion != null) images.Enqueue(frame);
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
                using (var current = TlsLink.Connect(connecting, endpoint.DnsSafeHost, port, pin, key, inventory, deviceName))
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
                    if (roster != null && current.PeerDevice == null) roster("旧版设备");
                    for (Frame frame; (frame = current.Read()) != null;)
                    {
                        if (frame.Kind == Wire.Roster && current.PeerDevice != null)
                        {
                            if (roster != null) roster(System.Text.Encoding.UTF8.GetString(frame.Data));
                        }
                        else if (frame.Kind == Wire.Refresh && current.PeerDevice != null)
                        {
                            byte[] local = inventory();
                            lock (gate)
                            {
                                current.EnqueueControl(new Frame(Wire.RefreshInventory, frame.Id, local));
                                refreshInventory = frame.Data;
                                replayNeeded = true;
                                ScheduleWrite();
                            }
                        }
                        else if (frame.Kind == Wire.Ack)
                        {
                            Frame delivered = null;
                            lock (gate)
                            {
                                if (sentImage != null && sentImage.Id.SequenceEqual(frame.Id) && images.Count > 0)
                                {
                                    delivered = images.Dequeue();
                                    sentImage = null;
                                    ScheduleWrite();
                                }
                                if (pending != null && pending.Id.SequenceEqual(frame.Id)) pending = null;
                            }
                            if (delivered != null) delivered.Complete(true);
                        }
                        else if (frame.Kind >= Wire.HistoryText && frame.Kind <= Wire.Delete) receive(frame, Publish);
                        else if (frame.Kind == Wire.Text || frame.Kind == Wire.Image)
                        {
                            if (lastReceived == null || !lastReceived.SequenceEqual(frame.Id))
                            {
                                try { receive(frame, Publish); }
                                catch (ExpiredImageException) { continue; }
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
                    refreshInventory = null;
                    DropManual();
                }
            }
            lock (gate)
            {
                if (closed) return;
                if (roster != null) roster("");
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
            byte[] remoteImages = null;
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
                else if (replayNeeded)
                {
                    replayNeeded = false;
                    frame = null;
                    remoteImages = refreshInventory;
                    refreshInventory = null;
                }
                else frame = history.Dequeue();
            }
            try
            {
                if (remoteImages != null) current.ReplaceInventory(remoteImages);
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
            DropManual();
            images.Clear();
            link = null;
            if (socket != null) socket.Close();
            Monitor.PulseAll(gate);
        }
    }
}

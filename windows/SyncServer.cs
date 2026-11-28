using System;
using System.ComponentModel;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Threading;

internal sealed class SyncServer : ISyncConnection
{
    private const int MaxPairingAttempts = 16;
    private readonly TcpListener listener;
    private TcpListener loopback;
    private readonly bool includeLoopback;
    private readonly Identity identity;
    private readonly Action<Frame, Action<Frame>> receive;
    private readonly Action<bool> status;
    private readonly Func<IEnumerable<Frame>> replay;
    private readonly Func<byte[]> inventory;
    private readonly object gate = new object();
    private readonly object clipboardGate = new object();
    private readonly HashSet<Peer> peers = new HashSet<Peer>();
    private readonly Queue<string> appliedOrder = new Queue<string>();
    private readonly HashSet<string> applied = new HashSet<string>();
    private bool closed;
    private int connected;
    private string deviceName;
    private Action<string> roster;

    internal void ConfigureDevices(string name, Action<string> update)
    {
        deviceName = name;
        roster = update;
        update(name);
    }

    private void PublishRoster()
    {
        if (roster == null || closed) return;
        string list = string.Join("\n", new[] { deviceName }.Concat(peers.Where(peer => peer.Link != null)
            .Select(peer => peer.Link.PeerDevice ?? "旧版设备").OrderBy(name => name, StringComparer.Ordinal)));
        roster(list);
        var frame = new Frame(Wire.Roster, new byte[Wire.IdSize], System.Text.Encoding.UTF8.GetBytes(list));
        foreach (Peer peer in peers)
            if (peer.Link != null && peer.Link.PeerDevice != null) peer.Link.EnqueueControl(frame);
    }

    public bool RefreshHistory()
    {
        lock (gate)
        {
            if (closed || connected == 0 || replay == null) return false;
            var frame = new Frame(Wire.Refresh, new byte[Wire.IdSize], inventory == null ? new byte[0] : inventory());
            foreach (Peer peer in peers)
            {
                if (peer.Link == null) continue;
                if (peer.Link.PeerDevice != null) peer.Link.EnqueueControl(frame);
                else
                {
                    peer.ReplayNeeded = true;
                    ScheduleWrite(peer);
                }
            }
            return true;
        }
    }
    private Frame pending;
    private readonly Queue<Frame> waitingImages = new Queue<Frame>();

    private sealed class Peer
    {
        internal readonly TcpClient Socket;
        internal readonly Queue<Frame> Images = new Queue<Frame>();
        internal readonly Queue<Frame> History = new Queue<Frame>();
        internal TlsLink Link;
        internal Frame Pending, Sent;
        internal bool Writing, ReplayNeeded;
        internal byte[] RefreshInventory;
        internal readonly Dictionary<string, Frame> Confirmations = new Dictionary<string, Frame>();

        internal Peer(TcpClient socket) { Socket = socket; }
    }

    internal SyncServer(IPAddress address, int port, Identity identity, Action<Frame, Action<Frame>> receive,
        Action<bool> status, Func<IEnumerable<Frame>> replay = null, bool includeLoopback = false, Func<byte[]> inventory = null)
    {
        this.identity = identity;
        this.receive = receive;
        this.status = status;
        this.replay = replay;
        this.inventory = inventory;
        this.includeLoopback = includeLoopback && !IPAddress.Loopback.Equals(address);
        listener = new TcpListener(address, port);
    }

    internal int Start()
    {
        listener.Start(4);
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        if (includeLoopback)
        {
            loopback = new TcpListener(IPAddress.Loopback, port);
            try { loopback.Start(4); }
            catch (SocketException) { listener.Stop(); throw; }
            StartAccepting(loopback);
        }
        StartAccepting(listener);
        return port;
    }

    private void StartAccepting(TcpListener source)
    {
        new Thread(() => AcceptLoop(source)) { IsBackground = true, Name = "Clipboard listener" }.Start();
    }

    public void Publish(Frame frame) { Publish(frame, null); }

    private void Publish(Frame frame, Peer origin)
    {
        lock (gate)
        {
            if (closed) return;
            foreach (Peer peer in peers)
            {
                if (peer.Link == null || peer == origin) continue;
                if (frame.Kind == Wire.HistoryImage &&
                    peer.Images.Any(image => image.SaveImage && image.Id.SequenceEqual(frame.Id))) continue;
                peer.History.Enqueue(frame);
                ScheduleWrite(peer);
            }
        }
    }

    public void Submit(Frame frame) { Submit(frame, null); }

    private void Submit(Frame frame, Peer origin)
    {
        lock (gate)
        {
            if (closed || (frame.Completion != null && connected == 0)) { frame.Complete(false); return; }
            bool queued = frame.Kind == Wire.Image || frame.Completion != null;
            if (!queued) pending = origin == null ? frame : null;
            else if (origin == null && connected == 0) waitingImages.Enqueue(frame);
            foreach (Peer peer in peers)
            {
                if (peer.Link == null || peer == origin) continue;
                if (queued) peer.Images.Enqueue(frame);
                else peer.Pending = frame;
                ScheduleWrite(peer);
            }
        }
    }

    private void AcceptLoop(TcpListener source)
    {
        while (true)
        {
            TcpClient incoming;
            try { incoming = source.AcceptTcpClient(); }
            catch (SocketException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (InvalidOperationException) { return; }
            lock (gate)
            {
                if (closed || peers.Count - connected >= MaxPairingAttempts)
                {
                    incoming.Close();
                    if (closed) return;
                    continue;
                }
                var peer = new Peer(incoming);
                peers.Add(peer);
                new Thread(() => ReadPeer(peer)) { IsBackground = true, Name = "Clipboard receive" }.Start();
            }
        }
    }

    private void ReadPeer(Peer peer)
    {
        try
        {
            using (TlsLink link = TlsLink.Accept(peer.Socket, identity.Certificate, identity.Key, inventory, deviceName))
            {
                lock (gate)
                {
                    if (closed) return;
                    peer.Link = link;
                    peer.Pending = pending;
                    while (waitingImages.Count > 0) peer.Images.Enqueue(waitingImages.Dequeue());
                    peer.ReplayNeeded = replay != null;
                    ScheduleWrite(peer);
                    if (++connected == 1) status(true);
                    PublishRoster();
                }
                Action<Frame> relay = change => Publish(change, change.Kind == Wire.Delete ? null : peer);
                for (Frame frame; (frame = link.Read()) != null;)
                {
                    if (frame.Kind == Wire.Refresh && link.PeerDevice != null) RefreshHistory();
                    else if (frame.Kind == Wire.RefreshInventory && link.PeerDevice != null && replay != null)
                    {
                        lock (gate)
                        {
                            peer.RefreshInventory = frame.Data;
                            peer.ReplayNeeded = true;
                            ScheduleWrite(peer);
                        }
                    }
                    else if (frame.Kind == Wire.Ack)
                    {
                        Frame delivered;
                        lock (gate)
                        {
                            string id = Convert.ToBase64String(frame.Id);
                            peer.Confirmations.TryGetValue(id, out delivered);
                            peer.Confirmations.Remove(id);
                            if (peer.Sent != null && peer.Sent.Id.SequenceEqual(frame.Id))
                            {
                                if (peer.Pending == peer.Sent) peer.Pending = null;
                                if (pending == peer.Sent) pending = null;
                            }
                            if (delivered != null) delivered.Complete(true);
                        }
                    }
                    else if (frame.Kind >= Wire.HistoryText && frame.Kind <= Wire.Delete) receive(frame, relay);
                    else if (frame.Kind == Wire.Text || frame.Kind == Wire.Image)
                    {
                        frame.Source = link.PeerDevice;
                        try { ApplyClipboard(frame, peer, relay); }
                        catch (ExpiredImageException) { continue; }
                        link.Acknowledge(frame.Id);
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
            peer.Socket.Close();
            lock (gate)
            {
                peers.Remove(peer);
                if (peer.Link != null)
                {
                    peer.Link = null;
                    if (--connected == 0 && !closed) status(false);
                    PublishRoster();
                }
                foreach (Frame frame in peer.Images.Concat(peer.Confirmations.Values))
                {
                    if (frame.Completion != null && !peers.Any(other =>
                        other.Images.Contains(frame) || other.Confirmations.ContainsValue(frame)))
                        frame.Complete(false);
                }
                peer.History.Clear();
                peer.Images.Clear();
                peer.Confirmations.Clear();
            }
        }
    }

    private void ApplyClipboard(Frame frame, Peer peer, Action<Frame> relay)
    {
        string id = Convert.ToBase64String(frame.Id);
        lock (clipboardGate)
        {
            if (applied.Contains(id)) return;
            receive(frame, relay);
            applied.Add(id);
            appliedOrder.Enqueue(id);
            if (appliedOrder.Count > 128) applied.Remove(appliedOrder.Dequeue());
            frame.ReceiveDeadline = null;
            Submit(frame, peer);
        }
    }

    private void ScheduleWrite(Peer peer)
    {
        if (peer.Writing) return;
        peer.Writing = true;
        ThreadPool.QueueUserWorkItem(delegate { WriteLatest(peer); });
    }

    private static Frame TakeDelivery(Peer peer)
    {
        Frame frame = peer.Images.Dequeue();
        if (frame.Completion != null) peer.Confirmations[Convert.ToBase64String(frame.Id)] = frame;
        return frame;
    }

    private void WriteLatest(Peer peer)
    {
        try
        {
            while (true)
            {
                TlsLink link;
                Frame frame = null;
                byte[] remoteImages = null;
                lock (gate)
                {
                    link = peer.Link;
                    if (closed || link == null ||
                        (!peer.ReplayNeeded && peer.Images.Count == 0 && peer.History.Count == 0 && (peer.Pending == null || peer.Pending == peer.Sent)))
                    {
                        peer.Writing = false;
                        return;
                    }
                    if (peer.Pending != null && peer.Pending != peer.Sent) frame = peer.Sent = peer.Pending;
                    else if (peer.Images.Count > 0) frame = TakeDelivery(peer);
                    else if (peer.ReplayNeeded)
                    {
                        peer.ReplayNeeded = false;
                        remoteImages = peer.RefreshInventory;
                        peer.RefreshInventory = null;
                    }
                    else frame = peer.History.Dequeue();
                }
                if (remoteImages != null) link.ReplaceInventory(remoteImages);
                if (frame == null)
                    foreach (Frame entry in replay())
                    {
                        Frame urgent;
                        lock (gate)
                        {
                            if (peer.Link != link) throw new IOException("Connection changed");
                            urgent = peer.Images.Count > 0 ? TakeDelivery(peer) : null;
                        }
                        if (urgent != null) link.Send(urgent);
                        link.Send(entry);
                    }
                else link.Send(frame);
            }
        }
        catch (IOException) { peer.Socket.Close(); }
        catch (ObjectDisposedException) { peer.Socket.Close(); }
    }

    public void Dispose()
    {
        lock (gate)
        {
            if (closed) return;
            closed = true;
            listener.Stop();
            if (loopback != null) loopback.Stop();
            foreach (Peer peer in peers) peer.Socket.Close();
            pending = null;
            waitingImages.Clear();
        }
    }
}

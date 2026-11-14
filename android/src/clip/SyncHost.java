package clip;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Base64;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

final class SyncHost implements Closeable {
    interface Receiver { void apply(Wire.Frame frame, Consumer<Wire.Frame> relay) throws IOException; }

    private final SSLServerSocket listener;
    private final Receiver receiver;
    private final Consumer<Boolean> status;
    private final SyncClient.Replay replay;
    private final byte[] key;
    private final Set<Peer> peers = new HashSet<>();
    private final Set<String> applied = new LinkedHashSet<>();
    private boolean closed;
    private int connected;
    private Wire.Frame pending;

    private static final class Peer {
        final SSLSocket socket;
        final ArrayDeque<Wire.Frame> history = new ArrayDeque<>();
        TlsLink link;
        Wire.Frame pending, sent;
        boolean writing, restore;
        Peer(SSLSocket socket) { this.socket = socket; }
    }

    SyncHost(SSLContext tls, byte[] key, int port, Receiver receiver,
             Consumer<Boolean> status, SyncClient.Replay replay) throws IOException {
        this.receiver = receiver;
        this.status = status;
        this.replay = replay;
        this.key = key.clone();
        listener = TlsLink.listen(tls, port);
    }

    void start() { startThread(this::accept, "clip-listen"); }
    int port() { return listener.getLocalPort(); }
    synchronized void submit(Wire.Frame frame) { submit(frame, null); }
    synchronized void publish(Wire.Frame frame) { publish(frame, null); }

    private synchronized void submit(Wire.Frame frame, Peer origin) {
        if (closed) return;
        pending = origin == null ? frame : null;
        for (Peer peer : peers) {
            if (peer == origin || peer.link == null) continue;
            peer.pending = frame;
            schedule(peer);
        }
    }

    private synchronized void publish(Wire.Frame frame, Peer origin) {
        if (closed) return;
        for (Peer peer : peers) {
            if (peer == origin || peer.link == null) continue;
            peer.history.add(frame);
            schedule(peer);
        }
    }

    private void accept() {
        for (;;) {
            try {
                SSLSocket socket = (SSLSocket) listener.accept();
                synchronized (this) {
                    if (closed || peers.size() >= 16) { socket.close(); continue; }
                    Peer peer = new Peer(socket);
                    peers.add(peer);
                    startThread(() -> read(peer), "clip-host-receive");
                }
            } catch (IOException | RuntimeException done) { return; }
        }
    }

    private void read(Peer peer) {
        try (TlsLink link = TlsLink.accept(peer.socket, key)) {
            synchronized (this) {
                if (closed) return;
                peer.link = link;
                peer.restore = true;
                peer.pending = pending;
                schedule(peer);
                if (++connected == 1) status.accept(true);
            }

            for (Wire.Frame frame; (frame = link.read()) != null;) {
                if (frame.kind == Wire.ACK) {
                    synchronized (this) {
                        if (peer.sent != null && Arrays.equals(peer.sent.id, frame.id)) {
                            if (peer.pending == peer.sent) peer.pending = null;
                            if (pending == peer.sent) pending = null;
                        }
                    }
                } else if (frame.kind >= Wire.HISTORY_TEXT && frame.kind <= Wire.DELETE) {
                    receiver.apply(frame, change -> publish(change, change.kind == Wire.DELETE ? null : peer));
                } else if (frame.kind == Wire.TEXT || frame.kind == Wire.IMAGE) {
                    applyClipboard(frame, peer);
                    link.send(new Wire.Frame(Wire.ACK, frame.id, new byte[0]));
                } else throw new IOException("Unexpected message");
            }
        } catch (IOException | RuntimeException ignored) {
            // Unauthenticated or disconnected clients do not affect the host.
        } finally {
            closeSocket(peer);
            synchronized (this) {
                peers.remove(peer);
                if (peer.link != null && --connected == 0 && !closed) status.accept(false);
                peer.link = null;
                peer.history.clear();
            }
        }
    }

    private void applyClipboard(Wire.Frame frame, Peer peer) throws IOException {
        String id = Base64.getEncoder().encodeToString(frame.id);
        synchronized (applied) {
            if (applied.contains(id)) return;
            receiver.apply(frame, change -> publish(change, change.kind == Wire.DELETE ? null : peer));
            applied.add(id);
            if (applied.size() > 128) {
                java.util.Iterator<String> oldest = applied.iterator();
                oldest.next();
                oldest.remove();
            }
            submit(frame, peer);
        }
    }

    // Called while holding the host monitor.
    private void schedule(Peer peer) {
        if (peer.writing) return;
        peer.writing = true;
        startThread(() -> write(peer), "clip-host-send");
    }

    private void write(Peer peer) {
        try {
            for (;;) {
                TlsLink link;
                Wire.Frame frame = null;
                synchronized (this) {
                    link = peer.link;
                    if (closed || link == null || (!peer.restore && peer.history.isEmpty() &&
                            (peer.pending == null || peer.pending == peer.sent))) {
                        peer.writing = false;
                        return;
                    }
                    if (peer.pending != null && peer.pending != peer.sent) frame = peer.sent = peer.pending;
                    else if (peer.restore) peer.restore = false;
                    else frame = peer.history.remove();
                }
                if (frame == null) replay.send(link::send);
                else link.send(frame);
            }
        } catch (IOException | RuntimeException failed) { closeSocket(peer); }
    }

    @Override public synchronized void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        for (Peer peer : peers) closeSocket(peer);
        pending = null;
    }

    private static void closeSocket(Peer peer) {
        try { peer.socket.close(); } catch (IOException ignored) { }
    }

    private static void startThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}

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
import java.net.ServerSocket;
import java.net.Socket;

final class SyncHost implements Closeable {
    interface Receiver { void apply(Wire.Frame frame, Consumer<Wire.Frame> relay) throws IOException; }

    private final ServerSocket listener;
    private final SSLContext tls;
    private final Receiver receiver;
    private final Consumer<Boolean> status;
    private final SyncClient.Replay replay;
    private java.util.function.Supplier<byte[]> inventory;
    private final byte[] key;
    private final Set<Peer> peers = new HashSet<>();
    private final Set<String> applied = new LinkedHashSet<>();
    private boolean closed;
    private int connected;
    private Wire.Frame pending;
    private final ArrayDeque<ImageDelivery> waitingImages = new ArrayDeque<>();

    private static final class ImageDelivery {
        final Wire.Frame frame;
        Runnable confirmation, failure;
        ImageDelivery(Wire.Frame frame, Runnable confirmation, Runnable failure) {
            this.frame = frame;
            this.confirmation = confirmation;
            this.failure = failure;
        }
    }

    private static final class Peer {
        final Socket socket;
        final ArrayDeque<ImageDelivery> images = new ArrayDeque<>();
        ImageDelivery sentImage;
        final ArrayDeque<Wire.Frame> history = new ArrayDeque<>();
        TlsLink link;
        Wire.Frame pending, sent;
        boolean writing, restore;
        Peer(Socket socket) { this.socket = socket; }
    }

    SyncHost(SSLContext tls, byte[] key, int port, Receiver receiver,
             Consumer<Boolean> status, SyncClient.Replay replay) throws IOException {
        this.receiver = receiver;
        this.status = status;
        this.replay = replay;
        this.key = key.clone();
        this.tls = tls;
        listener = TlsLink.listen(port);
    }

    void imageInventory(java.util.function.Supplier<byte[]> inventory) { this.inventory = inventory; }

    void start() { startThread(this::accept, "clip-listen"); }
    int port() { return listener.getLocalPort(); }
    void submit(Wire.Frame frame) { submit(frame, null, null, null); }
    void submit(Wire.Frame frame, Runnable delivered) { submit(frame, null, delivered, null); }
    void submit(Wire.Frame frame, Runnable delivered, Runnable failed) { submit(frame, null, delivered, failed); }
    synchronized void publish(Wire.Frame frame) { publish(frame, null); }

    private synchronized void submit(Wire.Frame frame, Peer origin, Runnable delivered, Runnable failed) {
        if (closed) return;
        ImageDelivery image = frame.kind == Wire.IMAGE ? new ImageDelivery(frame, delivered, failed) : null;
        if (image == null) pending = origin == null ? frame : null;
        else if (origin == null && connected == 0) {
            if (failed != null) failed.run();
            else waitingImages.add(image);
            return;
        }
        for (Peer peer : peers) {
            if (peer == origin || peer.link == null) continue;
            if (image != null) peer.images.add(image);
            else peer.pending = frame;
            schedule(peer);
        }
    }

    synchronized boolean cancelImage(Wire.Frame frame) {
        boolean committed = frame.cancelUpload();
        waitingImages.removeIf(image -> image.frame == frame);
        for (Peer peer : peers) {
            if (peer.link != null) peer.link.cancelWrite(frame);
            peer.images.removeIf(image -> image.frame == frame);
            if (peer.sentImage != null && peer.sentImage.frame == frame) {
                peer.sentImage = null;
                schedule(peer);
            }
        }
        return committed;
    }

    private synchronized void publish(Wire.Frame frame, Peer origin) {
        if (closed) return;
        for (Peer peer : peers) {
            if (peer == origin || peer.link == null) continue;
            if (frame.kind == Wire.HISTORY_IMAGE && peer.images.stream()
                    .anyMatch(image -> image.frame.saveImage && Arrays.equals(image.frame.id, frame.id))) continue;
            peer.history.add(frame);
            schedule(peer);
        }
    }

    private void accept() {
        for (;;) {
            try {
                Socket socket = listener.accept();
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
        try (TlsLink link = TlsLink.accept(peer.socket, tls, key, inventory)) {
            synchronized (this) {
                if (closed) return;
                peer.link = link;
                peer.restore = true;
                peer.pending = pending;
                peer.images.addAll(waitingImages);
                waitingImages.clear();
                schedule(peer);
                if (++connected == 1) status.accept(true);
            }

            for (Wire.Frame frame; (frame = link.read()) != null;) {
                if (frame.kind == Wire.ACK) {
                    Runnable delivered = null;
                    synchronized (this) {
                        if (peer.sentImage != null && Arrays.equals(peer.sentImage.frame.id, frame.id)) {
                            delivered = peer.sentImage.confirmation;
                            peer.sentImage.confirmation = null;
                            peer.sentImage.failure = null;
                            peer.images.remove();
                            peer.sentImage = null;
                            schedule(peer);
                        }
                        if (peer.sent != null && Arrays.equals(peer.sent.id, frame.id)) {
                            if (peer.pending == peer.sent) peer.pending = null;
                            if (pending == peer.sent) pending = null;
                        }
                    }
                    if (delivered != null) delivered.run();
                } else if (frame.kind >= Wire.HISTORY_TEXT && frame.kind <= Wire.DELETE) {
                    receiver.apply(frame, change -> publish(change, change.kind == Wire.DELETE ? null : peer));
                } else if (frame.kind == Wire.TEXT || frame.kind == Wire.IMAGE) {
                    try { applyClipboard(frame, peer); }
                    catch (Wire.ExpiredImage expired) { continue; }
                    link.acknowledge(frame.id);
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
                for (ImageDelivery image : peer.images) {
                    if (image.failure != null && peers.stream().noneMatch(other -> other.images.contains(image))) {
                        image.failure.run();
                        image.failure = null;
                    }
                }
                peer.images.clear();
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
            frame.deadlineNanos = 0;
            submit(frame, peer, null, null);
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
                    boolean nextImage = !peer.images.isEmpty() && peer.sentImage == null;
                    if (closed || link == null || (!peer.restore && !nextImage && peer.history.isEmpty() &&
                            (peer.pending == null || peer.pending == peer.sent))) {
                        peer.writing = false;
                        return;
                    }
                    if (peer.pending != null && peer.pending != peer.sent) frame = peer.sent = peer.pending;
                    else if (nextImage) {
                        peer.sentImage = peer.images.peek();
                        frame = peer.sentImage.frame;
                    }
                    else if (peer.restore) peer.restore = false;
                    else frame = peer.history.remove();
                }
                if (frame == null) replay.send(entry -> {
                    Wire.Frame urgent = null;
                    synchronized (this) {
                        if (peer.link != link) throw new IOException("Connection changed");
                        if (!peer.images.isEmpty() && peer.sentImage == null) {
                            peer.sentImage = peer.images.peek();
                            urgent = peer.sentImage.frame;
                        }
                    }
                    if (urgent != null) link.send(urgent);
                    link.send(entry);
                });
                else link.send(frame);
            }
        } catch (IOException | RuntimeException failed) { closeSocket(peer); }
    }

    @Override public synchronized void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        for (Peer peer : peers) closeSocket(peer);
        pending = null;
        waitingImages.clear();
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

package clip;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class SyncClient implements Closeable {
    interface Sender { void send(Wire.Frame frame) throws IOException; }
    interface Replay { void send(Sender sender) throws IOException; }
    interface Receiver { void apply(Wire.Frame frame) throws IOException; }

    private final String host;
    private final int port;
    private final byte[] pin, key;
    private final Receiver receiver;
    private final Consumer<Boolean> status;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> daemon(task, "clip-send"));
    private final Thread reader = daemon(this::run, "clip-receive");
    private final Object lock = new Object();
    private boolean closed, writing;
    private TlsLink link;
    private Wire.Frame pending, sent, sentImage;
    private final ArrayDeque<Wire.Frame> images = new ArrayDeque<>();
    private final java.util.IdentityHashMap<Wire.Frame, Runnable> confirmations = new java.util.IdentityHashMap<>();
    private final ArrayDeque<Wire.Frame> history = new ArrayDeque<>();
    private Replay replay;
    private boolean replayNeeded;

    void replayWith(Replay replay) { this.replay = replay; }

    void publish(Wire.Frame frame) {
        synchronized (lock) {
            if (closed || link == null) return;
            history.add(frame);
            scheduleWrite();
        }
    }
    private byte[] lastApplied;

    SyncClient(String host, int port, byte[] pin, byte[] key, Receiver receiver, Consumer<Boolean> status) {
        this.host = host;
        this.port = port;
        this.pin = pin.clone();
        this.key = key.clone();
        this.receiver = receiver;
        this.status = status;
    }

    void start() { reader.start(); }

    void submit(Wire.Frame frame) { submit(frame, null); }

    void submit(Wire.Frame frame, Runnable onDelivered) {
        synchronized (lock) {
            if (closed) return;
            if (frame.kind == Wire.IMAGE) {
                images.add(frame);
                if (onDelivered != null) confirmations.put(frame, onDelivered);
            }
            else pending = frame;
            scheduleWrite();
        }
    }

    private void run() {
        while (!isClosed()) {
            boolean connected = false;
            try (TlsLink current = TlsLink.connect(host, port, pin, key)) {
                synchronized (lock) {
                    if (closed) return;
                    link = current;
                    sent = sentImage = null;
                    replayNeeded = replay != null;
                    scheduleWrite();
                }
                connected = true;
                status.accept(true);
                for (Wire.Frame frame; (frame = current.read()) != null;) {
                    if (isClosed()) break;
                    if (frame.kind == Wire.ACK) {
                        Runnable delivered = null;
                        synchronized (lock) {
                            if (sentImage != null && Arrays.equals(sentImage.id, frame.id) && !images.isEmpty()) {
                                delivered = confirmations.remove(images.remove());
                                sentImage = null;
                                scheduleWrite();
                            }
                            if (pending != null && Arrays.equals(pending.id, frame.id)) pending = null;
                        }
                        if (delivered != null) delivered.run();
                    } else {
                        if (frame.kind < Wire.TEXT || frame.kind > Wire.DELETE)
                            throw new IOException("Unexpected message");
                        if (frame.kind >= Wire.HISTORY_TEXT || !Arrays.equals(lastApplied, frame.id)) {
                            receiver.apply(frame);
                            if (frame.kind < Wire.HISTORY_TEXT) lastApplied = frame.id;
                        }
                        if (frame.kind < Wire.HISTORY_TEXT) current.send(new Wire.Frame(Wire.ACK, frame.id, new byte[0]));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Pending data remains available for the next connection.
            } finally {
                synchronized (lock) { link = null; history.clear(); }
                if (connected && !isClosed()) status.accept(false);
            }
            try { Thread.sleep(850); }
            catch (InterruptedException stopping) { return; }
        }
    }

    private boolean isClosed() {
        synchronized (lock) { return closed; }
    }

    // Must be called while holding lock.
    private void scheduleWrite() {
        if (closed || writing || link == null || (history.isEmpty() && !replayNeeded &&
                (images.isEmpty() || sentImage != null) && (pending == null || pending == sent))) return;
        writing = true;
        writer.execute(this::writeLatest);
    }

    private void writeLatest() {
        for (;;) {
            TlsLink current;
            Wire.Frame frame = null;
            boolean restore = false;
            synchronized (lock) {
                current = link;
                if (closed || current == null || (history.isEmpty() && !replayNeeded &&
                (images.isEmpty() || sentImage != null) && (pending == null || pending == sent))) {
                    writing = false;
                    return;
                }
                if (pending != null && pending != sent) { frame = pending; sent = frame; }
                else if (!images.isEmpty() && sentImage == null) frame = sentImage = images.peek();
                else if (replayNeeded) { restore = true; replayNeeded = false; }
                else frame = history.remove();
            }
            try {
                if (restore) replay.send(current::send);
                else current.send(frame);
            }
            catch (IOException failed) {
                closeLink(current);
                synchronized (lock) {
                    if (link == current) link = null;
                }
            }
        }
    }

    @Override public void close() {
        TlsLink current;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            current = link;
            link = null;
            pending = null;
            images.clear();
            confirmations.clear();
        }
        closeLink(current);
        reader.interrupt();
        writer.shutdownNow();
    }

    private static void closeLink(TlsLink link) {
        if (link == null) return;
        try { link.close(); }
        catch (IOException ignored) { }
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }
}

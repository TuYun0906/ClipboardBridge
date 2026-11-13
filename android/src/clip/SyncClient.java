package clip;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class SyncClient implements Closeable {
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
    private Wire.Frame pending, sent;
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

    void submit(Wire.Frame frame) {
        synchronized (lock) {
            if (closed) return;
            pending = frame;
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
                    sent = null;
                    scheduleWrite();
                }
                connected = true;
                status.accept(true);
                for (Wire.Frame frame; (frame = current.read()) != null;) {
                    if (isClosed()) break;
                    if (frame.kind == Wire.ACK) {
                        synchronized (lock) {
                            if (pending != null && Arrays.equals(pending.id, frame.id)) pending = null;
                        }
                    } else {
                        if (frame.kind != Wire.TEXT && frame.kind != Wire.IMAGE)
                            throw new IOException("Unexpected message");
                        if (!Arrays.equals(lastApplied, frame.id)) {
                            receiver.apply(frame);
                            lastApplied = frame.id;
                        }
                        current.send(new Wire.Frame(Wire.ACK, frame.id, new byte[0]));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Pending data remains available for the next connection.
            } finally {
                synchronized (lock) { link = null; }
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
        if (closed || writing || link == null || pending == null || pending == sent) return;
        writing = true;
        writer.execute(this::writeLatest);
    }

    private void writeLatest() {
        for (;;) {
            TlsLink current;
            Wire.Frame frame;
            synchronized (lock) {
                current = link;
                frame = pending;
                if (closed || current == null || frame == null || frame == sent) {
                    writing = false;
                    return;
                }
                sent = frame;
            }
            try { current.send(frame); }
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

package clip;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class TlsLink implements Closeable {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SSLSocket socket;
    private final Socket transport;
    private final Object sendLock = new Object();
    private final java.util.Set<String> knownImages = new java.util.LinkedHashSet<>();
    private volatile boolean imageSync;
    private boolean imageParts;
    private Wire.Frame firstPart;
    private volatile byte[] readyImage;
    private long preparedAt;
    private java.io.ByteArrayOutputStream imageBuffer;
    private volatile Wire.Frame writingFrame;
    private final java.util.ArrayDeque<Wire.Frame> acknowledgements = new java.util.ArrayDeque<>();
    private boolean ackWriterScheduled;

    private TlsLink(SSLSocket socket, Socket transport) {
        this.socket = socket;
        this.transport = transport;
    }

    static TlsLink connect(String host, int port, byte[] pin, byte[] key) throws IOException {
        return connect(host, port, pin, key, null);
    }

    static TlsLink connect(String host, int port, byte[] pin, byte[] key,
            java.util.function.Supplier<byte[]> inventory) throws IOException {
        if (pin.length != 32 || key.length != Wire.KEY_SIZE)
            throw new IOException("Invalid pairing credentials");

        Socket transport = new Socket();
        SSLSocket socket = null;
        try {
            transport.connect(new InetSocketAddress(host, port), 6000);
            socket = (SSLSocket) clientContext(pin.clone()).getSocketFactory().createSocket(transport, host, port, true);
            socket.setUseClientMode(true);
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(6000);
            socket.startHandshake();

            TlsLink link = new TlsLink(socket, transport);
            byte[] id = new byte[Wire.ID_SIZE];
            RANDOM.nextBytes(id);
            if (inventory != null) Wire.offerImageSync(id);
            link.send(new Wire.Frame(Wire.AUTH, id, key));
            Wire.Frame ack = link.read();
            if (ack == null || ack.kind != Wire.ACK) throw new IOException("Pairing rejected");
            link.imageParts = inventory != null && Arrays.equals(ack.id, Wire.acceptImageSync(id, true));
            if (link.imageParts || (inventory != null && Arrays.equals(ack.id, Wire.acceptImageSync(id)))) {
                link.readInventory();
                link.send(new Wire.Frame(Wire.IMAGE_INVENTORY, id, inventory.get()));
                link.imageSync = true;
            } else if (!Arrays.equals(ack.id, id)) throw new IOException("Pairing rejected");
            socket.setSoTimeout(0);
            return link;
        } catch (IOException | RuntimeException failure) {
            closeFailedHandshake(transport, socket, failure);
            throw failure;
        }
    }

    static ServerSocket listen(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port), 8);
            return server;
        } catch (IOException | RuntimeException failure) {
            try { server.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    static TlsLink accept(Socket transport, SSLContext context, byte[] key,
            java.util.function.Supplier<byte[]> inventory) throws IOException {
        SSLSocket socket = null;
        try {
            socket = (SSLSocket) context.getSocketFactory().createSocket(transport,
                    transport.getInetAddress().getHostAddress(), transport.getPort(), true);
            socket.setUseClientMode(false);
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(6000);
            socket.startHandshake();
            TlsLink link = new TlsLink(socket, transport);
            Wire.Frame hello = link.read();
            if (hello == null || hello.kind != Wire.AUTH || !MessageDigest.isEqual(key, hello.data))
                throw new IOException("Pairing rejected");
            boolean upgraded = inventory != null && Wire.offersImageSync(hello.id);
            link.imageParts = upgraded && Wire.offersImageParts(hello.id);
            link.send(new Wire.Frame(Wire.ACK, upgraded ? Wire.acceptImageSync(hello.id, link.imageParts) : hello.id, new byte[0]));
            if (upgraded) {
                link.send(new Wire.Frame(Wire.IMAGE_INVENTORY, hello.id, inventory.get()));
                link.readInventory();
                link.imageSync = true;
            }
            socket.setSoTimeout(0);
            return link;
        } catch (IOException | RuntimeException failure) {
            closeFailedHandshake(transport, socket, failure);
            throw failure;
        }
    }

    private static void closeFailedHandshake(Socket transport, SSLSocket socket, Exception failure) {
        try { transport.close(); }
        catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        if (socket == null) return;
        try { socket.close(); }
        catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
    }

    Wire.Frame read() throws IOException {
        for (;;) {
            Wire.Frame frame = Wire.read(socket.getInputStream());
            if (frame == null) return null;
            if (imageSync && frame.kind == Wire.SAVED_IMAGE)
                return new Wire.Frame(Wire.IMAGE, frame.id, frame.data, true);
            if (!imageParts || frame.kind < Wire.IMAGE_PART || frame.kind > Wire.IMAGE_READY) return frame;
            if (frame.kind == Wire.IMAGE_READY) {
                readyImage = frame.id;
                continue;
            }
            if (frame.kind == Wire.IMAGE_PART || frame.kind == Wire.SAVED_IMAGE_PART) {
                if (firstPart == null) {
                    firstPart = frame;
                    imageBuffer = new java.io.ByteArrayOutputStream();
                }
                if (preparedAt != 0 || frame.kind != firstPart.kind || !Arrays.equals(frame.id, firstPart.id) ||
                        imageBuffer.size() + frame.data.length > Wire.IMAGE_LIMIT)
                    throw new IOException("Invalid image part");
                imageBuffer.write(frame.data);
                continue;
            }
            if (firstPart == null || !Arrays.equals(frame.id, firstPart.id))
                throw new IOException("Unexpected image completion");
            if (frame.kind == Wire.IMAGE_PREPARE) {
                if (preparedAt != 0) throw new IOException("Duplicate image preparation");
                preparedAt = System.nanoTime();
                enqueueControl(new Wire.Frame(Wire.IMAGE_READY, frame.id, new byte[0]));
                continue;
            }
            Wire.Frame completed = frame.kind == Wire.IMAGE_END && canCommit(frame)
                    ? new Wire.Frame(Wire.IMAGE, frame.id, imageBuffer.toByteArray(),
                            firstPart.kind == Wire.SAVED_IMAGE_PART) : null;
            if (completed != null && preparedAt != 0)
                completed.deadlineNanos = preparedAt + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(commitBudget(frame));
            firstPart = null;
            imageBuffer = null;
            preparedAt = 0;
            if (completed != null) return completed;
        }
    }

    private boolean canCommit(Wire.Frame frame) throws IOException {
        if (frame.data.length == 0 && preparedAt == 0) return true;
        if (frame.data.length != 4 || preparedAt == 0) throw new IOException("Invalid timed image commit");
        int budget = commitBudget(frame);
        return budget > 0 && budget <= 10000 &&
                System.nanoTime() - preparedAt < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budget);
    }

    private static int commitBudget(Wire.Frame frame) {
        return java.nio.ByteBuffer.wrap(frame.data).getInt();
    }

    void send(Wire.Frame frame) throws IOException {
        synchronized (sendLock) {
            flushAcknowledgements();
            writingFrame = frame;
            try {
                if (frame.expired()) return;
                String key = imageKey(frame.id);
                if (frame.kind == Wire.HISTORY_IMAGE && knownImages.contains(key)) return;
                if (frame.kind == Wire.DELETE) knownImages.remove(key);
                boolean savedLive = frame.kind == Wire.IMAGE && frame.saveImage;
                Wire.Frame outgoing = imageSync && savedLive
                        ? new Wire.Frame(Wire.SAVED_IMAGE, frame.id, frame.data) : frame;
                if (imageParts && frame.kind == Wire.IMAGE && frame.deadlineNanos != 0) {
                    if (!writeImageParts(frame)) return;
                } else Wire.write(socket.getOutputStream(), outgoing);
                if (savedLive && !imageSync && !frame.cancelled && !knownImages.contains(key))
                    Wire.write(socket.getOutputStream(), new Wire.Frame(Wire.HISTORY_IMAGE, frame.id, frame.data));
                if (frame.kind == Wire.HISTORY_IMAGE || savedLive) rememberImage(key);
            } finally { writingFrame = null; }
        }
    }

    private boolean writeImageParts(Wire.Frame frame) throws IOException {
        int offset = 0;
        while (offset < frame.data.length && !frame.expired()) {
            flushAcknowledgements();
            if (frame.expired()) break;
            int end = Math.min(offset + Wire.PART_LIMIT, frame.data.length);
            Wire.write(socket.getOutputStream(), new Wire.Frame(frame.saveImage ? Wire.SAVED_IMAGE_PART : Wire.IMAGE_PART,
                    frame.id, Arrays.copyOfRange(frame.data, offset, end)));
            offset = end;
        }
        if (offset > 0 && frame.deadlineNanos != 0 && !frame.expired()) {
            readyImage = null;
            Wire.write(socket.getOutputStream(), new Wire.Frame(Wire.IMAGE_PREPARE, frame.id, new byte[0]));
            while (!frame.expired() && !Arrays.equals(readyImage, frame.id)) {
                try { sendLock.wait(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Image transfer interrupted", interrupted);
                }
            }
            readyImage = null;
        }
        byte[] budget = new byte[0];
        boolean complete = !frame.expired();
        if (complete && frame.deadlineNanos != 0) {
            long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(frame.deadlineNanos - System.nanoTime());
            complete = left > 0;
            if (complete) budget = java.nio.ByteBuffer.allocate(4).putInt((int)Math.min(left, 10000)).array();
        }
        if (offset > 0) Wire.write(socket.getOutputStream(),
                new Wire.Frame(complete ? Wire.IMAGE_END : Wire.IMAGE_ABORT, frame.id, budget));
        return complete;
    }

    void acknowledge(byte[] id) {
        enqueueControl(new Wire.Frame(Wire.ACK, id, new byte[0]));
    }

    private void enqueueControl(Wire.Frame frame) {
        synchronized (acknowledgements) {
            acknowledgements.add(frame);
            if (ackWriterScheduled) return;
            ackWriterScheduled = true;
        }
        // Keep the receive thread reading even when an outgoing image holds the write lock.
        Thread worker = new Thread(this::writeAcknowledgements, "clip-ack");
        worker.setDaemon(true);
        worker.start();
    }

    private void writeAcknowledgements() {
        try {
            synchronized (sendLock) { flushAcknowledgements(); }
        } catch (IOException failure) {
            try { close(); } catch (IOException ignored) { }
        }
    }

    private void flushAcknowledgements() throws IOException {
        for (;;) {
            Wire.Frame ack;
            synchronized (acknowledgements) {
                ack = acknowledgements.poll();
                if (ack == null) { ackWriterScheduled = false; return; }
            }
            Wire.write(socket.getOutputStream(), ack);
        }
    }

    void cancelWrite(Wire.Frame frame) {
        if (imageParts || writingFrame != frame) return;
        // Abort TCP instead of waiting for a TLS close or for buffered bytes to drain.
        try { transport.setSoLinger(true, 0); } catch (IOException ignored) { }
        try { transport.close(); } catch (IOException ignored) { }
    }

    private void readInventory() throws IOException {
        Wire.Frame frame = read();
        if (frame == null || frame.kind != Wire.IMAGE_INVENTORY) throw new IOException("Image inventory missing");
        for (int offset = 0; offset < frame.data.length; offset += Wire.ID_SIZE)
            rememberImage(imageKey(Arrays.copyOfRange(frame.data, offset, offset + Wire.ID_SIZE)));
    }

    private void rememberImage(String key) {
        knownImages.add(key);
        if (knownImages.size() > 128) {
            java.util.Iterator<String> oldest = knownImages.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private static String imageKey(byte[] id) { return java.util.Base64.getEncoder().encodeToString(id); }

    @Override public void close() throws IOException {
        socket.close();
    }

    private static SSLContext clientContext(byte[] pin) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new PinnedCertificate(pin)}, RANDOM);
            return context;
        } catch (GeneralSecurityException error) {
            throw new IOException("TLS unavailable", error);
        }
    }

    private static final class PinnedCertificate implements X509TrustManager {
        private final byte[] pin;

        PinnedCertificate(byte[] pin) { this.pin = pin; }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("No server certificate");
            try {
                chain[0].checkValidity();
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(pin, hash)) throw new CertificateException("Server pin mismatch");
            } catch (GeneralSecurityException error) {
                throw new CertificateException("Server certificate rejected", error);
            }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificates are not used");
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}

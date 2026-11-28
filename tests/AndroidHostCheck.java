package clip;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class AndroidHostCheck {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        char[] password = "clipboard-test".toCharArray();
        try (FileInputStream file = new FileInputStream(args[0])) { store.load(file, password); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 42);
        byte[] pin = java.security.MessageDigest.getInstance("SHA-256")
                .digest(store.getCertificate(store.aliases().nextElement()).getEncoded());
        LinkedBlockingQueue<Wire.Frame> received = new LinkedBlockingQueue<>();
        java.util.concurrent.CountDownLatch initialPeersLeft = new java.util.concurrent.CountDownLatch(1);
        try (SyncHost host = new SyncHost(tls, key, 0, (frame, relay) -> {
            received.add(frame);
            if (frame.kind == Wire.DELETE || frame.kind == Wire.HISTORY_TEXT && frame.id[0] != 7)
                relay.accept(frame);
        }, connected -> { if (!connected) initialPeersLeft.countDown(); }, sender -> {
            sender.send(frame(Wire.HISTORY_TEXT, 8, "host history"));
            sender.send(frame(Wire.HISTORY_IMAGE, 9, "known image"));
        })) {
            host.imageInventory(() -> new byte[0]);
            host.configureDevices("Android 主机", names -> { });
            host.start();
            try (TlsLink peer = TlsLink.connect("127.0.0.1", host.port(), pin, key)) {
                check(peer.read(), Wire.HISTORY_TEXT, "host history");
                check(peer.read(), Wire.HISTORY_IMAGE, "known image");
                System.out.println(host.port());
                System.out.flush();

                check(received.poll(8, TimeUnit.SECONDS), Wire.TEXT, "Windows copy");
                Wire.Frame firstAfterRefresh = peer.read();
                Wire.Frame secondAfterRefresh = peer.read();
                if (firstAfterRefresh.kind == Wire.TEXT) {
                    check(firstAfterRefresh, Wire.TEXT, "Windows copy");
                    check(secondAfterRefresh, Wire.HISTORY_TEXT, "host history");
                } else {
                    check(firstAfterRefresh, Wire.HISTORY_TEXT, "host history");
                    check(secondAfterRefresh, Wire.TEXT, "Windows copy");
                }
                for (int i = 11; i <= 13; i++) {
                    Wire.Frame image = received.poll(8, TimeUnit.SECONDS);
                    check(image, Wire.IMAGE, "image " + i);
                    if (!"Windows 客户端".equals(image.source))
                        throw new AssertionError("Windows image source was not relayed");
                    if (image.saveImage != (i == 11)) throw new AssertionError("Combined image negotiation");
                    if (Wire.compressionPercent(image.id) != (i == 12 ? 70 : -1)) throw new AssertionError("Compression provenance");
                    Wire.Frame forwarded = peer.read();
                    check(forwarded, Wire.IMAGE, "image " + i);
                    peer.send(new Wire.Frame(Wire.ACK, forwarded.id, new byte[0]));
                    if (i == 11) check(peer.read(), Wire.HISTORY_IMAGE, "image 11");
                }
                java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
                for (int i = 21; i <= 23; i++) {
                    Wire.Frame image = frame(Wire.IMAGE, i, "host image " + i);
                    if (i == 22) Wire.markCompressed(image.id, 70, 100);
                    if (i == 21) image = new Wire.Frame(image.kind, image.id, image.data, true);
                    if (i == 23) image.uploadProgress = percent -> {};
                    image.withSource("Android 主机");
                    host.submit(image, i == 23 ? delivered::countDown : null);
                    if (i == 21) host.publish(new Wire.Frame(Wire.HISTORY_IMAGE, image.id, image.data));
                }
                for (int i = 21; i <= 23; i++) {
                    Wire.Frame image = peer.read();
                    check(image, Wire.IMAGE, "host image " + i);
                    peer.send(new Wire.Frame(Wire.ACK, image.id, new byte[0]));
                    if (i == 21) check(peer.read(), Wire.HISTORY_IMAGE, "host image 21");
                }
                if (!delivered.await(8, TimeUnit.SECONDS)) throw new AssertionError("No delivery confirmation");
                Wire.Frame deletion = received.poll(8, TimeUnit.SECONDS);
                check(deletion, Wire.DELETE, "");
                if (!Arrays.equals(deletion.id, peer.read().id)) throw new AssertionError("Delete relay");
                host.submit(frame(Wire.TEXT, 5, "Android copy"));
                check(peer.read(), Wire.TEXT, "Android copy");
                check(received.poll(8, TimeUnit.SECONDS), Wire.HISTORY_TEXT, "edited history");
                check(peer.read(), Wire.HISTORY_TEXT, "edited history");
                host.submit(frame(Wire.TEXT, 6, "edit received"));
                check(peer.read(), Wire.TEXT, "edit received");
                check(received.poll(8, TimeUnit.SECONDS), Wire.HISTORY_TEXT, "test done");
                host.publish(frame(Wire.HISTORY_TEXT, 7, "test done"));
                check(peer.read(), Wire.HISTORY_TEXT, "test done");
            }
            if (!initialPeersLeft.await(3, TimeUnit.SECONDS)) throw new AssertionError("Initial peers did not leave");
            try (TlsLink peer = TlsLink.connect("127.0.0.1", host.port(), pin, key)) {
                check(peer.read(), Wire.HISTORY_TEXT, "host history");
                check(peer.read(), Wire.HISTORY_IMAGE, "known image");
                java.util.concurrent.atomic.AtomicBoolean expiredAck = new java.util.concurrent.atomic.AtomicBoolean();
                Wire.Frame expired = frame(Wire.IMAGE, 26, "cancelled image");
                host.submit(expired, () -> expiredAck.set(true));
                check(peer.read(), Wire.IMAGE, "cancelled image");
                // A following frame proves the first write has returned before testing ACK cancellation.
                host.publish(frame(Wire.HISTORY_TEXT, 28, "image written"));
                check(peer.read(), Wire.HISTORY_TEXT, "image written");
                host.cancelImage(expired);
                host.submit(frame(Wire.IMAGE, 27, "next image"));
                peer.send(new Wire.Frame(Wire.ACK, expired.id, new byte[0]));
                check(peer.read(), Wire.IMAGE, "next image");
                if (expiredAck.get()) throw new AssertionError("Cancelled image falsely acknowledged");
            }
            try (TlsLink slow = TlsLink.connect("127.0.0.1", host.port(), pin, key, () -> new byte[0])) {
                check(slow.read(), Wire.HISTORY_TEXT, "host history");
                check(slow.read(), Wire.HISTORY_IMAGE, "known image");
                Wire.Frame screenshot = new Wire.Frame(Wire.IMAGE, frame(Wire.IMAGE, 32, "id").id,
                        new byte[3 * Wire.PART_LIMIT], true);
                screenshot.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800);
                host.submit(screenshot, () -> { throw new AssertionError("Expired screenshot confirmed"); });
                // Without a reader, READY cannot be sent even though TCP accepted all the parts.
                Thread.sleep(1050);
                host.cancelImage(screenshot);
                host.publish(frame(Wire.HISTORY_TEXT, 33, "same connection"));
                check(slow.read(), Wire.HISTORY_TEXT, "same connection");
                host.submit(frame(Wire.IMAGE, 34, "following image"));
                Wire.Frame following = slow.read();
                check(following, Wire.IMAGE, "following image");
                slow.acknowledge(following.id);

                Wire.Frame stopped = new Wire.Frame(Wire.IMAGE, frame(Wire.IMAGE, 38, "id").id,
                        new byte[4 * Wire.PART_LIMIT]);
                stopped.uploadProgress = percent -> {
                    if (percent != 25) throw new AssertionError("Expected progress of 25%, got " + percent);
                    if (host.cancelImage(stopped)) throw new AssertionError("Partial screenshot was committed");
                    host.publish(frame(Wire.HISTORY_TEXT, 39, "cancelled during upload"));
                };
                host.submit(stopped, () -> { throw new AssertionError("Cancelled screenshot confirmed"); });
                check(slow.read(), Wire.HISTORY_TEXT, "cancelled during upload");

                Wire.Frame full = new Wire.Frame(Wire.IMAGE, frame(Wire.IMAGE, 40, "id").id,
                        new byte[4 * Wire.PART_LIMIT], true);
                java.util.List<Integer> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
                java.util.concurrent.CountDownLatch fullyWritten = new java.util.concurrent.CountDownLatch(1);
                full.awaitingUploadUi = true;
                full.uploadProgress = percent -> {
                    progress.add(percent);
                    if (percent == 100) fullyWritten.countDown();
                };
                java.util.concurrent.CountDownLatch confirmed = new java.util.concurrent.CountDownLatch(1);
                host.submit(full, confirmed::countDown);
                // The image fits in the sender's TCP buffer. The receiver has not read any image data.
                if (fullyWritten.await(400, TimeUnit.MILLISECONDS) || !progress.isEmpty())
                    throw new AssertionError("Displayed progress before the peer read any bytes: " + progress);
                java.util.concurrent.FutureTask<Wire.Frame> readImage =
                        new java.util.concurrent.FutureTask<>(slow::read);
                Thread receiving = new Thread(readImage, "test-image-receiver");
                receiving.setDaemon(true);
                receiving.start();
                if (!fullyWritten.await(3, TimeUnit.SECONDS)) throw new AssertionError("Peer did not read the image");
                try {
                    readImage.get(150, TimeUnit.MILLISECONDS);
                    throw new AssertionError("Image committed before the UI showed 100%");
                } catch (java.util.concurrent.TimeoutException expected) { }
                full.awaitingUploadUi = false;
                Wire.Frame upload = readImage.get(3, TimeUnit.SECONDS);
                if (upload.kind != Wire.IMAGE || !upload.saveImage || !Arrays.equals(upload.data, full.data))
                    throw new AssertionError("Untimed screenshot payload changed");
                slow.acknowledge(upload.id);
                if (!confirmed.await(3, TimeUnit.SECONDS) || !fullyWritten.await(3, TimeUnit.SECONDS) ||
                        !progress.equals(Arrays.asList(25, 50, 75, 100)))
                    throw new AssertionError("Missing confirmation or progress: " + progress);

                Wire.Frame part = frame(Wire.IMAGE_PART, 35, "late screenshot");
                slow.send(part);
                slow.send(new Wire.Frame(Wire.IMAGE_PREPARE, part.id, new byte[0]));
                // A probe response proves the receiver processed PREPARE while this client was online.
                slow.send(frame(Wire.HISTORY_TEXT, 36, "prepare observed"));
                check(received.poll(8, TimeUnit.SECONDS), Wire.HISTORY_TEXT, "prepare observed");
                Thread.sleep(60);
                slow.send(new Wire.Frame(Wire.IMAGE_END, part.id, new byte[]{0, 0, 0, 1}));
                slow.send(frame(Wire.HISTORY_TEXT, 37, "late commit discarded"));
                check(received.poll(8, TimeUnit.SECONDS), Wire.HISTORY_TEXT, "late commit discarded");
            }
            try (TlsLink target = TlsLink.connect("127.0.0.1", host.port(), pin, key, () -> new byte[0])) {
                check(target.read(), Wire.HISTORY_TEXT, "host history");
                check(target.read(), Wire.HISTORY_IMAGE, "known image");
                Wire.Frame unconfirmed = frame(Wire.IMAGE, 41, "awaiting clipboard");
                java.util.concurrent.CountDownLatch failed = new java.util.concurrent.CountDownLatch(1);
                unconfirmed.uploadProgress = percent -> {};
                host.submit(unconfirmed, () -> { throw new AssertionError("No ACK was sent"); }, failed::countDown);
                check(target.read(), Wire.IMAGE, "awaiting clipboard");
                try (TlsLink replacement = TlsLink.connect("127.0.0.1", host.port(), pin, key, () -> new byte[0])) {
                    check(replacement.read(), Wire.HISTORY_TEXT, "host history");
                    check(replacement.read(), Wire.HISTORY_IMAGE, "known image");
                    target.close();
                    if (!failed.await(2, TimeUnit.SECONDS)) throw new AssertionError("Disconnected delivery did not fail");
                    host.submit(frame(Wire.TEXT, 42, "replacement still connected"));
                    check(replacement.read(), Wire.TEXT, "replacement still connected");
                }
            }
        }
    }

    static Wire.Frame frame(int kind, int firstId, String text) {
        byte[] id = new byte[Wire.ID_SIZE];
        id[0] = (byte) firstId;
        return new Wire.Frame(kind, id, text.getBytes(StandardCharsets.UTF_8));
    }

    static void check(Wire.Frame frame, int kind, String text) {
        if (frame == null || frame.kind != kind ||
                !text.equals(new String(frame.data, StandardCharsets.UTF_8)))
            throw new AssertionError("Expected " + kind + ": " + text + ", got " +
                    (frame == null ? "EOF" : frame.kind + ": " + new String(frame.data, StandardCharsets.UTF_8)));
    }
}

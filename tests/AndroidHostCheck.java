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
        try (SyncHost host = new SyncHost(tls, key, 0, (frame, relay) -> {
            received.add(frame);
            if (frame.kind == Wire.DELETE || frame.kind == Wire.HISTORY_TEXT) relay.accept(frame);
        }, connected -> {}, sender -> sender.send(frame(Wire.HISTORY_TEXT, 8, "host history")))) {
            host.start();
            try (TlsLink peer = TlsLink.connect("127.0.0.1", host.port(), pin, key)) {
                check(peer.read(), Wire.HISTORY_TEXT, "host history");
                System.out.println(host.port());
                System.out.flush();

                check(received.poll(8, TimeUnit.SECONDS), Wire.TEXT, "Windows copy");
                check(peer.read(), Wire.TEXT, "Windows copy");
                for (int i = 11; i <= 13; i++) {
                    Wire.Frame image = received.poll(8, TimeUnit.SECONDS);
                    check(image, Wire.IMAGE, "image " + i);
                    if (Wire.compressionPercent(image.id) != (i == 12 ? 70 : -1)) throw new AssertionError("Compression provenance");
                    Wire.Frame forwarded = peer.read();
                    check(forwarded, Wire.IMAGE, "image " + i);
                    peer.send(new Wire.Frame(Wire.ACK, forwarded.id, new byte[0]));
                }
                java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
                for (int i = 21; i <= 23; i++) {
                    Wire.Frame image = frame(Wire.IMAGE, i, "host image " + i);
                    if (i == 22) Wire.markCompressed(image.id, 70, 100);
                    host.submit(image, i == 23 ? delivered::countDown : null);
                }
                for (int i = 21; i <= 23; i++) {
                    Wire.Frame image = peer.read();
                    check(image, Wire.IMAGE, "host image " + i);
                    peer.send(new Wire.Frame(Wire.ACK, image.id, new byte[0]));
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
            throw new AssertionError("Expected " + kind + ": " + text);
    }
}

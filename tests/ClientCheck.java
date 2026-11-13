package clip;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ClientCheck {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        byte[] pin = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(args[1])));
        byte[] key = new byte[32];
        Arrays.fill(key, (byte)42);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(2);
        try (SyncClient client = new SyncClient("127.0.0.1", port, pin, key, frame -> {
            if (frame.kind != Wire.TEXT ||
                    !new String(frame.data, StandardCharsets.UTF_8).equals("已收到"))
                throw new AssertionError("Incorrect incoming content");
            received.countDown();
        }, connected -> { if (!connected) closed.countDown(); })) {
            client.submit(new Wire.Frame(Wire.TEXT, new byte[16], "旧".getBytes(StandardCharsets.UTF_8)));
            client.submit(new Wire.Frame(Wire.TEXT, new byte[16], "新".getBytes(StandardCharsets.UTF_8)));
            client.start();
            if (!received.await(12, TimeUnit.SECONDS)) throw new AssertionError("Reconnect timed out");
            if (!closed.await(4000, TimeUnit.MILLISECONDS)) throw new AssertionError("Peer did not finish");
        }
    }
}

package clip;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class HistorySyncPeersCheck {
    private static final class Peer implements AutoCloseable {
        final SyncClient client;
        final CountDownLatch connected = new CountDownLatch(1);
        final ConcurrentHashMap<Byte, String> history = new ConcurrentHashMap<>();
        final java.util.Set<Byte> deleted = ConcurrentHashMap.newKeySet();

        Peer(int port, byte[] pairing) {
            client = new SyncClient("127.0.0.1", port, Arrays.copyOfRange(pairing, 0, 32),
                Arrays.copyOfRange(pairing, 32, 64), frame -> {
                    byte id = frame.id[15];
                    if (frame.kind == Wire.DELETE) {
                        deleted.add(id);
                        history.remove(id);
                    } else if (frame.kind == Wire.HISTORY_TEXT && !deleted.contains(id)) {
                        history.put(id, new String(frame.data, StandardCharsets.UTF_8));
                    }
                }, online -> { if (online) connected.countDown(); });
            client.start();
        }

        void awaitConnection() throws InterruptedException {
            if (!connected.await(8, TimeUnit.SECONDS)) throw new AssertionError("Peer could not connect concurrently");
        }

        void publish(int kind, int id, String text) {
            byte[] key = new byte[Wire.ID_SIZE];
            key[15] = (byte) id;
            client.publish(new Wire.Frame(kind, key, text.getBytes(StandardCharsets.UTF_8)));
        }

        void requestWindowsEdit() {
            byte[] key = new byte[Wire.ID_SIZE];
            key[15] = 10;
            client.submit(new Wire.Frame(Wire.TEXT, key, "编辑".getBytes(StandardCharsets.UTF_8)));
        }

        void awaitEdit(byte previous, byte edited, String text) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                if (text.equals(history.get(edited)) && deleted.contains(previous) && !history.containsKey(previous))
                    return;
                Thread.sleep(20);
            }
            throw new AssertionError("History edit did not converge: " + history + ", deleted: " + deleted);
        }

        void awaitDeletion(byte id) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                if (deleted.contains(id)) return;
                Thread.sleep(20);
            }
            throw new AssertionError("Windows did not accept the deletion");
        }

        @Override public void close() { client.close(); }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        byte[] pairing = Base64.getUrlDecoder().decode(args[1]);
        try (Peer editor = new Peer(port, pairing)) {
            editor.awaitConnection();
            try (Peer other = new Peer(port, pairing)) {
                other.awaitConnection();
                editor.publish(Wire.HISTORY_TEXT, 1, "原文");
                editor.publish(Wire.HISTORY_TEXT, 2, "第一次编辑");
                editor.publish(Wire.DELETE, 1, "");
                other.awaitEdit((byte) 1, (byte) 2, "第一次编辑");
            }
            editor.publish(Wire.HISTORY_TEXT, 3, "离线编辑");
            editor.publish(Wire.DELETE, 2, "");
            editor.awaitDeletion((byte) 2);
            try (Peer reconnected = new Peer(port, pairing)) {
                reconnected.awaitConnection();
                reconnected.awaitEdit((byte) 2, (byte) 3, "离线编辑");
                if (reconnected.history.containsKey((byte) 1)) throw new AssertionError("Deleted original reappeared");
                editor.requestWindowsEdit();
                editor.awaitEdit((byte) 3, (byte) 4, "电脑编辑");
                reconnected.awaitEdit((byte) 3, (byte) 4, "电脑编辑");
            }
        }
        System.out.println("PASS: concurrent Android clients, edits in both directions and replay after reconnect");
    }
}

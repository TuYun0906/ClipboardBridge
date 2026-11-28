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
        final ConcurrentHashMap<Byte, String> sources = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Byte, Wire.Frame> localOnly = new ConcurrentHashMap<>();
        volatile String roster = "";
        final java.util.Set<Byte> deleted = ConcurrentHashMap.newKeySet();

        Peer(int port, byte[] pairing, String name) {
            client = new SyncClient("127.0.0.1", port, Arrays.copyOfRange(pairing, 0, 32),
                Arrays.copyOfRange(pairing, 32, 64), frame -> {
                    byte id = frame.id[15];
                    if (frame.kind == Wire.DELETE) {
                        deleted.add(id);
                        history.remove(id);
                    } else if ((frame.kind == Wire.HISTORY_TEXT || frame.kind == Wire.HISTORY_IMAGE ||
                            (frame.kind == Wire.IMAGE && frame.saveImage)) && !deleted.contains(id)) {
                        history.put(id, new String(frame.data, StandardCharsets.UTF_8));
                        if (frame.source != null) sources.put(id, frame.source);
                    }
                }, online -> { if (online) connected.countDown(); });
            client.imageInventory(() -> new byte[0]);
            client.configureDevices(name, names -> roster = names);
            client.replayWith(sender -> {
                for (Wire.Frame frame : localOnly.values()) sender.send(frame);
            });
            client.start();
        }

        void awaitConnection() throws InterruptedException {
            if (!connected.await(8, TimeUnit.SECONDS)) throw new AssertionError("Peer could not connect concurrently");
        }

        private static Wire.Frame frame(int kind, int id, String text) {
            byte[] key = new byte[Wire.ID_SIZE];
            key[15] = (byte) id;
            return new Wire.Frame(kind, key, text.getBytes(StandardCharsets.UTF_8), kind == Wire.IMAGE);
        }

        void publish(int kind, int id, String text) { client.publish(frame(kind, id, text)); }

        void sendManual(int id, String text) throws InterruptedException { sendManual(Wire.TEXT, id, text); }

        void sendManual(int kind, int id, String data) throws InterruptedException {
            CountDownLatch done = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean();
            client.submitManual(frame(kind, id, data), result -> {
                success.set(result);
                done.countDown();
            });
            if (!done.await(5, TimeUnit.SECONDS) || !success.get())
                throw new AssertionError("Manual text did not receive delivery confirmation");
        }

        void waitFor(java.util.function.BooleanSupplier predicate, String detail) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
            if (!predicate.getAsBoolean()) throw new AssertionError(detail + ": " + roster + " / " + sources);
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
        Endpoint mapped = Endpoint.parse("clip.example.com:50001", 47832);
        Endpoint direct = Endpoint.parse("100.64.1.2", 47832);
        Endpoint ipv6 = Endpoint.parse("[2001:db8::1]:443", 47832);
        if (mapped == null || !mapped.host.equals("clip.example.com") || mapped.port != 50001 ||
                direct == null || direct.port != 47832 || ipv6 == null || ipv6.port != 443)
            throw new AssertionError("Valid clipboard endpoint rejected");
        for (String invalid : new String[] {"", "clip.example.com:", "clip.example.com:0",
                "clip.example.com:65536", "https://clip.example.com", "clip.example.com/path", "user@clip.example.com"})
            if (Endpoint.parse(invalid, 47832) != null)
                throw new AssertionError("Invalid clipboard endpoint accepted: " + invalid);
        int port = Integer.parseInt(args[0]);
        byte[] pairing = Base64.getUrlDecoder().decode(args[1]);
        try (Peer editor = new Peer(port, pairing, "测试手机")) {
            editor.awaitConnection();
            try (Peer other = new Peer(port, pairing, "测试平板")) {
                other.awaitConnection();
                editor.publish(Wire.HISTORY_TEXT, 1, "原文");
                editor.publish(Wire.HISTORY_TEXT, 2, "第一次编辑");
                editor.publish(Wire.DELETE, 1, "");
                other.awaitEdit((byte) 1, (byte) 2, "第一次编辑");
                editor.waitFor(() -> editor.roster.equals("测试电脑\n测试平板\n测试手机"),
                        "Host-first online roster is incorrect");
                editor.sendManual(21, "重复内容");
                other.waitFor(() -> "测试手机".equals(other.sources.get((byte)21)), "Original sender missing");
                other.sendManual(22, "重复内容");
                editor.waitFor(() -> "测试平板".equals(editor.sources.get((byte)22)) && editor.deleted.contains((byte)21),
                        "Duplicate history did not use the latest sender");
                editor.sendManual(Wire.IMAGE, 24, "相同图片字节");
                other.waitFor(() -> "测试手机".equals(other.sources.get((byte)24)), "Original image sender missing");
                other.sendManual(Wire.IMAGE, 25, "相同图片字节");
                editor.waitFor(() -> "测试平板".equals(editor.sources.get((byte)25)) && editor.deleted.contains((byte)24),
                        "Duplicate image did not use the latest sender");
                editor.localOnly.put((byte)23, Peer.frame(Wire.HISTORY_TEXT, 23, "待同步记录").withSource("测试手机"));
                if (!other.client.refreshHistory()) throw new AssertionError("Refresh request rejected");
                other.waitFor(() -> "待同步记录".equals(other.history.get((byte)23)) &&
                        "测试手机".equals(other.sources.get((byte)23)), "Refresh did not collect another peer's history");
                editor.sendManual(11, "检查电脑回执");
                editor.waitFor(() -> "电脑收到回执".equals(editor.history.get((byte)32)) &&
                        "电脑收到回执".equals(editor.history.get((byte)33)), "Windows host missed a manual send ACK");
            }
            editor.waitFor(() -> editor.roster.equals("测试电脑\n测试手机"), "Disconnected device still listed");
            editor.publish(Wire.HISTORY_TEXT, 3, "离线编辑");
            editor.publish(Wire.DELETE, 2, "");
            editor.awaitDeletion((byte) 2);
            try (Peer reconnected = new Peer(port, pairing, "测试平板")) {
                reconnected.awaitConnection();
                reconnected.awaitEdit((byte) 2, (byte) 3, "离线编辑");
                if (reconnected.history.containsKey((byte) 1)) throw new AssertionError("Deleted original reappeared");
                editor.requestWindowsEdit();
                editor.awaitEdit((byte) 3, (byte) 4, "电脑编辑");
                reconnected.awaitEdit((byte) 3, (byte) 4, "电脑编辑");
            }
        }
        System.out.println("PASS: device roster, send ACK, latest sender, forced refresh, bidirectional edits and reconnect replay");
    }
}

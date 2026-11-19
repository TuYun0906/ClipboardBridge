package clip;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

final class ClipHistory {
    private static final int IMAGE_HISTORY_LIMIT = 50;
    static final String SAVE_IMAGES = "historyImages";
    private final File folder;
    private final Context context;
    private final Map<String, Entry> records = new HashMap<>();
    private final Set<String> deleted = new HashSet<>();

    ClipHistory(Context context) {
        this.context = context.getApplicationContext();
        folder = new File(context.getFilesDir(), "history");
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.matches("[a-f0-9]{32}\\.deleted")) deleted.add(name.substring(0, 32));
        }
        for (File file : files) {
            if (!file.getName().endsWith(".clip")) continue;
            try {
                Wire.Frame frame = read(file);
                if (frame.kind != Wire.TEXT && frame.kind != Wire.IMAGE) continue;
                String key = key(frame.id);
                if (deleted.contains(key) || records.containsKey(key)) file.delete();
                else records.put(key, new Entry(file, key, frame));
            } catch (IOException ignored) { }
        }
        Set<String> texts = new HashSet<>();
        Set<String> images = new HashSet<>();
        try {
            for (Entry entry : entries())
                if (!(entry.kind == Wire.IMAGE ? images : texts).add(entry.contentKey)) delete(entry.key);
            trimImages(new ArrayList<>());
        } catch (IOException ignored) { }
    }

    synchronized List<Wire.Frame> apply(Wire.Frame frame, boolean local) throws IOException {
        String key = key(frame.id);
        List<Wire.Frame> changes = new ArrayList<>();
        if (frame.kind == Wire.DELETE) {
            if (delete(key)) changes.add(frame);
            return changes;
        }
        if (deleted.contains(key) || records.containsKey(key) ||
                (frame.kind == Wire.IMAGE && local && !Prefs.get(context).getBoolean(SAVE_IMAGES, false))) return changes;
        File file = new File(folder, key + ".clip");
        Entry incoming = new Entry(file, key, frame);
        Entry duplicate = null;
        for (Entry entry : records.values()) {
            if (entry.kind == incoming.kind && entry.contentKey.equals(incoming.contentKey)) {
                duplicate = entry;
                break;
            }
        }
        if (duplicate != null && order(duplicate.key).compareTo(order(key)) > 0) {
            if (delete(key)) changes.add(tombstone(key));
            return changes;
        }
        ensureFolder();
        File pending = new File(file.getPath() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(pending.toPath())) { Wire.write(out, frame); }
            Files.move(pending.toPath(), file.toPath());
        } finally { pending.delete(); }
        records.put(key, incoming);
        changes.add(asHistory(frame));
        if (duplicate != null && delete(duplicate.key)) changes.add(tombstone(duplicate.key));
        if (incoming.kind == Wire.IMAGE) trimImages(changes);
        return changes;
    }

    synchronized List<Wire.Frame> edit(String key, Wire.Frame replacement) throws IOException {
        Entry previous = records.get(key);
        if (previous == null || previous.kind != Wire.TEXT ||
                previous.text.equals(new String(replacement.data, StandardCharsets.UTF_8)))
            return Collections.emptyList();
        Wire.Frame removal = tombstone(key);
        byte[] id = replacement.id.clone();
        System.arraycopy(removal.id, 0, id, 0, 6);
        String replacementKey = key(id);
        if (deleted.contains(replacementKey) || records.containsKey(replacementKey))
            throw new IOException("Duplicate history ID");
        List<Wire.Frame> changes = apply(new Wire.Frame(Wire.TEXT, id, replacement.data), true);
        if (delete(key)) changes.add(0, removal);
        return changes;
    }

    synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>(records.values());
        result.sort(Comparator.comparing((Entry entry) -> order(entry.key)).reversed());
        return result;
    }

    synchronized byte[] imageInventory() {
        ByteArrayOutputStream ids = new ByteArrayOutputStream();
        for (Entry entry : records.values()) {
            if (entry.kind != Wire.IMAGE) continue;
            byte[] id = tombstone(entry.key).id;
            ids.write(id, 0, id.length);
            if (ids.size() == IMAGE_HISTORY_LIMIT * Wire.ID_SIZE) break;
        }
        return ids.toByteArray();
    }

    void replay(SyncClient.Sender sender) throws IOException {
        List<String> tombstones;
        List<Entry> snapshot;
        synchronized (this) {
            tombstones = new ArrayList<>(deleted);
            snapshot = entries();
        }
        for (String key : tombstones) sender.send(tombstone(key));
        for (Entry entry : snapshot) {
            Wire.Frame frame;
            synchronized (this) { frame = records.containsKey(entry.key) ? read(entry.file) : null; }
            if (frame != null) sender.send(asHistory(frame));
        }
    }

    private void ensureFolder() throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("History folder unavailable");
    }

    private boolean delete(String key) throws IOException {
        if (deleted.contains(key)) return false;
        ensureFolder();
        File marker = new File(folder, key + ".deleted");
        if (!marker.createNewFile() && !marker.isFile()) throw new IOException("Cannot delete history item");
        deleted.add(key);
        Entry previous = records.remove(key);
        if (previous != null) previous.file.delete();
        return true;
    }

    private void trimImages(List<Wire.Frame> changes) throws IOException {
        List<Entry> images = new ArrayList<>();
        for (Entry entry : records.values())
            if (entry.kind == Wire.IMAGE) images.add(entry);
        images.sort(Comparator.comparing((Entry entry) -> order(entry.key)).reversed());
        for (int i = IMAGE_HISTORY_LIMIT; i < images.size(); i++) {
            String key = images.get(i).key;
            if (delete(key)) changes.add(tombstone(key));
        }
    }

    static Wire.Frame read(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            Wire.Frame frame = Wire.read(in);
            if (frame == null) throw new IOException("Empty history entry");
            return frame;
        }
    }

    private static String key(byte[] id) {
        char[] chars = new char[id.length * 2];
        String digits = "0123456789abcdef";
        for (int i = 0; i < id.length; i++) {
            chars[2 * i] = digits.charAt((id[i] & 255) >>> 4);
            chars[2 * i + 1] = digits.charAt(id[i] & 15);
        }
        return new String(chars);
    }

    private static String order(String key) {
        return (copyTime(key) != 0 ? "1" : "0") + key;
    }

    private static long copyTime(String key) {
        long time = Long.parseLong(key.substring(0, 12), 16);
        return time >= 1577836800000L && time <= 4102444800000L ? time : 0;
    }

    static Wire.Frame tombstone(String key) {
        byte[] id = new byte[Wire.ID_SIZE];
        for (int i = 0; i < id.length; i++) id[i] = (byte) Integer.parseInt(key.substring(i * 2, i * 2 + 2), 16);
        return new Wire.Frame(Wire.DELETE, id, new byte[0]);
    }

    private static Wire.Frame asHistory(Wire.Frame frame) {
        return new Wire.Frame(frame.kind == Wire.TEXT ? Wire.HISTORY_TEXT : Wire.HISTORY_IMAGE, frame.id, frame.data);
    }

    static final class Entry {
        final File file;
        final String key, text, contentKey;
        final int kind;
        final long copiedAt;
        final int compressionPercent;
        Entry(File file, String key, Wire.Frame frame) {
            this.file = file;
            this.key = key;
            kind = frame.kind;
            copiedAt = copyTime(key);
            compressionPercent = kind == Wire.IMAGE ? Wire.compressionPercent(frame.id) : -1;
            text = kind == Wire.TEXT ? new String(frame.data, StandardCharsets.UTF_8) : "图片";
            contentKey = kind == Wire.IMAGE ? imageDigest(frame.data) : text;
        }
    }

    private static String imageDigest(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}

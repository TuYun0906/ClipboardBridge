package clip;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

final class ClipHistory {
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
        try {
            for (Entry entry : entries())
                if (entry.kind == Wire.TEXT && !texts.add(entry.text)) delete(entry.key);
            trim(new ArrayList<>());
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
        if (frame.kind == Wire.TEXT) {
            for (Entry entry : records.values()) {
                if (entry.kind == Wire.TEXT && entry.text.equals(incoming.text)) { duplicate = entry; break; }
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
        trim(changes);
        return changes;
    }

    synchronized List<Wire.Frame> edit(String key, Wire.Frame replacement) throws IOException {
        Entry previous = records.get(key);
        if (previous == null || previous.kind != Wire.TEXT ||
                previous.text.equals(new String(replacement.data, StandardCharsets.UTF_8)))
            return Collections.emptyList();
        List<Wire.Frame> changes = apply(replacement, true);
        if (delete(key)) changes.add(tombstone(key));
        return changes;
    }

    synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>(records.values());
        result.sort(Comparator.comparing((Entry entry) -> order(entry.key)).reversed());
        return result;
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

    private void trim(List<Wire.Frame> changes) throws IOException {
        int count = 0;
        long bytes = 0;
        for (Entry entry : entries()) {
            bytes += entry.file.length();
            if (++count <= 100 && bytes <= 64L * 1024 * 1024) continue;
            if (delete(entry.key)) changes.add(tombstone(entry.key));
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
        long time = Long.parseLong(key.substring(0, 12), 16);
        return (time >= 1577836800000L && time <= 4102444800000L ? "1" : "0") + key;
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
        final String key, text;
        final int kind;
        Entry(File file, String key, Wire.Frame frame) {
            this.file = file;
            this.key = key;
            kind = frame.kind;
            text = kind == Wire.TEXT ? new String(frame.data, StandardCharsets.UTF_8) : "图片";
        }
    }
}

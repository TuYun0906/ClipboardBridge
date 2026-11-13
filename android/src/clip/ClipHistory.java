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

    ClipHistory(Context context) {
        this.context = context.getApplicationContext();
        folder = new File(context.getFilesDir(), "history");
    }

    synchronized void add(Wire.Frame frame) {
        if (frame.kind == Wire.IMAGE && !Prefs.get(context).getBoolean(SAVE_IMAGES, false)) return;
        if (!folder.isDirectory() && !folder.mkdirs()) return;
        File file = new File(folder, String.format(Locale.ROOT, "%019d-%s.clip", System.currentTimeMillis(), UUID.randomUUID()));
        File pending = new File(file.getPath() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(pending.toPath())) { Wire.write(out, frame); }
            Files.move(pending.toPath(), file.toPath());
            File[] files = files();
            long bytes = 0;
            for (int i = 0; i < files.length; i++) {
                bytes += files[i].length();
                if (i >= 100 || bytes > 64L * 1024 * 1024) files[i].delete();
            }
        } catch (IOException ignored) {
        } finally { pending.delete(); }
    }

    synchronized List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (File file : files()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                int kind = in.read();
                if (kind == Wire.IMAGE) entries.add(new Entry(file, "图片"));
                if (kind == Wire.TEXT) {
                    Wire.Frame frame = read(file);
                    entries.add(new Entry(file, new String(frame.data, StandardCharsets.UTF_8)));
                }
            } catch (IOException ignored) { }
        }
        return entries;
    }

    static Wire.Frame read(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            Wire.Frame frame = Wire.read(in);
            if (frame == null) throw new IOException("Empty history entry");
            return frame;
        }
    }

    private File[] files() {
        File[] files = folder.listFiles((parent, name) -> name.endsWith(".clip"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return files;
    }

    static final class Entry {
        final File file;
        final String text;
        Entry(File file, String text) { this.file = file; this.text = text; }
    }
}

package clip;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore.Images.Media;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.function.Consumer;

final class MediaWatcher extends ContentObserver {
    private final ContentResolver resolver;
    private final Handler worker;
    private final Consumer<Uri> onImage;
    private final Runnable scan = this::scan;
    private final LinkedHashSet<Long> seen = new LinkedHashSet<>();
    private boolean enabled;
    private long floor;

    MediaWatcher(Context context, Handler worker, Consumer<Uri> onImage) {
        super(worker);
        resolver = context.getContentResolver();
        this.worker = worker;
        this.onImage = onImage;
    }

    void update(boolean desired) {
        worker.post(() -> {
            if (enabled == desired) return;
            if (desired) {
                floor = System.currentTimeMillis() / 1000;
                seen.clear();
                enabled = true;
                try {
                    resolver.registerContentObserver(Media.EXTERNAL_CONTENT_URI, true, this);
                    query(true);
                } catch (SecurityException denied) { disable(); }
            } else disable();
        });
    }

    @Override public void onChange(boolean selfChange, Uri uri) {
        worker.removeCallbacks(scan);
        if (enabled) worker.postDelayed(scan, 280);
    }

    private void scan() {
        if (!enabled) return;
        try { query(false); }
        catch (SecurityException denied) { disable(); }
    }

    private void disable() {
        enabled = false;
        worker.removeCallbacks(scan);
        resolver.unregisterContentObserver(this);
    }

    private void query(boolean baseline) {
        String[] columns = {Media._ID, Media.RELATIVE_PATH};
        Bundle params = new Bundle();
        params.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                Media.DATE_ADDED + " >= ? AND " + Media.IS_PENDING + " = 0");
        params.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{Long.toString(floor)});
        params.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{Media.DATE_ADDED, Media._ID});
        params.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        params.putInt(ContentResolver.QUERY_ARG_LIMIT, 80);
        try (Cursor rows = resolver.query(Media.EXTERNAL_CONTENT_URI, columns, params, null)) {
            if (rows == null) return;
            Uri newest = null;
            while (rows.moveToNext()) {
                long id = rows.getLong(0);
                String path = rows.getString(1);
                if (!captureFolder(path) || !seen.add(id)) continue;
                if (!baseline && newest == null) newest = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id);
            }
            while (seen.size() > 320) seen.remove(seen.iterator().next());
            if (newest != null) onImage.accept(newest);
        }
    }

    private static boolean captureFolder(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("dcim/camera/") || lower.equals("dcim/camera") ||
                lower.contains("/screenshots/") || lower.endsWith("/screenshots") ||
                lower.startsWith("screenshots/");
    }
}

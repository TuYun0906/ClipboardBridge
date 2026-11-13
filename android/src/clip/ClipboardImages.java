package clip;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

public final class ClipboardImages extends ContentProvider {
    static Uri store(Context context, byte[] data, String extension) throws IOException {
        if (ImageFormat.mime(extension) == null) throw new IOException("Unsupported image");
        File dir = directory(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Image cache unavailable");
        File file = new File(dir, UUID.randomUUID() + "." + extension);
        try {
            Files.write(file.toPath(), data);
        } catch (IOException error) {
            Files.deleteIfExists(file.toPath());
            throw error;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = 3; i < files.length; i++) {
                if (!files[i].equals(file)) files[i].delete();
            }
        }
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".images")
                .appendPath(file.getName()).build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String name = validName(uri);
        return name == null ? null : ImageFormat.mime(name.substring(name.lastIndexOf('.') + 1));
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = validName(uri);
        if (!"r".equals(mode) || name == null) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(directory(getContext()), name),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String name = validName(uri);
        if (name == null) return null;
        File file = new File(directory(getContext()), name);
        if (!file.isFile()) return null;
        String[] columns = projection != null ? projection :
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            switch (column) {
                case OpenableColumns.DISPLAY_NAME: row.add(name); break;
                case OpenableColumns.SIZE: row.add(file.length()); break;
                default: row.add(null); break;
            }
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }

    private static File directory(Context context) { return new File(context.getCacheDir(), "received-images"); }

    private static String validName(Uri uri) {
        if (uri.getPathSegments().size() != 1) return null;
        String name = uri.getLastPathSegment();
        return name != null && name.matches("[a-f0-9-]{36}\\.(png|jpg|gif|bmp)") ? name : null;
    }
}

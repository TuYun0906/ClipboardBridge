package clip;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class AndroidClipboard {
    private static final long PIXEL_LIMIT = 24_000_000L;
    private final Context context;
    private final ClipboardManager manager;
    private final String remoteLabel;
    private long lastTimestamp = -1;
    private Object lastValue;

    AndroidClipboard(Context context) {
        this.context = context.getApplicationContext();
        manager = this.context.getSystemService(ClipboardManager.class);
        remoteLabel = this.context.getPackageName() + ".received";
    }

    void listen(ClipboardManager.OnPrimaryClipChangedListener listener) {
        manager.addPrimaryClipChangedListener(listener);
    }

    void unlisten(ClipboardManager.OnPrimaryClipChangedListener listener) {
        manager.removePrimaryClipChangedListener(listener);
    }

    // Called from the focused UI thread. It only takes a snapshot, leaving image I/O to a worker.
    Object snapshot(boolean focused, boolean autoSend) {
        if (!focused || !autoSend) return null;
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        ClipDescription description = clip.getDescription();
        if (remoteLabel.contentEquals(String.valueOf(description.getLabel()))) return null;
        ClipData.Item item = clip.getItemAt(0);
        Object value;
        if (item.getUri() != null && description.hasMimeType("image/*"))
            value = item.getUri();
        else if (item.getText() != null)
            value = item.getText().toString();
        else return null;
        long timestamp = description.getTimestamp();
        if (timestamp == lastTimestamp && value.equals(lastValue)) return null;
        lastTimestamp = timestamp;
        lastValue = value;
        return value;
    }

    Wire.Frame makeFrame(Object snapshot) throws IOException { return makeFrame(snapshot, false); }

    Wire.Frame makeFrame(Object snapshot, boolean compress) throws IOException {
        if (snapshot instanceof Uri) {
            byte[] imageId = id();
            return new Wire.Frame(Wire.IMAGE, imageId, imageBytes((Uri) snapshot, compress, imageId));
        }
        if (snapshot instanceof ParcelFileDescriptor) {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream((ParcelFileDescriptor)snapshot)) {
                byte[] imageId = id();
                return new Wire.Frame(Wire.IMAGE, imageId, imageBytes(in, compress, imageId));
            }
        }
        if (!(snapshot instanceof String)) throw new IOException("No clipboard content");
        String text = (String) snapshot;
        if (text.isEmpty() || text.length() > Wire.TEXT_LIMIT) throw new IOException("Invalid text length");
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > Wire.TEXT_LIMIT) throw new IOException("Text exceeds size limit");
        return new Wire.Frame(Wire.TEXT, id(), data);
    }

    // Image storage and validation run on the receiving worker before acknowledging the frame.
    void apply(Wire.Frame frame) throws IOException {
        ClipData clip;
        if (frame.kind == Wire.TEXT) {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(frame.data)).toString();
            clip = ClipData.newPlainText(remoteLabel, text);
        } else if (frame.kind == Wire.IMAGE) {
            String extension = ImageFormat.extension(frame.data);
            validateImage(frame.data);
            Uri uri = ClipboardImages.store(context, frame.data, extension);
            clip = ClipData.newUri(context.getContentResolver(), remoteLabel, uri);
        } else throw new IOException("Unexpected clipboard frame");
        manager.setPrimaryClip(clip);
    }

    private byte[] imageBytes(Uri uri, boolean compress, byte[] imageId) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Image unavailable");
            return imageBytes(in, compress, imageId);
        } catch (SecurityException error) { throw new IOException("Image cannot be read", error); }
    }

    private byte[] imageBytes(InputStream in, boolean compress, byte[] imageId) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) != -1;) {
                if (out.size() + n > Wire.IMAGE_LIMIT) throw new IOException("Image exceeds size limit");
                out.write(buffer, 0, n);
            }
            byte[] data = out.toByteArray();
            BitmapFactory.Options bounds = validateImage(data);
            String format = ImageFormat.extension(data);
            if (compress && !"gif".equals(format)) {
                byte[] candidate = compress(data, bounds);
                if (candidate != null && candidate.length < data.length) {
                    Wire.markCompressed(imageId);
                    return candidate;
                }
            }
            if (format != null) return data;

            Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (decoded == null) throw new IOException("Invalid image");
            try {
                out.reset();
                if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, out) ||
                        out.size() > Wire.IMAGE_LIMIT) throw new IOException("Converted image exceeds size limit");
                return out.toByteArray();
            } finally { decoded.recycle(); }
    }

    private static byte[] compress(byte[] bytes, BitmapFactory.Options bounds) throws IOException {
        final int limit = 1920;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / (opts.inSampleSize * 2) >= limit) opts.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (decoded == null) throw new IOException("Invalid image");
        Bitmap scaled = decoded, oriented = decoded;
        try {
            int side = Math.max(decoded.getWidth(), decoded.getHeight());
            if (side > limit) {
                float ratio = (float)limit / side;
                scaled = Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(decoded.getWidth() * ratio)),
                        Math.max(1, Math.round(decoded.getHeight() * ratio)), true);
            }

            oriented = scaled;
            try {
                android.media.ExifInterface exif = new android.media.ExifInterface(new java.io.ByteArrayInputStream(bytes));
                int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                if (orientation >= 2 && orientation <= 8) {
                    android.graphics.Matrix transform = new android.graphics.Matrix();
                    if (orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7)
                        transform.setScale(-1f, 1f);
                    if (orientation == 3 || orientation == 4) transform.postRotate(180);
                    if (orientation == 6 || orientation == 7) transform.postRotate(90);
                    if (orientation == 5 || orientation == 8) transform.postRotate(270);
                    oriented = Bitmap.createBitmap(scaled, 0, 0, scaled.getWidth(), scaled.getHeight(), transform, true);
                }
            } catch (IOException ignored) { }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Bitmap.CompressFormat target = hasTransparency(oriented) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
            return oriented.compress(target, 82, output) && output.size() <= Wire.IMAGE_LIMIT ? output.toByteArray() : null;
        } finally {
            if (oriented != scaled && oriented != decoded) oriented.recycle();
            if (scaled != decoded) scaled.recycle();
            decoded.recycle();
        }
    }

    private static boolean hasTransparency(Bitmap bitmap) {
        if (!bitmap.hasAlpha()) return false;
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int pixel : row) if ((pixel >>> 24) != 255) return true;
        }
        return false;
    }

    private static BitmapFactory.Options validateImage(byte[] data) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                (long) bounds.outWidth * bounds.outHeight > PIXEL_LIMIT) throw new IOException("Invalid image dimensions");
        return bounds;
    }

    private static byte[] id() {
        UUID uuid = UUID.randomUUID();
        byte[] id = ByteBuffer.allocate(Wire.ID_SIZE).putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits()).array();
        long time = System.currentTimeMillis();
        for (int i = 5; i >= 0; i--, time >>= 8) id[i] = (byte) time;
        return id;
    }
}

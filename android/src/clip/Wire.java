package clip;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class Wire {
    static final int AUTH = 1, TEXT = 2, IMAGE = 3, ACK = 4, HISTORY_TEXT = 5, HISTORY_IMAGE = 6, DELETE = 7;
    static final int IMAGE_INVENTORY = 8, SAVED_IMAGE = 9;
    static final int IMAGE_PART = 10, SAVED_IMAGE_PART = 11, IMAGE_END = 12, IMAGE_ABORT = 13;
    static final int IMAGE_PREPARE = 14, IMAGE_READY = 15, IMAGE_PROGRESS = 16;
    static final int DEVICE = 17, ROSTER = 18, SOURCE = 19, REFRESH = 20, REFRESH_INVENTORY = 21;
    static final int UPLOAD_PROGRESS_UNAVAILABLE = -1;
    static final int PART_LIMIT = 32 * 1024;
    private static final byte[] PART_VERSION = {67, 67, 73, 49};
    private static final byte[] SYNC_VERSION = {67, 66, 73, 77, 71, 2, 117, 57};

    static void offerImageSync(byte[] id) {
        System.arraycopy(SYNC_VERSION, 0, id, 0, SYNC_VERSION.length);
        System.arraycopy(PART_VERSION, 0, id, 8, PART_VERSION.length);
        id[12] = 'R';
        id[13] = 'C';
        id[14] = 'V';
    }

    static boolean offersImageParts(byte[] id) {
        for (int i = 0; i < PART_VERSION.length; i++) if (id[8 + i] != PART_VERSION[i]) return false;
        return true;
    }

    static boolean offersImageSync(byte[] id) {
        for (int i = 0; i < SYNC_VERSION.length; i++) if (id[i] != SYNC_VERSION[i]) return false;
        return true;
    }

    static boolean offersImageProgress(byte[] id) {
        return id[12] == 'R' && id[13] == 'C' && id[14] == 'V';
    }

    static byte[] acceptImageProgress(byte[] id) {
        byte[] reply = id.clone();
        reply[15] ^= 4;
        return reply;
    }

    static byte[] acceptImageSync(byte[] id) { return acceptImageSync(id, false); }

    static byte[] acceptImageSync(byte[] id, boolean parts) {
        byte[] reply = id.clone();
        reply[15] ^= parts ? 2 : 1;
        return reply;
    }

    static final int ID_SIZE = 16, KEY_SIZE = 32;
    // Preserve provenance without changing the frame layout understood by older peers.
    private static final byte[] COMPRESSED_MARK = {(byte)0xC1, 0x19, 0x3B, (byte)0xA6, 0x5D};
    static void markCompressed(byte[] id) { System.arraycopy(COMPRESSED_MARK, 0, id, 6, COMPRESSED_MARK.length); }
    private static final byte[] RATIO_MARK = {(byte)0xC1, 0x19, 0x3B, (byte)0xA7, 0x5E};

    static void markCompressed(byte[] id, int remainingBytes, int originalBytes) {
        System.arraycopy(RATIO_MARK, 0, id, 6, RATIO_MARK.length);
        id[11] = (byte)Math.max(1, Math.min(99, (100L * remainingBytes + originalBytes / 2) / originalBytes));
    }

    // -1 means original, 0 means compressed by an older version with no known ratio.
    static int compressionPercent(byte[] id) {
        if (hasMark(id, RATIO_MARK) && id[11] >= 1 && id[11] <= 99) return id[11];
        return hasMark(id, COMPRESSED_MARK) ? 0 : -1;
    }

    // The first two bytes cannot be a v4 UUID version in either endpoint's byte order.
    static void markCompressedSize(byte[] id, int originalBytes) {
        id[6] = (byte)0xC2;
        id[7] = (byte)0xA8;
        for (int i = 11; i >= 8; i--, originalBytes >>>= 8) id[i] = (byte)originalBytes;
    }

    static String compressionLabel(byte[] id, int sentBytes) {
        if (id[6] == (byte)0xC2 && id[7] == (byte)0xA8) {
            int original = 0;
            for (int i = 8; i <= 11; i++) original = (original << 8) | (id[i] & 255);
            if (sentBytes <= 0 || original <= sentBytes) return null;
            long hundredths = (10000L * sentBytes + original / 2) / original;
            if (hundredths == 0) return "已压缩至原<0.01%";
            if (hundredths == 10000) return "已压缩至原<100%";
            String percentage = String.format(java.util.Locale.ROOT, "%.2f", hundredths / 100.0);
            return "已压缩至原" + percentage.replaceFirst("\\.?0+$", "") + "%";
        }
        int legacy = compressionPercent(id);
        return legacy < 0 ? null : legacy == 0 ? "已压缩" : "已压缩至原" + legacy + "%";
    }

    private static boolean hasMark(byte[] id, byte[] mark) {
        for (int i = 0; i < mark.length; i++) if (id[6 + i] != mark[i]) return false;
        return true;
    }

    static final int TEXT_LIMIT = 1024 * 1024, IMAGE_LIMIT = 24 * 1024 * 1024;

    static final class ExpiredImage extends IOException { }

    static final class Frame {
        final int kind;
        final byte[] id;
        final byte[] data;
        final boolean saveImage;
        String source;

        Frame withSource(String value) { source = value; return this; }
        volatile boolean cancelled;
        long deadlineNanos;
        java.util.function.IntConsumer uploadProgress;
        volatile boolean awaitingUploadUi;
        private boolean uploadCommitted;

        synchronized boolean beginUploadCommit() {
            if (cancelled) return false;
            uploadCommitted = true;
            return true;
        }

        synchronized boolean cancelUpload() {
            cancelled = true;
            return uploadCommitted;
        }

        void reportUploadProgress(int percent) {
            if (uploadProgress != null && !cancelled) uploadProgress.accept(percent);
        }

        boolean expired() { return cancelled || (deadlineNanos != 0 && System.nanoTime() - deadlineNanos >= 0); }

        Frame(int kind, byte[] id, byte[] data) { this(kind, id, data, false); }

        Frame(int kind, byte[] id, byte[] data, boolean saveImage) {
            this.kind = kind;
            this.id = id;
            this.data = data;
            this.saveImage = saveImage;
        }
    }

    static byte[] deviceInventoryId(byte[] id) {
        byte[] result = id.clone();
        result[15] ^= 16;
        return result;
    }

    static String deviceName(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        if (clean.length() > 64) clean = clean.substring(0, 64).trim();
        return clean.isEmpty() ? "未命名设备" : clean;
    }

    static byte[] nameBytes(String name) { return deviceName(name).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    static String readName(Frame frame) { return deviceName(new String(frame.data, java.nio.charset.StandardCharsets.UTF_8)); }

    static void writeHistory(OutputStream out, Frame frame) throws IOException {
        write(out, frame);
        if (frame.source != null) write(out, new Frame(SOURCE, frame.id, nameBytes(frame.source)));
    }

    static Frame readHistory(InputStream in) throws IOException {
        Frame frame = read(in);
        if (frame == null) return null;
        Frame source = read(in);
        if (source != null && source.kind == SOURCE && java.util.Arrays.equals(frame.id, source.id))
            frame.source = readName(source);
        return frame;
    }

    static Frame read(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(source);
        int kind = in.read();
        if (kind == -1) return null;
        byte[] id = new byte[ID_SIZE];
        in.readFully(id);
        int count = in.readInt();
        validateLength(kind, count);
        byte[] data = new byte[count];
        in.readFully(data);
        return new Frame(kind, id, data);
    }

    static void write(OutputStream target, Frame frame) throws IOException {
        writeHeader(target, frame);
        target.write(frame.data);
        target.flush();
    }

    static void writeHeader(OutputStream target, Frame frame) throws IOException {
        validateLength(frame.kind, frame.data.length);
        if (frame.id.length != ID_SIZE) throw new IOException("Invalid message ID");
        int count = frame.data.length;
        byte[] header = new byte[1 + ID_SIZE + 4];
        header[0] = (byte)frame.kind;
        System.arraycopy(frame.id, 0, header, 1, ID_SIZE);
        for (int i = 0; i < 4; i++) header[1 + ID_SIZE + i] = (byte)(count >>> (24 - 8 * i));
        target.write(header);
    }

    private static void validateLength(int kind, int count) throws IOException {
        boolean valid;
        switch (kind) {
            case AUTH: valid = count == KEY_SIZE; break;
            case IMAGE_PART:
            case SAVED_IMAGE_PART: valid = count > 0 && count <= PART_LIMIT; break;
            case IMAGE_END: valid = count == 0 || count == 4; break;
            case IMAGE_PROGRESS: valid = count == 4; break;
            case DEVICE:
            case SOURCE: valid = count > 0 && count <= 256; break;
            case ROSTER: valid = count > 0 && count <= 16384; break;
            case REFRESH:
            case REFRESH_INVENTORY:
            case IMAGE_INVENTORY: valid = count >= 0 && count <= 50 * ID_SIZE && count % ID_SIZE == 0; break;
            case IMAGE_ABORT:
            case IMAGE_PREPARE:
            case IMAGE_READY:
            case DELETE:
            case ACK: valid = count == 0; break;
            case HISTORY_TEXT:
            case TEXT: valid = count > 0 && count <= TEXT_LIMIT; break;
            case SAVED_IMAGE:
            case HISTORY_IMAGE:
            case IMAGE: valid = count > 0 && count <= IMAGE_LIMIT; break;
            default: valid = false; break;
        }
        if (!valid) throw new IOException("Invalid message type or length");
    }
}

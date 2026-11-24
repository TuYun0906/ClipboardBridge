package clip;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class Wire {
    static final int AUTH = 1, TEXT = 2, IMAGE = 3, ACK = 4, HISTORY_TEXT = 5, HISTORY_IMAGE = 6, DELETE = 7;
    static final int IMAGE_INVENTORY = 8, SAVED_IMAGE = 9;
    static final int IMAGE_PART = 10, SAVED_IMAGE_PART = 11, IMAGE_END = 12, IMAGE_ABORT = 13;
    static final int IMAGE_PREPARE = 14, IMAGE_READY = 15;
    static final int PART_LIMIT = 32 * 1024;
    private static final byte[] PART_VERSION = {67, 67, 73, 49};
    private static final byte[] SYNC_VERSION = {67, 66, 73, 77, 71, 2, 117, 57};

    static void offerImageSync(byte[] id) {
        System.arraycopy(SYNC_VERSION, 0, id, 0, SYNC_VERSION.length);
        System.arraycopy(PART_VERSION, 0, id, 8, PART_VERSION.length);
    }

    static boolean offersImageParts(byte[] id) {
        for (int i = 0; i < PART_VERSION.length; i++) if (id[8 + i] != PART_VERSION[i]) return false;
        return true;
    }

    static boolean offersImageSync(byte[] id) {
        for (int i = 0; i < SYNC_VERSION.length; i++) if (id[i] != SYNC_VERSION[i]) return false;
        return true;
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

    static boolean isCompressed(byte[] id) { return compressionPercent(id) >= 0; }

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
        volatile boolean cancelled;
        long deadlineNanos;

        boolean expired() { return cancelled || (deadlineNanos != 0 && System.nanoTime() - deadlineNanos >= 0); }

        Frame(int kind, byte[] id, byte[] data) { this(kind, id, data, false); }

        Frame(int kind, byte[] id, byte[] data, boolean saveImage) {
            this.kind = kind;
            this.id = id;
            this.data = data;
            this.saveImage = saveImage;
        }
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
        validateLength(frame.kind, frame.data.length);
        if (frame.id.length != ID_SIZE) throw new IOException("Invalid message ID");
        int count = frame.data.length;
        byte[] header = new byte[1 + ID_SIZE + 4];
        header[0] = (byte)frame.kind;
        System.arraycopy(frame.id, 0, header, 1, ID_SIZE);
        for (int i = 0; i < 4; i++) header[1 + ID_SIZE + i] = (byte)(count >>> (24 - 8 * i));
        target.write(header);
        target.write(frame.data);
        target.flush();
    }

    private static void validateLength(int kind, int count) throws IOException {
        boolean valid;
        switch (kind) {
            case AUTH: valid = count == KEY_SIZE; break;
            case IMAGE_PART:
            case SAVED_IMAGE_PART: valid = count > 0 && count <= PART_LIMIT; break;
            case IMAGE_END: valid = count == 0 || count == 4; break;
            case IMAGE_ABORT:
            case IMAGE_PREPARE:
            case IMAGE_READY:
            case DELETE:
            case ACK: valid = count == 0; break;
            case HISTORY_TEXT:
            case TEXT: valid = count > 0 && count <= TEXT_LIMIT; break;
            case IMAGE_INVENTORY: valid = count >= 0 && count <= 50 * ID_SIZE && count % ID_SIZE == 0; break;
            case SAVED_IMAGE:
            case HISTORY_IMAGE:
            case IMAGE: valid = count > 0 && count <= IMAGE_LIMIT; break;
            default: valid = false; break;
        }
        if (!valid) throw new IOException("Invalid message type or length");
    }
}

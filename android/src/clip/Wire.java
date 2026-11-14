package clip;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class Wire {
    static final int AUTH = 1, TEXT = 2, IMAGE = 3, ACK = 4, HISTORY_TEXT = 5, HISTORY_IMAGE = 6, DELETE = 7;
    static final int ID_SIZE = 16, KEY_SIZE = 32;
    // Preserve provenance without changing the frame layout understood by older peers.
    private static final byte[] COMPRESSED_MARK = {(byte)0xC1, 0x19, 0x3B, (byte)0xA6, 0x5D};
    static void markCompressed(byte[] id) { System.arraycopy(COMPRESSED_MARK, 0, id, 6, COMPRESSED_MARK.length); }
    static boolean isCompressed(byte[] id) {
        for (int i = 0; i < COMPRESSED_MARK.length; i++) if (id[6 + i] != COMPRESSED_MARK[i]) return false;
        return true;
    }

    static final int TEXT_LIMIT = 1024 * 1024, IMAGE_LIMIT = 24 * 1024 * 1024;

    static final class Frame {
        final int kind;
        final byte[] id;
        final byte[] data;

        Frame(int kind, byte[] id, byte[] data) {
            this.kind = kind;
            this.id = id;
            this.data = data;
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
        DataOutputStream out = new DataOutputStream(target);
        out.writeByte(frame.kind);
        out.write(frame.id);
        out.writeInt(frame.data.length);
        out.write(frame.data);
        out.flush();
    }

    private static void validateLength(int kind, int count) throws IOException {
        boolean valid;
        switch (kind) {
            case AUTH: valid = count == KEY_SIZE; break;
            case DELETE:
            case ACK: valid = count == 0; break;
            case HISTORY_TEXT:
            case TEXT: valid = count > 0 && count <= TEXT_LIMIT; break;
            case HISTORY_IMAGE:
            case IMAGE: valid = count > 0 && count <= IMAGE_LIMIT; break;
            default: valid = false; break;
        }
        if (!valid) throw new IOException("Invalid message type or length");
    }
}

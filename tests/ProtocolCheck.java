package clip;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

public final class ProtocolCheck {
    public static void main(String[] args) throws IOException {
        byte[] id = new byte[16];
        for (int i = 0; i < id.length; i++) id[i] = (byte) i;
        byte[] text = "跨端剪贴板\n🙂".getBytes(StandardCharsets.UTF_8);
        byte[] image = {(byte) 137, 80, 78, 71, 0, (byte) 255};
        Path path = Paths.get(args[1]);
        if (args[0].equals("write")) {
            try (OutputStream out = Files.newOutputStream(path)) {
                Wire.write(out, new Wire.Frame(Wire.TEXT, id, text));
                Wire.write(out, new Wire.Frame(Wire.IMAGE, id, image));
                Wire.write(out, new Wire.Frame(Wire.ACK, id, new byte[0]));
            }
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            check(Wire.read(in), Wire.TEXT, id, text);
            check(Wire.read(in), Wire.IMAGE, id, image);
            check(Wire.read(in), Wire.ACK, id, new byte[0]);
            if (Wire.read(in) != null) throw new AssertionError("Expected EOF");
        }
        reject(new byte[] {Wire.TEXT});
        byte[] huge = new byte[21];
        huge[0] = Wire.IMAGE;
        huge[17] = 0x7f;
        reject(huge);
        huge[0] = (byte) 0xff;
        reject(huge);
        byte[] negative = new byte[21];
        Arrays.fill(negative, 17, 21, (byte) 0xff);
        negative[0] = Wire.IMAGE_INVENTORY;
        negative[20] = (byte) 0xf0;
        reject(negative);
    }

    private static void check(Wire.Frame frame, int kind, byte[] id, byte[] data) {
        if (frame.kind != kind || !Arrays.equals(frame.id, id) || !Arrays.equals(frame.data, data))
            throw new AssertionError("Frame mismatch");
    }

    private static void reject(byte[] bytes) {
        try { Wire.read(new ByteArrayInputStream(bytes)); }
        catch (IOException expected) { return; }
        throw new AssertionError("Malformed frame accepted");
    }
}

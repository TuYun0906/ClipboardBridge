package clip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;

public final class TlsCheck {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        byte[] pin = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(args[1])));
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 42);
        reject(port, new byte[32], key);
        reject(port, pin, new byte[32]);
        try (TlsLink link = TlsLink.connect("127.0.0.1", port, pin, key)) {
            byte[] id = new byte[16];
            Arrays.fill(id, (byte) 53);
            link.send(new Wire.Frame(Wire.TEXT, id, "手机→电脑".getBytes(StandardCharsets.UTF_8)));
            Wire.Frame ack = link.read();
            if (ack.kind != Wire.ACK || !Arrays.equals(ack.id, id))
                throw new AssertionError("Text confirmation failed");
            Wire.Frame image = link.read();
            if (image.kind != Wire.IMAGE || !Arrays.equals(image.data, new byte[] {(byte)137, 80, 78, 71}))
                throw new AssertionError("Image failed");
            link.send(new Wire.Frame(Wire.ACK, image.id, new byte[0]));
        }
    }

    private static void reject(int port, byte[] pin, byte[] key) throws Exception {
        try (TlsLink ignored = TlsLink.connect("127.0.0.1", port, pin, key)) {
            throw new AssertionError("Invalid peer accepted");
        } catch (IOException expected) { }
    }
}

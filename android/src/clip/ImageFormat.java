package clip;

final class ImageFormat {
    static String extension(byte[] data) {
        if (starts(data, 137, 80, 78, 71, 13, 10, 26, 10)) return "png";
        if (starts(data, 255, 216, 255)) return "jpg";
        if (starts(data, 71, 73, 70, 56, 55, 97) || starts(data, 71, 73, 70, 56, 57, 97))
            return "gif";
        if (starts(data, 66, 77)) return "bmp";
        return null;
    }

    static String mime(String extension) {
        if (extension == null) return null;
        switch (extension) {
            case "png": return "image/png";
            case "jpg": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            default: return null;
        }
    }

    private static boolean starts(byte[] data, int... bytes) {
        if (data.length < bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) if ((data[i] & 255) != bytes[i]) return false;
        return true;
    }
}

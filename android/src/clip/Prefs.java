package clip;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.content.pm.PackageManager;

final class Prefs {
    static final String SCREENSHOT_BUBBLE = "screenshot_bubble";
    static final int PORT = 47832;
    static final String HISTORY_PAGE_SIZE = "history_page_size";
    static final String COMPRESS = "compress_images";
    static final String AUTO = "auto", MEDIA = "media", INTERNET = "internet";
    static final String HOST = "host", HOST_KEY = "host_key";
    static final String LAN = "lan", WAN = "wan", PAIR = "pair";

    static SharedPreferences get(Context context) { return context.getSharedPreferences("clipboard", 0); }

    static String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33 ?
                android.Manifest.permission.READ_MEDIA_IMAGES : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static boolean canReadMedia(Context context) {
        return context.checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED;
    }
}

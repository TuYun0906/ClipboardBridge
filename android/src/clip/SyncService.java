package clip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

public final class SyncService extends Service {
    static final String HISTORY = "clip.bridge.HISTORY";
    static final String STATUS = "clip.bridge.SYNC_STATUS";
    private static final String CHANNEL = "clipboard_link";
    private static final int NOTICE = 11;
    static SyncService running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::applySettings;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (p, key) -> {
        main.removeCallbacks(refresh);
        main.post(refresh);
    };
    private SharedPreferences prefs;
    private HandlerThread io;
    private Handler worker;
    private AndroidClipboard clipboard;
    private ClipHistory history;
    private BackgroundClipboard background;
    private MediaWatcher media;
    private volatile SyncClient client;
    private String endpoint;
    private String token;
    private boolean internet;
    private String status = "等待连接";

    static void start(Context context) { context.startForegroundService(new Intent(context, SyncService.class)); }
    String status() { return status; }

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        prefs = Prefs.get(this);
        clipboard = new AndroidClipboard(this);
        history = new ClipHistory(this);
        io = new HandlerThread("clipboard-media");
        io.start();
        worker = new Handler(io.getLooper());
        media = new MediaWatcher(this, worker, this::sendOnWorker);
        background = new BackgroundClipboard(this, this::send,
                () -> sendBroadcast(new Intent(STATUS).setPackage(getPackageName())));
        prefs.registerOnSharedPreferenceChangeListener(changes);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "剪贴板同步",
                NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTICE, notification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        applySettings();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(changes);
        main.removeCallbacksAndMessages(null);
        if (client != null) client.close();
        background.close();
        media.update(false);
        io.quitSafely();
        running = null;
        super.onDestroy();
    }

    void send(Object snapshot) {
        if (snapshot != null && !worker.post(() -> sendOnWorker(snapshot)) &&
                snapshot instanceof android.os.ParcelFileDescriptor) {
            try { ((android.os.ParcelFileDescriptor)snapshot).close(); }
            catch (IOException ignored) { }
        }
    }

    void readFocusedClipboard(boolean focused) {
        if (background.ready()) return;
        Object snapshot = clipboard.snapshot(focused, true);
        boolean transmit = client != null && prefs.getBoolean(Prefs.AUTO, false);
        if (snapshot != null) worker.post(() -> process(snapshot, transmit));
    }

    boolean backgroundReady() { return background.ready(); }

    void loadHistory(java.util.function.Consumer<java.util.List<ClipHistory.Entry>> callback) {
        worker.post(() -> {
            java.util.List<ClipHistory.Entry> entries = history.entries();
            main.post(() -> callback.accept(entries));
        });
    }

    void deleteHistory(ClipHistory.Entry entry) {
        worker.post(() -> record(ClipHistory.tombstone(entry.key), true));
    }

    private void record(Wire.Frame frame, boolean local) {
        try {
            java.util.List<Wire.Frame> changes = history.apply(frame, local);
            SyncClient target = client;
            if (target != null)
                for (Wire.Frame change : changes)
                    if (local || change.kind == Wire.DELETE) target.publish(change);
            if (!changes.isEmpty()) sendBroadcast(new Intent(HISTORY).setPackage(getPackageName()));
        } catch (IOException error) {
            android.util.Log.w("ClipHistory", "History write failed", error);
        }
    }

    void copyHistory(ClipHistory.Entry entry) {
        worker.post(() -> {
            String result;
            try {
                clipboard.apply(ClipHistory.read(entry.file));
                result = "已复制";
            } catch (IOException | RuntimeException error) {
                result = "无法复制该记录";
            }
            String message = result;
            main.post(() -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show());
        });
    }

    AndroidClipboard clipboard() { return clipboard; }

    private void sendOnWorker(Object snapshot) {
        process(snapshot, true);
    }

    private void process(Object snapshot, boolean transmit) {
        try {
            Wire.Frame frame = clipboard.makeFrame(snapshot);
            record(frame, true);
            if (!transmit) return;
            main.post(() -> {
                if (client == null) {
                    android.widget.Toast.makeText(this, "请先填写电脑地址和配对码", android.widget.Toast.LENGTH_SHORT).show();
                } else client.submit(frame);
            });
        } catch (IOException error) {
            main.post(() -> android.widget.Toast.makeText(this, "无法发送此内容", android.widget.Toast.LENGTH_SHORT).show());
        }
    }

    private void applySettings() {
        boolean publicMode = prefs.getBoolean(Prefs.INTERNET, false);
        String host = prefs.getString(publicMode ? Prefs.WAN : Prefs.LAN, "").trim();
        String code = prefs.getString(Prefs.PAIR, "").trim();
        if (!host.equals(endpoint) || !code.equals(token) || publicMode != internet) {
            if (client != null) client.close();
            client = null;
            endpoint = host;
            token = code;
            internet = publicMode;
            byte[] pairing = parsePairing(code);
            if (host.isEmpty()) setStatus("请填写电脑地址");
            else if (pairing == null) setStatus("请填写有效的配对码");
            else {
                byte[] pin = Arrays.copyOfRange(pairing, 0, 32);
                byte[] key = Arrays.copyOfRange(pairing, 32, 64);
                final Object connection = new Object();
                currentConnection = connection;
                client = new SyncClient(host, Prefs.PORT, pin, key, frame -> {
                    if (frame.kind >= Wire.HISTORY_TEXT) {
                        Wire.Frame entry = frame;
                        if (frame.kind != Wire.DELETE)
                            entry = new Wire.Frame(frame.kind == Wire.HISTORY_TEXT ? Wire.TEXT : Wire.IMAGE, frame.id, frame.data);
                        record(entry, false);
                    } else {
                        clipboard.apply(frame);
                        if (frame.kind == Wire.TEXT) record(frame, false);
                    }
                }, connected ->
                        main.post(() -> {
                            if (currentConnection == connection && client != null)
                                setStatus(connected ? "已连接" : "正在重连…");
                        }));
                setStatus("正在连接…");
                client.replayWith(history::replay);
                client.start();
            }
        }
        media.update(prefs.getBoolean(Prefs.MEDIA, false) && Prefs.canReadMedia(this));
        background.enable(client != null && prefs.getBoolean(Prefs.AUTO, false));
    }

    private Object currentConnection;

    private static byte[] parsePairing(String value) {
        try {
            byte[] data = Base64.getUrlDecoder().decode(value);
            return data.length == 64 ? data : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private void setStatus(String value) {
        status = value;
        getSystemService(NotificationManager.class).notify(NOTICE, notification());
        sendBroadcast(new Intent(STATUS).setPackage(getPackageName()));
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        int icon = getResources().getIdentifier("clip_icon", "drawable", getPackageName());
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("剪贴板同步").setContentText(status).setSmallIcon(icon)
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build();
    }
}

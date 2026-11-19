package clip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
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
    private static final String CAPTURE_PERMISSION = "clip.bridge.CAPTURE_PERMISSION";
    private static final String CHANNEL = "clipboard_link";
    private static final String IMAGE_NOTICE_CHANNEL = "clipboard_image_sent";
    private static final int NOTICE = 11, IMAGE_NOTICE = 12;
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
    private volatile SyncHost host;
    private HostIdentity hostIdentity;
    private boolean startingHost, hosting, destroyed;
    private final android.net.ConnectivityManager.NetworkCallback networkChanges =
            new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onLinkPropertiesChanged(android.net.Network network,
                        android.net.LinkProperties properties) { notifyAddress(); }
                @Override public void onLost(android.net.Network network) { notifyAddress(); }
            };

    private void notifyAddress() {
        main.post(() -> { if (!destroyed && hosting) sendBroadcast(new Intent(STATUS).setPackage(getPackageName())); });
    }
    private String endpoint;
    private String token;
    private boolean internet;
    private static final String IMAGE_SENT = "发送图片成功";
    private String status = "等待连接";
    private long lastImageSentAt;
    private ScreenshotBubble screenshot;
    private Object queuedCapture;
    private Runnable cancelCapture;
    private android.widget.Toast imageToast;
    private final ImageSentBanner imageBanner = new ImageSentBanner(this);

    static void start(Context context) { context.startForegroundService(new Intent(context, SyncService.class)); }
    static void authorizeCapture(Context context, int result, Intent data) {
        context.startForegroundService(new Intent(context, SyncService.class).setAction(CAPTURE_PERMISSION)
                .putExtra("result", result).putExtra("consent", data));
    }

    void setCaptureForeground(boolean capture) {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (capture) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        startForeground(NOTICE, notification(), types);
    }

    void endCaptureForeground() {
        if (!destroyed) setCaptureForeground(false);
    }

    String status() {
        if (lastImageSentAt == 0) return status;
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        String skeleton = android.text.format.DateFormat.is24HourFormat(this) ? "Hms" : "hms";
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton);
        String time = new java.text.SimpleDateFormat(pattern, locale).format(new java.util.Date(lastImageSentAt));
        return status + " · " + IMAGE_SENT + " " + time;
    }
    String hostPairingCode() { return host == null || hostIdentity == null ? "" : hostIdentity.code; }

    String hostAddress() {
        android.net.ConnectivityManager manager = getSystemService(android.net.ConnectivityManager.class);
        for (android.net.Network network : manager.getAllNetworks()) {
            android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || !(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            android.net.LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            for (android.net.LinkAddress link : properties.getLinkAddresses()) {
                java.net.InetAddress address = link.getAddress();
                if (address instanceof java.net.Inet4Address && !address.isLinkLocalAddress() &&
                        !address.isLoopbackAddress()) return address.getHostAddress() + ":" + Prefs.PORT;
            }
        }
        return "请连接 Wi-Fi 或以太网";
    }

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        prefs = Prefs.get(this);
        screenshot = new ScreenshotBubble(this, main);
        clipboard = new AndroidClipboard(this);
        history = new ClipHistory(this);
        io = new HandlerThread("clipboard-media");
        io.start();
        worker = new Handler(io.getLooper());
        media = new MediaWatcher(this, worker, image -> process(image, true, true));
        background = new BackgroundClipboard(this, this::send,
                () -> sendBroadcast(new Intent(STATUS).setPackage(getPackageName())));
        prefs.registerOnSharedPreferenceChangeListener(changes);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "剪贴板同步",
                NotificationManager.IMPORTANCE_LOW));
        if (useImageNotification()) {
            NotificationChannel sent = new NotificationChannel(IMAGE_NOTICE_CHANNEL, "图片发送提示",
                    NotificationManager.IMPORTANCE_HIGH);
            sent.setSound(null, null);
            sent.enableVibration(false);
            sent.setShowBadge(false);
            notifications.createNotificationChannel(sent);
        }
        setCaptureForeground(false);
        getSystemService(android.net.ConnectivityManager.class).registerNetworkCallback(
                new android.net.NetworkRequest.Builder()
                        .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET).build(), networkChanges);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        applySettings();
        if (intent != null && CAPTURE_PERMISSION.equals(intent.getAction())) {
            Intent consent = intent.getParcelableExtra("consent");
            if (consent != null) screenshot.authorize(intent.getIntExtra("result", 0), consent);
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        main.post(screenshot::keepOnScreen);
    }

    @Override public void onDestroy() {
        destroyed = true;
        getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(networkChanges);
        prefs.unregisterOnSharedPreferenceChangeListener(changes);
        main.removeCallbacksAndMessages(null);
        dismissImagePopup();
        screenshot.close();
        if (client != null) client.close();
        if (host != null) host.close();
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
        boolean transmit = (client != null || host != null) && prefs.getBoolean(Prefs.AUTO, false);
        if (snapshot != null) worker.post(() -> process(snapshot, transmit));
    }

    boolean backgroundReady() { return background.ready(); }

    void loadHistory(java.util.function.Consumer<java.util.List<ClipHistory.Entry>> callback) {
        worker.post(() -> {
            java.util.List<ClipHistory.Entry> entries = history.entries();
            main.post(() -> callback.accept(entries));
        });
    }

    void loadHistoryImage(ClipHistory.Entry entry, int maxSide,
            java.util.function.Consumer<android.graphics.Bitmap> callback) {
        worker.post(() -> {
            android.graphics.Bitmap bitmap = null;
            try {
                byte[] bytes = ClipHistory.read(entry.file).data;
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                options.inJustDecodeBounds = false;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide)
                    options.inSampleSize *= 2;
                bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            } catch (IOException | RuntimeException ignored) { }
            android.graphics.Bitmap result = bitmap;
            main.post(() -> callback.accept(result));
        });
    }

    void deleteHistory(java.util.List<String> keys) {
        worker.post(() -> {
            java.util.List<Wire.Frame> changes = new java.util.ArrayList<>();
            for (String key : keys) {
                try {
                    changes.addAll(history.apply(ClipHistory.tombstone(key), true));
                } catch (IOException error) {
                    android.util.Log.w("ClipHistory", "History deletion failed", error);
                }
            }
            publishHistory(changes, true);
        });
    }

    void editHistory(ClipHistory.Entry entry, String text, java.util.function.Consumer<String> callback) {
        worker.post(() -> {
            String error;
            try {
                java.util.List<Wire.Frame> changes = history.edit(entry.key, clipboard.makeFrame(text));
                publishHistory(changes, true);
                error = changes.isEmpty() ? "这条记录已更新或删除" : null;
            } catch (IOException failure) {
                android.util.Log.w("ClipHistory", "History edit failed", failure);
                error = "无法保存这条记录";
            }
            String result = error;
            main.post(() -> callback.accept(result));
        });
    }

    private void record(Wire.Frame frame, boolean local) {
        try {
            publishHistory(history.apply(frame, local), local);
        } catch (IOException error) {
            android.util.Log.w("ClipHistory", "History write failed", error);
        }
    }

    private void publishHistory(java.util.List<Wire.Frame> changes, boolean local) {
        SyncClient target = client;
        if (target != null)
            for (Wire.Frame change : changes)
                if (local || change.kind == Wire.DELETE) target.publish(change);
        SyncHost listening = host;
        if (local && listening != null)
            for (Wire.Frame change : changes) listening.publish(change);
        historyChanged(changes);
    }

    private void historyChanged(java.util.List<Wire.Frame> changes) {
        if (!changes.isEmpty()) sendBroadcast(new Intent(HISTORY).setPackage(getPackageName()));
    }

    void copyHistory(ClipHistory.Entry entry, java.util.function.Consumer<Boolean> callback) {
        worker.post(() -> {
            boolean result;
            try {
                clipboard.apply(ClipHistory.read(entry.file));
                result = true;
            } catch (IOException | RuntimeException error) {
                result = false;
            }
            boolean copied = result;
            main.post(() -> callback.accept(copied));
        });
    }

    AndroidClipboard clipboard() { return clipboard; }

    private void sendOnWorker(Object snapshot) {
        process(snapshot, true);
    }

    private void process(Object snapshot, boolean transmit) { process(snapshot, transmit, false); }

    private void process(Object snapshot, boolean transmit, boolean confirmCapture) {
        try {
            Wire.Frame frame = clipboard.makeFrame(snapshot, transmit && prefs.getBoolean(Prefs.COMPRESS, false));
            if (!transmit) { record(frame, true); return; }
            Wire.Frame outgoing = withImageHistory(frame);
            main.post(() -> {
                Runnable delivered = confirmCapture ? () -> main.post(this::showImageSent) : null;
                if (host != null) host.submit(outgoing, delivered);
                else if (client != null) client.submit(outgoing, delivered);
                else android.widget.Toast.makeText(this, hosting ? "Android 主机尚未启动" : "请先填写主机地址和配对码",
                        android.widget.Toast.LENGTH_SHORT).show();
                worker.post(() -> record(outgoing, true));
            });
        } catch (IOException error) {
            main.post(() -> android.widget.Toast.makeText(this, "无法发送此内容", android.widget.Toast.LENGTH_SHORT).show());
        }
    }

    private Wire.Frame withImageHistory(Wire.Frame frame) {
        return frame.kind == Wire.IMAGE && prefs.getBoolean(ClipHistory.SAVE_IMAGES, false)
                ? new Wire.Frame(frame.kind, frame.id, frame.data, true) : frame;
    }

    boolean capturePeerConnected() {
        return "已连接".equals(status) || "主机已连接".equals(status);
    }

    void sendCapture(Bitmap image, Object request) {
        if (!worker.post(() -> {
            try {
                Wire.Frame frame = withImageHistory(clipboard.makeFrame(image, prefs.getBoolean(Prefs.COMPRESS, false)));
                main.post(() -> {
                    if (destroyed || !screenshot.isSending(request)) return;
                    if (!capturePeerConnected()) {
                        screenshot.sendFailed(request);
                        return;
                    }
                    Runnable delivered = () -> main.post(() -> {
                        if (destroyed || !screenshot.isSending(request)) return;
                        worker.post(() -> record(frame, true));
                        showImageSent(request);
                    });
                    SyncHost currentHost = host;
                    SyncClient currentClient = client;
                    if (currentHost != null) {
                        queuedCapture = request;
                        cancelCapture = () -> currentHost.cancelImage(frame);
                        currentHost.submit(frame, delivered);
                    } else if (currentClient != null) {
                        queuedCapture = request;
                        cancelCapture = () -> currentClient.cancelImage(frame);
                        currentClient.submit(frame, delivered);
                    } else screenshot.sendFailed(request);
                });
            } catch (IOException | RuntimeException error) {
                android.util.Log.w("ClipCapture", "Could not encode captured image", error);
                main.post(() -> screenshot.sendFailed(request));
            } finally { image.recycle(); }
        })) {
            image.recycle();
            screenshot.sendFailed(request);
        }
    }

    void finishCapture(Object request, boolean delivered) {
        if (queuedCapture != request) return;
        if (!delivered) cancelCapture.run();
        queuedCapture = null;
        cancelCapture = null;
    }

    private void applySettings() {
        screenshot.enable(prefs.getBoolean(Prefs.SCREENSHOT_BUBBLE, false));
        if (screenshot.isVisible()) dismissImagePopup();
        boolean hostMode = prefs.getBoolean(Prefs.HOST, false);
        if (hostMode != hosting) {
            hosting = hostMode;
            if (client != null) client.close();
            client = null;
            currentConnection = null;
            if (host != null) host.close();
            host = null;
            endpoint = null;
        }
        if (hosting) {
            if (host == null && !startingHost) startHost();
        } else applyClientSettings();
        media.update(prefs.getBoolean(Prefs.MEDIA, false) && Prefs.canReadMedia(this));
        updateClipboardWatcher();
    }

    private void updateClipboardWatcher() {
        background.enable((client != null || host != null) && prefs.getBoolean(Prefs.AUTO, false));
    }

    private void startHost() {
        startingHost = true;
        setStatus("正在启动 Android 主机…");
        worker.post(() -> {
            HostIdentity identity = null;
            try { identity = HostIdentity.open(prefs); }
            catch (java.security.GeneralSecurityException | IOException | RuntimeException failure) {
                android.util.Log.w("ClipHost", "Could not load host identity", failure);
            }
            final HostIdentity result = identity;
            main.post(() -> {
                startingHost = false;
                if (destroyed || !hosting) return;
                if (result == null) { setStatus("无法生成主机配对码"); return; }
                try {
                    Object connection = new Object();
                    SyncHost next = new SyncHost(result.tls, result.key, Prefs.PORT, (frame, relay) ->
                            receive(frame, relay), connected -> main.post(() -> {
                            if (currentConnection == connection && host != null)
                                setStatus(connected ? "主机已连接" : "主机已启动，等待连接");
                            }), history::replay);
                    next.imageInventory(history::imageInventory);
                    hostIdentity = result;
                    currentConnection = connection;
                    host = next;
                    next.start();
                    setStatus("主机已启动，等待连接");
                    updateClipboardWatcher();
                } catch (IOException | RuntimeException failure) {
                    setStatus("无法监听同步端口");
                    android.util.Log.w("ClipHost", "Could not start host", failure);
                }
            });
        });
    }

    private void receive(Wire.Frame frame, java.util.function.Consumer<Wire.Frame> relay) throws IOException {
        Wire.Frame entry = frame;
        if (frame.kind < Wire.HISTORY_TEXT) {
            clipboard.apply(frame);
            if (frame.kind != Wire.TEXT && !frame.saveImage) return;
        } else if (frame.kind != Wire.DELETE) {
            entry = new Wire.Frame(frame.kind == Wire.HISTORY_TEXT ? Wire.TEXT : Wire.IMAGE, frame.id, frame.data);
        }
        try {
            java.util.List<Wire.Frame> changes = history.apply(entry, false);
            for (Wire.Frame change : changes)
                if (relay != null && (!frame.saveImage || change.kind == Wire.DELETE)) relay.accept(change);
            historyChanged(changes);
        } catch (IOException error) {
            android.util.Log.w("ClipHistory", "History write failed", error);
        }
    }

    private void applyClientSettings() {
        boolean publicMode = prefs.getBoolean(Prefs.INTERNET, false);
        String address = prefs.getString(publicMode ? Prefs.WAN : Prefs.LAN, "").trim();
        String code = prefs.getString(Prefs.PAIR, "").trim();
        if (!address.equals(endpoint) || !code.equals(token) || publicMode != internet) {
            if (client != null) client.close();
            client = null;
            endpoint = address;
            token = code;
            internet = publicMode;
            byte[] pairing = parsePairing(code);
            Endpoint target = Endpoint.parse(address, Prefs.PORT);
            if (address.isEmpty()) setStatus("请填写主机地址");
            else if (target == null) setStatus("地址格式有误，请填写 IP 或域名，可加端口");
            else if (pairing == null) setStatus("请填写有效的配对码");
            else {
                byte[] pin = Arrays.copyOfRange(pairing, 0, 32);
                byte[] key = Arrays.copyOfRange(pairing, 32, 64);
                final Object connection = new Object();
                currentConnection = connection;
                client = new SyncClient(target.host, target.port, pin, key,
                        frame -> receive(frame, change -> {
                            SyncClient active = client;
                            if (active != null && currentConnection == connection && change.kind == Wire.DELETE)
                                active.publish(change);
                        }), connected ->
                        main.post(() -> {
                            if (currentConnection == connection && client != null)
                                setStatus(connected ? "已连接" : "正在重连…");
                        }));
                setStatus("正在连接…");
                client.replayWith(history::replay);
                client.imageInventory(history::imageInventory);
                client.start();
            }
        }

    }

    private volatile Object currentConnection;

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

    static boolean useImageNotification() {
        return Build.VERSION.SDK_INT < 30 || "huawei".equalsIgnoreCase(Build.MANUFACTURER) ||
                "huawei".equalsIgnoreCase(Build.BRAND);
    }

    private void showImageSent() { showImageSent(null); }

    private void showImageSent(Object request) {
        if (destroyed) return;
        if (screenshot.isVisible()) {
            screenshot.delivered(request);
            return;
        }
        if (request != null) return;
        lastImageSentAt = System.currentTimeMillis();
        sendBroadcast(new Intent(STATUS).setPackage(getPackageName()));
        NotificationManager notifications = getSystemService(NotificationManager.class);
        android.util.Log.d("ClipDelivery", "Capture acknowledged; updating sync status");
        try {
            notifications.notify(NOTICE, notification());
        } catch (SecurityException ignored) {
            // The result remains visible on the app's main screen.
        }
        if (useImageNotification() && imageBanner.show(IMAGE_SENT)) return;
        NotificationChannel channel = useImageNotification()
                ? notifications.getNotificationChannel(IMAGE_NOTICE_CHANNEL) : null;
        if (channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE &&
                notifications.areNotificationsEnabled()) {
            int icon = getResources().getIdentifier("clip_icon", "drawable", getPackageName());
            try {
                notifications.notify(IMAGE_NOTICE, new Notification.Builder(this, IMAGE_NOTICE_CHANNEL)
                        .setSmallIcon(icon).setContentTitle(IMAGE_SENT).setTimeoutAfter(4500)
                        .setShowWhen(false).setVisibility(Notification.VISIBILITY_PRIVATE).build());
                return;
            } catch (SecurityException ignored) {
                // Permission may have changed since the check above.
            }
        }
        if (imageToast != null) imageToast.cancel();
        imageToast = android.widget.Toast.makeText(this, IMAGE_SENT, android.widget.Toast.LENGTH_SHORT);
        imageToast.show();
    }

    private void dismissImagePopup() {
        imageBanner.dismiss();
        getSystemService(NotificationManager.class).cancel(IMAGE_NOTICE);
        if (imageToast != null) {
            imageToast.cancel();
            imageToast = null;
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        int icon = getResources().getIdentifier("clip_icon", "drawable", getPackageName());
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("剪贴板同步").setContentText(status()).setSmallIcon(icon)
                .setStyle(new Notification.BigTextStyle().bigText(status()))
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build();
    }
}

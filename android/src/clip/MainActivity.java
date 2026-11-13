package clip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int IMAGE = 31, MEDIA = 32, NOTICE = 33;
    private SharedPreferences prefs;
    private ClipboardManager clipboard;
    private TextView status, mediaHint, authorize;
    private EditText host, pair;
    private Switch show, media, internet, auto;
    private boolean updating, configuredMode;
    private final ClipboardManager.OnPrimaryClipChangedListener clipChanges = this::captureFocused;
    private final BroadcastReceiver statusChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshStatus();
            captureFocused();
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        prefs = Prefs.get(this);
        clipboard = getSystemService(ClipboardManager.class);
        configuredMode = prefs.getBoolean(Prefs.INTERNET, false);
        buildScreen();
        if (savedState == null) findViewById(android.R.id.content).post(() -> openAction(getIntent()));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openAction(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        clipboard.addPrimaryClipChangedListener(clipChanges);
        IntentFilter filter = new IntentFilter(SyncService.STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusChanges, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusChanges, filter);
    }

    @Override protected void onResume() {
        super.onResume();
        SyncService.start(this);
        refreshStatus();
        updating = true;
        show.setChecked(prefs.getBoolean(Prefs.SHOW, false) && Settings.canDrawOverlays(this));
        media.setChecked(prefs.getBoolean(Prefs.MEDIA, false) && Prefs.canReadMedia(this));
        updating = false;
    }

    @Override protected void onStop() {
        clipboard.removePrimaryClipChangedListener(clipChanges);
        unregisterReceiver(statusChanges);
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) captureFocused();
    }

    private void captureFocused() {
        SyncService service = SyncService.running;
        if (service != null) service.readFocusedClipboard(hasWindowFocus());
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Style.BACKGROUND);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().setStatusBarColor(Style.INK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
        LinearLayout root = Style.column(this);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setFocusableInTouchMode(true);
        scroll.addView(root);

        TextView title = Style.label(this, "剪贴板", 32, Style.INK, true);
        root.addView(title);
        status = Style.label(this, "等待连接", 14, Style.BLUE, true);
        add(root, status, 6, -2);

        LinearLayout sending = new LinearLayout(this);
        equal(sending, Style.action(this, "发送文字", true, this::sendText));
        equal(sending, Style.action(this, "选择图片", false, this::selectImage));
        equal(sending, Style.action(this, "历史记录", false, this::showHistory));
        add(root, sending, 20, dp(48));

        LinearLayout connection = card(root, "连接");
        internet = toggle(connection, "公网模式", configuredMode);
        host = field(configuredMode ? "电脑的 Tailscale 地址" : "电脑的局域网地址",
                prefs.getString(configuredMode ? Prefs.WAN : Prefs.LAN, ""));
        host.setSingleLine(true);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        add(connection, host, 7, dp(48));
        pair = field("Windows 配对码", prefs.getString(Prefs.PAIR, ""));
        pair.setSingleLine(true);
        pair.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(connection, pair, 9, dp(48));
        add(connection, Style.action(this, "保存连接", true, this::saveConnection), 12, dp(46));
        internet.setOnCheckedChangeListener((button, enabled) -> {
            prefs.edit().putString(configuredMode ? Prefs.WAN : Prefs.LAN, host.getText().toString().trim())
                    .putString(Prefs.PAIR, pair.getText().toString().trim())
                    .putBoolean(Prefs.INTERNET, enabled).apply();
            configuredMode = enabled;
            host.setHint(enabled ? "电脑的 Tailscale 地址" : "电脑的局域网地址");
            host.setText(prefs.getString(enabled ? Prefs.WAN : Prefs.LAN, ""));
        });

        LinearLayout options = card(root, "同步与浮窗");
        Switch saveImages = toggle(options, "历史记录保存图片",
                prefs.getBoolean(ClipHistory.SAVE_IMAGES, false));
        saveImages.setOnCheckedChangeListener((button, enabled) ->
                prefs.edit().putBoolean(ClipHistory.SAVE_IMAGES, enabled).apply());
        auto = toggle(options, "打开软件时自动发送剪贴板", prefs.getBoolean(Prefs.AUTO, false));
        authorize = Style.action(this, "授权 Shizuku 后台同步", false, this::requestBackgroundAccess);
        add(options, authorize, 4, dp(42));
        auto.setOnCheckedChangeListener((button, enabled) -> {
            prefs.edit().putBoolean(Prefs.AUTO, enabled).apply();
            if (enabled) captureFocused();
            refreshStatus();
        });
        media = toggle(options, "自动发送截图与拍照", false);
        media.setOnCheckedChangeListener((button, enabled) -> {
            if (updating) return;
            if (enabled && !Prefs.canReadMedia(this)) {
                if (Build.VERSION.SDK_INT >= 34) {
                    requestPermissions(new String[]{Prefs.mediaPermission(),
                            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, MEDIA);
                } else requestPermissions(new String[]{Prefs.mediaPermission()}, MEDIA);
            } else enableMedia(enabled);
        });
        mediaHint = Style.label(this, "✓ 已开启截图与拍照同步", 13, Style.BLUE, true);
        mediaHint.setVisibility(View.GONE);
        options.addView(mediaHint);
        show = toggle(options, "显示浮窗", false);
        show.setOnCheckedChangeListener((button, enabled) -> {
            if (updating) return;
            prefs.edit().putBoolean(Prefs.SHOW, enabled).apply();
            if (enabled && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            }
        });
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) scroll.post(() -> {
            WindowInsetsController controller = scroll.getWindowInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(appearance, appearance);
            }
        });
        root.requestFocus();
    }

    private void saveConnection() {
        getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(host.getWindowToken(), 0);
        host.clearFocus();
        pair.clearFocus();
        prefs.edit().putString(configuredMode ? Prefs.WAN : Prefs.LAN, host.getText().toString().trim())
                .putString(Prefs.PAIR, pair.getText().toString().trim()).apply();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean("askedNotice", false)) {
            prefs.edit().putBoolean("askedNotice", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTICE);
        }
    }

    private void enableMedia(boolean enabled) {
        prefs.edit().putBoolean(Prefs.MEDIA, enabled).apply();
        updating = true;
        media.setChecked(enabled);
        updating = false;
        if (!enabled) return;
        mediaHint.animate().cancel();
        mediaHint.setVisibility(View.VISIBLE);
        mediaHint.setAlpha(0);
        mediaHint.setTranslationY(dp(7));
        mediaHint.animate().alpha(1).translationY(0).setDuration(175).start();
        mediaHint.postDelayed(() -> mediaHint.animate().alpha(0).setDuration(180)
                .withEndAction(() -> mediaHint.setVisibility(View.GONE)).start(), 850);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == MEDIA) {
            boolean granted = Prefs.canReadMedia(this);
            enableMedia(granted);
            if (!granted) Toast.makeText(this, "自动发送需要完整的照片权限", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAction(Intent intent) {
        String mode = intent.getStringExtra("mode");
        if ("text".equals(mode)) sendText();
        if ("image".equals(mode)) selectImage();
        intent.removeExtra("mode");
    }

    private void sendText() {
        EditText entry = field("输入要发送的文字", "");
        entry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        entry.setMinLines(3);
        LinearLayout body = Style.column(this);
        body.setPadding(dp(20), dp(7), dp(20), 0);
        body.addView(entry, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("发送文字").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("发送", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = entry.getText().toString();
            if (text.isEmpty()) { entry.setError("请输入文字"); return; }
            submit(text);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void selectImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, IMAGE);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == IMAGE && result == RESULT_OK && data != null && data.getData() != null) submit(data.getData());
    }

    private void submit(Object content) {
        SyncService service = SyncService.running;
        if (service != null) service.send(content);
        else Toast.makeText(this, "同步服务正在启动", Toast.LENGTH_SHORT).show();
    }

    private void showHistory() {
        SyncService service = SyncService.running;
        if (service == null) return;
        LinearLayout body = Style.column(this);
        body.setPadding(dp(18), dp(10), dp(18), 0);
        EditText query = field("搜索文字或图片", "");
        query.setSingleLine(true);
        add(body, query, 0, dp(46));
        android.widget.ListView list = new android.widget.ListView(this);
        add(body, list, 8, Math.min(dp(370), getResources().getDisplayMetrics().heightPixels / 2));
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);
        java.util.List<ClipHistory.Entry> entries = new java.util.ArrayList<>();
        java.util.List<ClipHistory.Entry> matches = new java.util.ArrayList<>();
        Runnable refresh = () -> {
            String search = query.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            matches.clear();
            adapter.clear();
            for (ClipHistory.Entry entry : entries) {
                if (!entry.text.toLowerCase(java.util.Locale.ROOT).contains(search)) continue;
                matches.add(entry);
                String preview = entry.text.replace('\n', ' ').replace('\r', ' ');
                adapter.add(preview.length() > 150 ? preview.substring(0, 150) + "…" : preview);
            }
            if (matches.isEmpty()) adapter.add("暂无匹配记录");
        };
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(android.text.Editable value) { }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < matches.size()) service.copyHistory(matches.get(position));
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("剪贴板历史")
                .setView(body).setPositiveButton("关闭", null).create();
        dialog.show();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        service.loadHistory(result -> {
            if (!dialog.isShowing()) return;
            entries.addAll(result);
            refresh.run();
        });
    }

    private void refreshStatus() {
        SyncService service = SyncService.running;
        if (status != null && service != null) status.setText(service.status());
        if (auto == null || authorize == null) return;
        boolean ready = service != null && service.backgroundReady();
        auto.setText(ready ? "自动发送剪贴板" : "打开软件时自动发送剪贴板");
        authorize.setVisibility(auto.isChecked() && !ready ? View.VISIBLE : View.GONE);
    }

    private void requestBackgroundAccess() {
        if (BackgroundClipboard.granted()) {
            SyncService.start(this);
            Toast.makeText(this, "正在连接 Shizuku 服务", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Shizuku.pingBinder()) {
            try {
                if (Shizuku.shouldShowRequestPermissionRationale())
                    Toast.makeText(this, "请在 Shizuku 的已授权应用中允许剪贴板", Toast.LENGTH_LONG).show();
                else Shizuku.requestPermission(34);
                return;
            } catch (RuntimeException ignored) { }
        }
        Toast.makeText(this, "请先安装并启动 Shizuku，再点此授权", Toast.LENGTH_LONG).show();
        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launch != null) startActivity(launch);
    }

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout box = Style.column(this);
        box.setBackground(Style.surface(this, Style.WHITE, 22));
        box.setPadding(dp(16), dp(14), dp(16), dp(15));
        box.addView(Style.label(this, title, 19, Style.INK, true));
        add(root, box, 16, -2);
        return box;
    }

    private Switch toggle(LinearLayout root, String title, boolean checked) {
        Switch item = new Switch(this);
        item.setText(title);
        item.setTextSize(15);
        item.setTextColor(Style.INK);
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        item.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{Style.BLUE, 0xffe5e9f1}));
        item.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{0xffa8bbf7, 0xffb2bdcc}));
        item.setMinHeight(dp(48));
        item.setChecked(checked);
        item.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(item, new LinearLayout.LayoutParams(-1, -2));
        return item;
    }

    private EditText field(String hint, String value) {
        EditText entry = new EditText(this);
        entry.setHint(hint);
        entry.setText(value);
        entry.setTextColor(Style.INK);
        entry.setHintTextColor(Style.MUTED);
        entry.setTextSize(15);
        entry.setPadding(dp(13), dp(8), dp(13), dp(8));
        entry.setBackground(Style.surface(this, Style.BACKGROUND, 13));
        return entry;
    }

    private void equal(LinearLayout row, View child) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.leftMargin = dp(8);
        row.addView(child, lp);
    }

    private void add(LinearLayout parent, View child, int margin, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, height);
        lp.topMargin = dp(margin);
        parent.addView(child, lp);
    }

    private int dp(int value) { return Style.dp(this, value); }
}

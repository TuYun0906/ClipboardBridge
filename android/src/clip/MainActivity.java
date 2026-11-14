package clip;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Dialog;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int IMAGE = 31, MEDIA = 32, NOTICE = 33;
    private SharedPreferences prefs;
    private ClipboardManager clipboard;
    private TextView status, mediaHint, authorize;
    private EditText host, pair;
    private Switch media, internet, auto;
    private boolean updating, configuredMode;
    private final ClipboardManager.OnPrimaryClipChangedListener clipChanges = this::captureFocused;
    private final BroadcastReceiver statusChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (SyncService.HISTORY.equals(intent.getAction())) {
                if (refreshHistory != null) refreshHistory.run();
                return;
            }
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
        filter.addAction(SyncService.HISTORY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusChanges, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusChanges, filter);
    }

    @Override protected void onResume() {
        super.onResume();
        SyncService.start(this);
        refreshStatus();
        updating = true;
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
        TextView help = Style.label(this, "如何上手", 13, Style.BLUE, true);
        help.setGravity(Gravity.CENTER_VERTICAL);
        help.setFocusable(true);
        help.setOnClickListener(view -> showHelp());
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(-2, dp(44));
        root.addView(help, helpParams);

        LinearLayout sending = new LinearLayout(this);
        equal(sending, Style.action(this, "发送文字", true, this::sendText));
        equal(sending, Style.action(this, "选择图片", false, this::selectImage));
        equal(sending, Style.action(this, "历史记录", false, this::showHistory));
        add(root, sending, 20, dp(48));

        LinearLayout connection = card(root, "连接");
        internet = toggle(connection, "公网模式", configuredMode);
        host = field(connectionHint(configuredMode),
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
            host.setHint(connectionHint(enabled));
            host.setText(prefs.getString(enabled ? Prefs.WAN : Prefs.LAN, ""));
        });

        LinearLayout options = card(root, "同步与历史");
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
        String address = host.getText().toString().trim();
        if (!address.isEmpty() && Endpoint.parse(address, Prefs.PORT) == null) {
            host.setError("填写 IP 或域名，可加端口，例如 example.com:50001");
            return;
        }
        getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(host.getWindowToken(), 0);
        host.clearFocus();
        pair.clearFocus();
        prefs.edit().putString(configuredMode ? Prefs.WAN : Prefs.LAN, address)
                .putString(Prefs.PAIR, pair.getText().toString().trim()).apply();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean("askedNotice", false)) {
            prefs.edit().putBoolean("askedNotice", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTICE);
        }
    }

    private static String connectionHint(boolean publicMode) {
        return publicMode ? "Tailscale 地址或映射地址:端口" : "电脑的局域网地址";
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
        LinearLayout body = dialogBody("发送文字");
        add(body, entry, 0, -2);
        Dialog dialog = appDialog(body);
        dialogActions(body, dialog, "取消", "发送", () -> {
            String text = entry.getText().toString();
            if (text.isEmpty()) { entry.setError("请输入文字"); return; }
            submit(text);
            dialog.dismiss();
        });
        showDialog(dialog, body);
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

    private Runnable refreshHistory;

    private void showHelp() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Style.column(this);
        scroll.addView(body);
        helpSection(body, "局域网配对", "让电脑和安卓设备连接同一个局域网。在电脑「设置」查看地址并复制配对码，在安卓端填写两项并点「保存连接」。");
        helpSection(body, "Shizuku 后台同步", "安装 Shizuku。Android 10 用 USB 和电脑上的 ADB 启动。Android 11 及以上可在 Shizuku 里使用无线调试配对并启动。回到剪贴板，开启自动发送并授权 Shizuku。");
        helpLink(body, "查看 Shizuku 启动步骤", "https://shizuku.rikka.app/guide/setup/");
        helpSection(body, "Tailscale 公网连接", "让两端加入同一个 Tailscale 网络，并都开启公网模式。把 Windows 设置中显示的 Tailscale 地址和原配对码填到安卓端，再保存连接。");
        helpSection(body, "内网映射连接", "在电脑关闭 Tailscale 模式，用映射工具把公网 TCP 端口转发到电脑设置中显示的局域网地址和端口 47832。安卓开启公网模式，填入映射的公网 IP 或域名及端口，再用原配对码保存。");
        helpLink(body, "查看 Tailscale 安装说明", "https://tailscale.com/download");
        LinearLayout panel = dialogBody("如何上手");
        add(panel, scroll, 0, Math.min(dp(360), getResources().getDisplayMetrics().heightPixels / 2));
        Dialog dialog = appDialog(panel);
        dialogActions(panel, dialog, "关闭", null, null);
        showDialog(dialog, panel);
    }

    private void helpSection(LinearLayout body, String title, String instructions) {
        add(body, Style.label(this, title, 16, Style.INK, true), 12, -2);
        TextView text = Style.label(this, instructions, 14, Style.MUTED, false);
        text.setLineSpacing(dp(4), 1);
        add(body, text, 5, -2);
    }

    private void helpLink(LinearLayout body, String title, String url) {
        add(body, Style.action(this, title, false, () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (android.content.ActivityNotFoundException error) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
            }
        }), 8, dp(44));
    }

    private void showHistory() {
        SyncService service = SyncService.running;
        if (service == null) return;
        LinearLayout body = dialogBody("剪贴板历史");
        EditText query = field("搜索文字或图片", "");
        query.setBackground(Style.surface(this, Style.WHITE, 12));
        query.setSingleLine(true);
        LinearLayout search = new LinearLayout(this);
        search.addView(query, new LinearLayout.LayoutParams(0, -1, 1));
        add(body, search, 0, dp(46));
        int[] filter = {0};
        LinearLayout dates = new LinearLayout(this);
        add(body, dates, 8, dp(44));
        LocalDate[] bounds = new LocalDate[2];
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.ROOT);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = Style.column(this);
        scroll.addView(rows);
        add(body, scroll, 10, Math.min(dp(410), getResources().getDisplayMetrics().heightPixels / 2));
        TextView feedback = Style.label(this, "", 13, Style.MUTED, false);
        feedback.setGravity(Gravity.CENTER);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        add(body, feedback, 6, dp(28));
        LinearLayout selectionActions = new LinearLayout(this);
        add(body, selectionActions, 8, dp(44));
        java.util.List<ClipHistory.Entry> entries = new java.util.ArrayList<>();
        java.util.Set<String> selected = new java.util.HashSet<>();
        java.util.Map<String, Runnable> previews = new java.util.HashMap<>();
        java.util.Map<String, View> cards = new java.util.HashMap<>();
        int[] copyCount = {0};
        TextView cancel = Style.action(this, "取消多选", false, () -> { });
        TextView preview = Style.action(this, "预览", false, () -> {
            if (selected.size() != 1) return;
            Runnable open = previews.get(selected.iterator().next());
            if (open != null) open.run();
        });
        TextView delete = Style.action(this, "删除", true, () ->
                confirmHistoryDelete(service, new java.util.ArrayList<>(selected)));
        equal(selectionActions, cancel);
        equal(selectionActions, preview);
        equal(selectionActions, delete);
        Runnable updateSelection = () -> {
            selected.retainAll(cards.keySet());
            cards.forEach((key, card) -> card.setSelected(selected.contains(key)));
            selectionActions.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
            delete.setText("删除 (" + selected.size() + ")");
            preview.setVisibility(selected.size() == 1 ? View.VISIBLE : View.GONE);
        };
        Runnable refresh = () -> {
            String term = query.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            rows.removeAllViews();
            previews.clear();
            cards.clear();
            for (ClipHistory.Entry entry : entries) {
                if (filter[0] != 0 && entry.kind != filter[0]) continue;
                if (!entry.text.toLowerCase(java.util.Locale.ROOT).contains(term)) continue;
                ZonedDateTime copied = entry.copiedAt == 0 ? null : Instant.ofEpochMilli(entry.copiedAt).atZone(zone);
                LocalDate date = copied == null ? null : copied.toLocalDate();
                if ((bounds[0] != null && (date == null || date.isBefore(bounds[0]))) ||
                        (bounds[1] != null && (date == null || date.isAfter(bounds[1])))) continue;
                LinearLayout card = Style.column(this);
                cards.put(entry.key, card);
                card.setFocusable(true);
                android.graphics.drawable.StateListDrawable surface = new android.graphics.drawable.StateListDrawable();
                surface.addState(new int[]{android.R.attr.state_selected}, Style.surface(this, Style.TINT, 14));
                surface.addState(new int[0], Style.surface(this, Style.WHITE, 14));
                card.setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x283368ea), surface, null));
                card.setPadding(dp(12), dp(10), dp(12), dp(8));
                if (entry.kind == Wire.IMAGE) {
                    card.setContentDescription("图片记录");
                    previews.put(entry.key, addHistoryImage(card, service, entry));
                }
                else {
                    TextView label = Style.label(this, entry.text, 15, Style.INK, false);
                    label.setMaxLines(3);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    add(card, label, 0, -2);
                    previews.put(entry.key, () -> previewHistoryText(service, entry));
                }
                add(card, Style.label(this, copied == null ? "复制时间未知" : timestamp.format(copied),
                        12, Style.MUTED, false), 5, -2);
                card.setOnClickListener(view -> {
                    if (!selected.isEmpty()) {
                        if (!selected.remove(entry.key)) selected.add(entry.key);
                        updateSelection.run();
                        return;
                    }
                    service.copyHistory(entry, copiedSuccessfully -> {
                        if (!feedback.isAttachedToWindow()) return;
                        if (!copiedSuccessfully) {
                            Toast.makeText(this, "无法复制该记录", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int count = ++copyCount[0];
                        feedback.setText(count == 1 ? "已复制" : "已复制x" + count);
                        feedback.setVisibility(View.VISIBLE);
                    });
                });
                card.setOnLongClickListener(view -> {
                    selected.add(entry.key);
                    updateSelection.run();
                    return true;
                });
                add(rows, card, rows.getChildCount() == 0 ? 0 : 8, -2);
            }
            if (rows.getChildCount() == 0) {
                TextView empty = Style.label(this, "暂无匹配记录", 14, Style.MUTED, false);
                empty.setGravity(Gravity.CENTER);
                add(rows, empty, 12, dp(68));
            }
            updateSelection.run();
        };
        Runnable resetSelection = () -> {
            selected.clear();
            refresh.run();
        };
        TextView type = Style.action(this, "全部", false, () -> { });
        type.setContentDescription("历史类型：全部");
        LinearLayout.LayoutParams typeSize = new LinearLayout.LayoutParams(dp(72), -1);
        typeSize.leftMargin = dp(8);
        search.addView(type, typeSize);
        type.setOnClickListener(view -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(this, type);
            menu.getMenu().add(0, 0, 0, "全部");
            menu.getMenu().add(0, Wire.TEXT, 1, "文字");
            menu.getMenu().add(0, Wire.IMAGE, 2, "图片");
            menu.setOnMenuItemClickListener(item -> {
                filter[0] = item.getItemId();
                type.setText(item.getTitle());
                type.setContentDescription("历史类型：" + item.getTitle());
                resetSelection.run();
                return true;
            });
            menu.show();
        });
        cancel.setOnClickListener(view -> {
            selected.clear();
            updateSelection.run();
        });
        TextView from = historyDateButton("开始日期", 0, bounds, zone, resetSelection);
        TextView until = historyDateButton("结束日期", 1, bounds, zone, resetSelection);
        equal(dates, from);
        equal(dates, until);
        TextView reset = Style.action(this, "清除", false, () -> {
            bounds[0] = bounds[1] = null;
            from.setText("开始日期");
            until.setText("结束日期");
            resetSelection.run();
        });
        reset.setContentDescription("清除日期筛选");
        reset.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams resetSize = new LinearLayout.LayoutParams(dp(52), -1);
        resetSize.leftMargin = dp(8);
        dates.addView(reset, resetSize);
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { resetSelection.run(); }
            @Override public void afterTextChanged(android.text.Editable value) { }
        });
        Dialog[] historyDialog = new Dialog[1];
        Dialog dialog = appDialog(body, () -> {
            if (selected.isEmpty()) historyDialog[0].dismiss();
            else {
                selected.clear();
                updateSelection.run();
            }
        });
        historyDialog[0] = dialog;
        dialogActions(body, dialog, "关闭", null, null);
        showDialog(dialog, body);
        refreshHistory = () -> service.loadHistory(result -> {
            if (!dialog.isShowing()) return;
            entries.clear();
            entries.addAll(result);
            refresh.run();
        });
        dialog.setOnDismissListener(ignored -> refreshHistory = null);
        refreshHistory.run();
    }

    private TextView historyDateButton(String title, int index, LocalDate[] bounds, ZoneId zone, Runnable refresh) {
        TextView button = Style.action(this, title, false, () -> {});
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setSingleLine(true);
        button.setOnClickListener(view -> {
            LocalDate other = bounds[1 - index];
            LocalDate selected = bounds[index];
            if (selected == null) selected = other == null ? LocalDate.now(zone) : other;
            DatePickerDialog picker = new DatePickerDialog(this, (widget, year, month, day) -> {
                LocalDate date = LocalDate.of(year, month + 1, day);
                bounds[index] = date;
                button.setText(date.toString());
                refresh.run();
            }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth());
            picker.setTitle(title);
            if (other != null) {
                long limit = other.atStartOfDay(zone).toInstant().toEpochMilli();
                if (index == 0) picker.getDatePicker().setMaxDate(limit);
                else picker.getDatePicker().setMinDate(limit);
            }
            picker.show();
        });
        return button;
    }

    private LinearLayout dialogBody(String title) {
        LinearLayout body = Style.column(this);
        body.setPadding(dp(16), dp(15), dp(16), dp(14));
        body.setBackground(Style.surface(this, Style.BACKGROUND, 22));
        body.setFocusableInTouchMode(true);
        TextView heading = Style.label(this, title, 20, Style.INK, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        body.addView(heading, params);
        return body;
    }

    private Dialog appDialog(LinearLayout body) {
        return appDialog(body, null);
    }

    private Dialog appDialog(LinearLayout body, Runnable close) {
        Dialog dialog = close == null ? new Dialog(this) : new Dialog(this) {
            @Override public void onBackPressed() { close.run(); }
        };
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(body);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        return dialog;
    }

    private void showDialog(Dialog dialog, LinearLayout body) {
        dialog.show();
        dialog.getWindow().setLayout(Math.min(dp(520),
                getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
        body.requestFocus();
    }

    private void dialogActions(LinearLayout body, Dialog dialog, String cancel, String confirm, Runnable action) {
        LinearLayout buttons = new LinearLayout(this);
        equal(buttons, Style.action(this, cancel, false, dialog::dismiss));
        if (confirm != null) equal(buttons, Style.action(this, confirm, true, action));
        add(body, buttons, 12, dp(44));
    }

    private void confirmHistoryDelete(SyncService service, java.util.List<String> keys) {
        LinearLayout body = dialogBody("删除所选 " + keys.size() + " 条记录？");
        add(body, Style.label(this, "删除会同步到其他设备。", 14, Style.MUTED, false), 0, -2);
        Dialog dialog = appDialog(body);
        dialogActions(body, dialog, "取消", "删除", () -> {
            service.deleteHistory(keys);
            dialog.dismiss();
        });
        showDialog(dialog, body);
    }

    private void previewHistoryText(SyncService service, ClipHistory.Entry entry) {
        LinearLayout body = dialogBody("文字预览");
        EditText input = field("输入文字", entry.text);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackground(Style.surface(this, Style.WHITE, 12));
        add(body, input, 0, dp(160));
        LinearLayout actions = new LinearLayout(this);
        add(body, actions, 12, dp(44));

        class Editor {
            Dialog dialog;
            View save;
            boolean saving;

            void save() {
                if (saving) return;
                String text = input.getText().toString();
                if (text.isEmpty()) { input.setError("请输入文字"); return; }
                if (text.equals(entry.text)) { dialog.dismiss(); return; }
                if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Wire.TEXT_LIMIT) {
                    input.setError("文字超过大小限制");
                    return;
                }
                saving = true;
                input.setEnabled(false);
                save.setEnabled(false);
                service.editHistory(entry, text, error -> {
                    if (!dialog.isShowing()) return;
                    if (error == null) { dialog.dismiss(); return; }
                    saving = false;
                    input.setEnabled(true);
                    save.setEnabled(true);
                    input.setError(error);
                });
            }

            void close() {
                if (saving) return;
                if (entry.text.contentEquals(input.getText())) { dialog.dismiss(); return; }
                LinearLayout prompt = dialogBody("保存更改");
                add(prompt, Style.label(MainActivity.this, "你编辑了这条信息，是否保存更改？",
                        14, Style.MUTED, false), 0, -2);
                Dialog confirmation = appDialog(prompt);
                LinearLayout buttons = new LinearLayout(MainActivity.this);
                equal(buttons, Style.action(MainActivity.this, "否", false, () -> {
                    confirmation.dismiss();
                    dialog.dismiss();
                }));
                equal(buttons, Style.action(MainActivity.this, "是", true, () -> {
                    confirmation.dismiss();
                    save();
                }));
                add(prompt, buttons, 12, dp(44));
                showDialog(confirmation, prompt);
            }
        }
        Editor editor = new Editor();
        editor.dialog = appDialog(body, editor::close);
        editor.dialog.setCanceledOnTouchOutside(false);
        equal(actions, Style.action(this, "关闭", false, editor::close));
        editor.save = Style.action(this, "保存", true, editor::save);
        equal(actions, editor.save);
        editor.save.setVisibility(View.GONE);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                editor.save.setVisibility(entry.text.contentEquals(s) ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(android.text.Editable value) { }
        });
        showDialog(editor.dialog, body);
    }

    private Runnable addHistoryImage(LinearLayout card, SyncService service, ClipHistory.Entry entry) {
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        image.setBackground(Style.surface(this, Style.TINT, 10));
        image.setClipToOutline(true);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(112), dp(72));
        card.addView(image, size);
        android.graphics.Bitmap[] thumbnail = new android.graphics.Bitmap[1];
        Runnable togglePreview = () -> {
            boolean expand = size.width != -1;
            size.width = expand ? -1 : dp(112);
            size.height = expand ? dp(240) : dp(72);
            image.setLayoutParams(size);
            if (!expand) image.setImageBitmap(thumbnail[0]);
            if (expand) service.loadHistoryImage(entry, 1440, bitmap -> {
                if (image.isAttachedToWindow() && size.width == -1 && bitmap != null) image.setImageBitmap(bitmap);
            });
        };
        service.loadHistoryImage(entry, 176, bitmap -> {
            thumbnail[0] = bitmap;
            if (image.isAttachedToWindow() && size.width != -1) {
                image.setImageBitmap(bitmap);
                if (bitmap == null) image.setContentDescription("图片无法读取");
            }
        });
        return togglePreview;
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

using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using Forms = System.Windows.Forms;

internal sealed class Program : IDisposable
{
    private const string StartupKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string StartupName = "ClipBridge";
    private const int Port = 47832;
    private readonly Application app;
    private readonly string settingsPath;
    private readonly Identity identity;
    private readonly MainWindow window;
    private readonly Forms.NotifyIcon tray;
    private readonly Icon trayIcon;
    private readonly WindowsClipboard clipboard;
    private readonly TaskScheduler encoding = new ConcurrentExclusiveSchedulerPair().ExclusiveScheduler;
    private volatile ISyncConnection connection;
    private IPAddress address;
    private bool disposed;
    private bool exiting;
    internal bool Exiting { get { return exiting; } }
    internal ClipHistory History { get; private set; }
    internal event Action HistoryChanged;
    internal System.Windows.Media.ImageSource WindowIcon
    {
        get { return Imaging.CreateBitmapSourceFromHIcon(trayIcon.Handle, Int32Rect.Empty,
            System.Windows.Media.Imaging.BitmapSizeOptions.FromEmptyOptions()); }
    }

    internal bool Automatic { get; private set; }
    internal bool PublicMode { get; private set; }
    internal string RemoteAddress { get; private set; }
    internal string RemoteCode { get; private set; }
    internal bool ClientMode { get; private set; }
    internal bool ShowWindow { get; private set; }
    internal string AddressText { get { return address == null ? "暂无可用地址" : address.ToString(); } }
    internal string Status { get; private set; }

    [STAThread]
    private static void Main(string[] args)
    {
        bool first;
        using (var mutex = new Mutex(true, @"Local\ClipBridge.Clipboard", out first))
        {
            if (!first)
            {
                MessageBox.Show("跨设备剪贴板已在运行，可从系统托盘打开。", "跨设备剪贴板");
                return;
            }
            var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
            try
            {
                string directory = Path.Combine(Environment.GetFolderPath(
                    Environment.SpecialFolder.LocalApplicationData), "ClipBridge");
                bool startHidden = args.Contains("--startup");
                using (var program = new Program(app, directory, startHidden))
                {
                    if (!startHidden) InheritStartup();
                    app.Run();
                }
            }
            catch (Exception error)
            {
                MessageBox.Show(error.Message, "剪贴板无法启动", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
    }

    internal Program(Application app, string directory, bool startHidden = false)
    {
        this.app = app;
        settingsPath = Path.Combine(directory, "settings.txt");
        identity = Identity.Open(Path.Combine(directory, "identity.bin"));
        string options = File.Exists(settingsPath) ? File.ReadAllText(settingsPath) : "001";
        string[] lines = options.Replace("\r", "").Split('\n');
        ClientMode = lines.Length >= 4 && lines[1] == "client";
        RemoteAddress = lines.Length >= 4 ? lines[2] : "";
        string encoded = lines.Length >= 4 ? lines[3] : "";
        RemoteCode = encoded == "" ? "" : ReadRemoteCode(encoded);
        Automatic = options.Length > 0 && options[0] == '1';
        PublicMode = options.Length > 1 && options[1] == '1';
        ShowWindow = !startHidden;
        History = new ClipHistory(Path.Combine(directory, "history"));
        History.SaveImages = options.Length > 3 && options[3] == '1';
        using (var stream = typeof(Program).Assembly.GetManifestResourceStream("Clipboard.ico"))
        using (var icon = new Icon(stream, 32, 32)) trayIcon = (Icon)icon.Clone();

        window = new MainWindow(this);
        clipboard = new WindowsClipboard(content => QueueContent(content, Automatic));
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("显示窗口", null, delegate { SetVisible(true); });
        menu.Items.Add("退出", null, delegate { exiting = true; app.Shutdown(); });
        tray = new Forms.NotifyIcon
        {
            Text = "跨设备剪贴板", Icon = trayIcon, ContextMenuStrip = menu, Visible = true
        };
        tray.MouseClick += (sender, args) => { if (args.Button == Forms.MouseButtons.Left) SetVisible(true); };
        NetworkChange.NetworkAddressChanged += NetworkChanged;
        Listen(true);
        if (ShowWindow) window.Show();
    }

    private static string ReadRemoteCode(string value)
    {
        try
        {
            var bytes = System.Security.Cryptography.ProtectedData.Unprotect(Convert.FromBase64String(value),
                null, System.Security.Cryptography.DataProtectionScope.CurrentUser);
            return System.Text.Encoding.UTF8.GetString(bytes);
        }
        catch (FormatException) { return ""; }
        catch (System.Security.Cryptography.CryptographicException) { return ""; }
    }

    internal void SaveSettings(bool automatic, bool publicMode, bool visible, bool saveImages = false,
        bool clientMode = false, string remoteAddress = "", string remoteCode = "")
    {
        bool changed = PublicMode != publicMode || ClientMode != clientMode ||
            RemoteAddress != remoteAddress || RemoteCode != remoteCode;
        ClientMode = clientMode;
        RemoteAddress = remoteAddress;
        RemoteCode = remoteCode;
        Automatic = automatic;
        PublicMode = publicMode;
        History.SaveImages = saveImages;
        SetVisible(visible);
        if (changed) Listen(true);
        try
        {
            string encoded = RemoteCode == "" ? "" : Convert.ToBase64String(
                System.Security.Cryptography.ProtectedData.Protect(System.Text.Encoding.UTF8.GetBytes(RemoteCode),
                    null, System.Security.Cryptography.DataProtectionScope.CurrentUser));
            File.WriteAllText(settingsPath, (Automatic ? "1" : "0") + (PublicMode ? "1" : "0") +
                "1" + (History.SaveImages ? "1" : "0") + "\n" + (ClientMode ? "client" : "host") +
                "\n" + RemoteAddress + "\n" + encoded);
        }
        catch (IOException) { SetStatus("无法保存设置"); }
        catch (UnauthorizedAccessException) { SetStatus("无法保存设置"); }
        catch (System.Security.Cryptography.CryptographicException) { SetStatus("无法保存连接设置"); }
    }

    internal void SetVisible(bool visible)
    {
        ShowWindow = visible;
        if (!visible) { window.Hide(); return; }
        if (!window.IsVisible) window.Show();
        if (window.WindowState == WindowState.Minimized) window.WindowState = WindowState.Normal;
        window.Activate();
    }

    internal static bool StartWithWindows
    {
        get
        {
            using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(StartupKey))
                return key != null && !String.IsNullOrWhiteSpace(key.GetValue(StartupName) as string);
        }
    }

    private static string StartupCommand
    {
        get { return "\"" + System.Reflection.Assembly.GetExecutingAssembly().Location + "\" --startup"; }
    }

    private static void InheritStartup()
    {
        try
        {
            using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(StartupKey, true))
            {
                if (key == null) return;
                string previous = key.GetValue(StartupName) as string;
                if (!String.IsNullOrWhiteSpace(previous) &&
                    !String.Equals(previous, StartupCommand, StringComparison.OrdinalIgnoreCase))
                    key.SetValue(StartupName, StartupCommand, Microsoft.Win32.RegistryValueKind.String);
            }
        }
        catch (Exception error)
        {
            if (!(error is IOException) && !(error is UnauthorizedAccessException) &&
                !(error is System.Security.SecurityException)) throw;
            MessageBox.Show(error.Message, "无法继承开机自启设置", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    internal static void SaveStartup(bool enabled)
    {
        using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(StartupKey))
        {
            if (enabled) key.SetValue(StartupName, StartupCommand, Microsoft.Win32.RegistryValueKind.String);
            else key.DeleteValue(StartupName, false);
        }
    }

    internal void DeleteHistory(string key)
    {
        Task.Factory.StartNew(() => Record(ClipHistory.Tombstone(key), true),
            CancellationToken.None, TaskCreationOptions.None, encoding);
    }

    internal void CopyHistory(string path)
    {
        try { Clipboard.SetDataObject(WindowsClipboard.ToClipboardData(ClipHistory.Read(path)), false); }
        catch (Exception error) { SetStatus("复制失败：" + error.Message); }
    }

    internal Task<bool> EditHistory(string key, string text)
    {
        return Task.Factory.StartNew(() =>
        {
            Frame[] changes = History.Edit(key, WindowsClipboard.ToFrame(text));
            ISyncConnection target = connection;
            PublishHistory(changes, target == null ? (Action<Frame>)null : target.Publish);
            return changes.Length != 0;
        }, CancellationToken.None, TaskCreationOptions.None, encoding);
    }

    private void Record(Frame frame, bool local, Action<Frame> relay = null)
    {
        try
        {
            ISyncConnection target = connection;
            PublishHistory(History.Apply(frame, local), local && target != null ? target.Publish : relay);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private void PublishHistory(Frame[] changes, Action<Frame> publish)
    {
        if (publish != null)
            foreach (Frame change in changes) publish(change);
        if (changes.Length > 0) app.Dispatcher.BeginInvoke(new Action(() =>
        {
            if (HistoryChanged != null) HistoryChanged();
        }));
    }

    internal void CopyPairingCode()
    {
        try
        {
            Clipboard.SetDataObject(WindowsClipboard.InternalText(identity.PairingCode), false);
        }
        catch (ExternalException) { MessageBox.Show("剪贴板正忙，请稍后重试。", "剪贴板"); }
    }

    internal void Send(object content)
    {
        QueueContent(content, true);
    }

    private void QueueContent(object content, bool send)
    {
        ISyncConnection target = send ? connection : null;
        Task.Factory.StartNew(delegate
        {
            try
            {
                Frame frame = WindowsClipboard.ToFrame(content);
                if (target != null) target.Submit(frame);
                Record(frame, true);
            }
            catch (Exception error)
            {
                app.Dispatcher.BeginInvoke(new Action(() => SetStatus("发送失败：" + error.Message)));
            }
        }, CancellationToken.None, TaskCreationOptions.None, encoding);
    }

    private void Receive(Frame frame, Action<Frame> relay)
    {
        if (frame.Kind >= Wire.HistoryText)
        {
            if (frame.Kind == Wire.HistoryText || frame.Kind == Wire.HistoryImage)
                frame = new Frame(frame.Kind == Wire.HistoryText ? Wire.Text : Wire.Image, frame.Id, frame.Data);
            Record(frame, false, relay);
            return;
        }
        DataObject value;
        try { value = WindowsClipboard.ToClipboardData(frame); }
        catch (Exception error) { throw new IOException("Invalid clipboard payload", error); }
        for (int attempt = 0; attempt < 6; attempt++)
        {
            try
            {
                app.Dispatcher.Invoke(new Action(() => Clipboard.SetDataObject(value, false)));
                if (frame.Kind == Wire.Text) Record(frame, false, relay);
                return;
            }
            catch (ExternalException) { Thread.Sleep(45); }
            catch (InvalidOperationException error) { throw new IOException("Clipboard unavailable", error); }
            catch (TaskCanceledException error) { throw new IOException("Clipboard unavailable", error); }
        }
        throw new IOException("Clipboard is busy");
    }

    private void Listen(bool force)
    {
        IPAddress selected = SelectAddress(PublicMode);
        if (!force && (ClientMode || Equals(selected, address)) && connection != null) return;
        if (connection != null) connection.Dispose();
        connection = null;
        if (ClientMode)
        {
            Uri endpoint;
            byte[] credentials;
            if (!SyncClient.TryEndpoint(RemoteAddress, out endpoint) || !SyncClient.TryPairing(RemoteCode, out credentials))
            {
                SetStatus("请在设置中填写 Android 地址和配对码");
                return;
            }
            SyncClient client = null;
            client = new SyncClient(endpoint, credentials, Receive, connected =>
                app.Dispatcher.BeginInvoke(new Action(delegate
                {
                    if (connection == client) SetStatus(connected ? "已连接到 Android 主机" : "正在连接 Android 主机…");
                })), History.Replay);
            connection = client;
            SetStatus("正在连接 Android 主机…");
            client.Start();
            return;
        }
        address = selected;
        if (address == null && PublicMode)
        {
            SetStatus("等待 Tailscale 网络");
            return;
        }
        SyncServer next = null;
        try
        {
            next = new SyncServer(address ?? IPAddress.Loopback, Port, identity, Receive, connected =>
                app.Dispatcher.BeginInvoke(new Action(delegate
                {
                    if (connection == next) SetStatus(connected ? "已连接" : "等待手机连接");
                })), History.Replay, includeLoopback: !PublicMode);
            connection = next;
            next.Start();
            SetStatus("等待手机连接");
        }
        catch (SocketException)
        {
            if (next != null) next.Dispose();
            connection = null;
            SetStatus("无法监听同步端口");
        }
    }

    private static IPAddress SelectAddress(bool publicMode)
    {
        var candidates = new List<Tuple<IPAddress, int>>();
        foreach (NetworkInterface adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up ||
                adapter.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            bool tailscale = (adapter.Name + " " + adapter.Description)
                .IndexOf("tailscale", StringComparison.OrdinalIgnoreCase) >= 0;
            if (tailscale != publicMode) continue;
            IPInterfaceProperties properties = adapter.GetIPProperties();
            int rank = properties.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork &&
                       !Equals(g.Address, IPAddress.Any)) ? 3 : 0;
            if (adapter.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ||
                adapter.NetworkInterfaceType == NetworkInterfaceType.Ethernet) rank++;
            foreach (UnicastIPAddressInformation item in properties.UnicastAddresses)
            {
                IPAddress ip = item.Address;
                if (ip.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(ip)) continue;
                byte[] bytes = ip.GetAddressBytes();
                if (bytes[0] == 169 && bytes[1] == 254) continue;
                candidates.Add(Tuple.Create(ip, rank));
            }
        }
        return candidates.OrderByDescending(value => value.Item2).Select(value => value.Item1).FirstOrDefault();
    }

    private void NetworkChanged(object sender, EventArgs args)
    {
        app.Dispatcher.BeginInvoke(new Action(delegate { if (!disposed) Listen(false); }));
    }

    private void SetStatus(string value)
    {
        Status = value;
        window.RefreshStatus();
    }

    public void Dispose()
    {
        disposed = true;
        NetworkChange.NetworkAddressChanged -= NetworkChanged;
        if (connection != null) connection.Dispose();
        clipboard.Dispose();
        tray.Visible = false;
        tray.ContextMenuStrip.Dispose();
        tray.Dispose();
        trayIcon.Dispose();
        identity.Dispose();
    }
}

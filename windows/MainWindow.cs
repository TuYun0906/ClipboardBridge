using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;

internal sealed class MainWindow : Window
{
    private static readonly Brush BackgroundColor = Brush(246, 247, 250);
    private static readonly Brush Ink = Brush(34, 41, 54);
    private static readonly Brush Muted = Brush(94, 106, 124);
    private static readonly Brush Blue = Brush(51, 104, 234);
    private static readonly Brush Tint = Brush(234, 240, 255);
    private static readonly Brush BorderColor = Brush(222, 229, 239);
    private static readonly FontFamily UiFont = new FontFamily("Microsoft YaHei UI, Segoe UI");
    private readonly Program program;
    private TextBlock status;
    private Window settings;
    private readonly System.Threading.Tasks.TaskScheduler thumbnailLoader =
        new System.Threading.Tasks.ConcurrentExclusiveSchedulerPair().ExclusiveScheduler;

    internal MainWindow(Program program)
    {
        this.program = program;
        Title = "剪贴板";
        FontFamily = UiFont;
        Width = 560;
        Height = 530;
        MinWidth = 385;
        MinHeight = 330;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Icon = program.WindowIcon;
        Content = Panel();
        Closing += (sender, args) =>
        {
            if (program.Exiting || Application.Current.Dispatcher.HasShutdownStarted) return;
            args.Cancel = true;
            program.SetVisible(false);
        };
    }

    internal void RefreshStatus()
    {
        if (status != null) status.Text = program.Status ?? "等待连接";
    }

    private Border Panel()
    {
        var body = new Grid { Margin = new Thickness(22) };
        for (int i = 0; i < 5; i++) body.RowDefinitions.Add(new RowDefinition {
            Height = i == 4 ? new GridLength(1, GridUnitType.Star) : GridLength.Auto
        });
        status = Label("", 14, Muted, false);
        var statusRow = new DockPanel();
        var help = new Button { Content = "如何上手", Foreground = Blue, Background = Brushes.Transparent,
            BorderThickness = new Thickness(0), Padding = new Thickness(8, 0, 0, 0), Cursor = Cursors.Hand };
        help.Click += (sender, args) => OpenHelp();
        DockPanel.SetDock(help, Dock.Right);
        statusRow.Children.Add(help);
        statusRow.Children.Add(status);
        body.Children.Add(statusRow);
        RefreshStatus();

        var actions = new Grid { Height = 42, Margin = new Thickness(0, 12, 0, 16) };
        for (int i = 0; i < 3; i++) actions.ColumnDefinitions.Add(new ColumnDefinition());
        AddAction(actions, 0, Action("发送文字", true, SendText));
        AddAction(actions, 1, Action("选择图片", false, SendImage));
        AddAction(actions, 2, Action("设置", false, OpenSettings));
        Grid.SetRow(actions, 1);
        body.Children.Add(actions);

        var searchRow = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
        var heading = Label("剪贴板历史", 16, Ink, true);
        heading.Margin = new Thickness(0, 0, 14, 0);
        heading.VerticalAlignment = VerticalAlignment.Center;
        searchRow.Children.Add(heading);
        var type = new ComboBox { Width = 67, Height = 32, Margin = new Thickness(8, 0, 0, 0),
            ItemsSource = new[] { "全部", "文字", "图片" }, SelectedIndex = 0 };
        System.Windows.Automation.AutomationProperties.SetName(type, "历史类型");
        DockPanel.SetDock(type, Dock.Right);
        searchRow.Children.Add(type);
        var search = new TextBox { Height = 32, Padding = new Thickness(8, 4, 8, 4),
            ToolTip = "搜索历史文字或图片" };
        System.Windows.Automation.AutomationProperties.SetName(search, "搜索历史");
        searchRow.Children.Add(search);
        Grid.SetRow(searchRow, 2);
        body.Children.Add(searchRow);

        var dates = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        dates.ColumnDefinitions.Add(new ColumnDefinition());
        dates.ColumnDefinitions.Add(new ColumnDefinition());
        dates.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var from = HistoryDatePicker("开始日期");
        var through = HistoryDatePicker("结束日期");
        AddAction(dates, 0, HistoryDateField("开始日期", from));
        AddAction(dates, 1, HistoryDateField("结束日期", through));
        var clearDates = Action("清除日期", false, () =>
        {
            from.SelectedDate = through.SelectedDate = null;
            from.Text = through.Text = "";
        });
        clearDates.Width = 76;
        clearDates.Height = 32;
        clearDates.VerticalAlignment = VerticalAlignment.Bottom;
        AddAction(dates, 2, clearDates);
        Grid.SetRow(dates, 3);
        body.Children.Add(dates);

        var items = new ListBox { BorderBrush = BorderColor };
        ScrollViewer.SetHorizontalScrollBarVisibility(items, ScrollBarVisibility.Disabled);
        Grid.SetRow(items, 4);
        body.Children.Add(items);
        Action refresh = () =>
        {
            items.Items.Clear();
            if (from.SelectedDate > through.SelectedDate)
            {
                items.Items.Add(Label("结束日期须在开始日期当天或之后", 14, Muted, false));
                return;
            }
            byte? kind = type.SelectedIndex == 1 ? Wire.Text : type.SelectedIndex == 2 ? Wire.Image : (byte?)null;
            foreach (var entry in program.History.Search(search.Text, from.SelectedDate, through.SelectedDate, kind))
            {
                var row = new DockPanel { LastChildFill = true, Margin = new Thickness(3), Height = 48 };
                var copy = Action("复制", false, () => program.CopyHistory(entry.Path));
                copy.Width = 62;
                var delete = Action("删除", false, () => program.DeleteHistory(entry.Key));
                delete.Width = 62;
                delete.Margin = new Thickness(6, 0, 0, 0);
                DockPanel.SetDock(delete, Dock.Right);
                row.Children.Add(delete);
                DockPanel.SetDock(copy, Dock.Right);
                row.Children.Add(copy);
                var details = new StackPanel { Margin = new Thickness(5, 0, 10, 0),
                    VerticalAlignment = VerticalAlignment.Center };
                if (entry.Kind == Wire.Image)
                {
                    row.Height = 82;
                    copy.Height = delete.Height = 36;
                    AddHistoryImage(details, entry.Path);
                }
                else details.Children.Add(Label(entry.Label, 14, Ink, false));
                string timestamp = entry.CopyTime.HasValue
                    ? entry.CopyTime.Value.ToString("yyyy-MM-dd HH:mm:ss", System.Globalization.CultureInfo.InvariantCulture)
                    : "复制时间未知";
                var time = Label(timestamp, 12, Muted, false);
                time.Margin = new Thickness(0, 3, 0, 0);
                details.Children.Add(time);
                row.Children.Add(details);
                var item = new ListBoxItem { Content = row, HorizontalContentAlignment = HorizontalAlignment.Stretch };
                item.ToolTip = "双击预览";
                item.MouseDoubleClick += (sender, args) =>
                {
                    if (args.ChangedButton != MouseButton.Left || copy.IsMouseOver || delete.IsMouseOver) return;
                    args.Handled = true;
                    OpenHistoryPreview(entry);
                };
                items.Items.Add(item);
            }
            if (items.Items.Count == 0) items.Items.Add(Label("暂无匹配记录", 14, Muted, false));
        };
        search.TextChanged += (sender, args) => refresh();
        type.SelectionChanged += (sender, args) => refresh();
        from.SelectedDateChanged += (sender, args) => refresh();
        through.SelectedDateChanged += (sender, args) => refresh();
        program.HistoryChanged += refresh;
        IsVisibleChanged += (sender, args) => { if (IsVisible) refresh(); };
        refresh();
        return new Border { Background = BackgroundColor, Child = body };
    }

    private static DatePicker HistoryDatePicker(string label)
    {
        var picker = new DatePicker { Height = 32 };
        System.Windows.Automation.AutomationProperties.SetName(picker, label);
        return picker;
    }

    private static StackPanel HistoryDateField(string label, DatePicker picker)
    {
        var field = new StackPanel();
        field.Children.Add(Label(label, 12, Muted, false));
        field.Children.Add(picker);
        return field;
    }

    private void OpenHelp()
    {
        var dialog = Dialog("如何上手", 440);
        dialog.SizeToContent = SizeToContent.Manual;
        dialog.Height = Math.Min(580, SystemParameters.WorkArea.Height - 40);
        var body = DialogBody();
        AddHelpSection(body, "局域网配对", "让电脑和安卓设备连接同一个局域网。在电脑「设置」查看地址并复制配对码，再到安卓端填写地址和配对码，点「保存连接」。");
        AddHelpSection(body, "Android 后台同步", "先安装并启动 Shizuku。Android 10 用 USB 和电脑上的 ADB 启动。Android 11 及以上可用无线调试配对并启动。回到剪贴板，开启自动发送并授权 Shizuku。");
        AddHelpLink(body, "查看 Shizuku 启动步骤", "https://shizuku.rikka.app/guide/setup/");
        AddHelpSection(body, "Tailscale 公网连接", "让两端加入同一个 Tailscale 网络。在电脑开启公网模式并保存，记下显示的 Tailscale 地址。安卓端也切到公网模式，填写这个地址和原来的配对码，再保存连接。");
        AddHelpSection(body, "内网映射连接", "电脑关闭 Tailscale 模式。用映射工具将公网 TCP 端口转发至设置显示的局域网地址和端口 47832。安卓开启公网模式，填写映射的公网地址和端口，再用原配对码保存。");
        AddHelpLink(body, "查看 Tailscale 安装说明", "https://tailscale.com/download");
        var shell = new DockPanel { Background = BackgroundColor, Margin = new Thickness(0) };
        var close = Action("关闭", false, dialog.Close);
        close.Height = 40;
        close.Margin = new Thickness(19, 8, 19, 16);
        close.IsCancel = true;
        DockPanel.SetDock(close, Dock.Bottom);
        shell.Children.Add(close);
        shell.Children.Add(new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        dialog.Content = shell;
        dialog.ShowDialog();
    }

    private static void AddHelpSection(StackPanel body, string title, string instructions)
    {
        var heading = Label(title, 16, Ink, true);
        heading.Margin = new Thickness(0, 12, 0, 5);
        body.Children.Add(heading);
        body.Children.Add(new TextBlock { Text = instructions, TextWrapping = TextWrapping.Wrap,
            Foreground = Muted, FontSize = 14, LineHeight = 22 });
    }

    private static void AddHelpLink(StackPanel body, string title, string url)
    {
        var link = Action(title, false, () =>
        {
            try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true }); }
            catch (System.ComponentModel.Win32Exception) { MessageBox.Show("无法打开浏览器。", "如何上手"); }
        });
        link.Height = 36;
        link.Margin = new Thickness(0, 8, 0, 0);
        body.Children.Add(link);
    }

    private void AddHistoryImage(Panel row, string path)
    {
        var image = new Image { Stretch = Stretch.Uniform };
        var thumbnail = new Border
        {
            Child = image, Background = Tint, CornerRadius = new CornerRadius(8),
            Width = 104, Height = 55, HorizontalAlignment = HorizontalAlignment.Left
        };
        row.Children.Add(thumbnail);
        thumbnail.Loaded += async (sender, args) =>
        {
            if (image.Source != null) return;
            image.Source = await LoadHistoryImage(path, 176);
            if (image.Source == null) thumbnail.ToolTip = "图片无法读取";
        };
    }

    private System.Threading.Tasks.Task<System.Windows.Media.Imaging.BitmapSource> LoadHistoryImage(string path, int limit)
    {
        return System.Threading.Tasks.Task.Factory.StartNew(() =>
        {
            try { return WindowsClipboard.DecodePreview(ClipHistory.Read(path).Data, limit); }
            catch (Exception) { return null; }
        }, System.Threading.CancellationToken.None, System.Threading.Tasks.TaskCreationOptions.None, thumbnailLoader);
    }

    private void OpenHistoryPreview(ClipHistory.Entry entry)
    {
        bool isImage = entry.Kind == Wire.Image;
        var window = new Window
        {
            Owner = this, Title = isImage ? "图片预览" : "文字预览",
            Width = Math.Min(740, SystemParameters.WorkArea.Width),
            Height = Math.Min(560, SystemParameters.WorkArea.Height),
            MinWidth = 300, MinHeight = 220, ShowInTaskbar = false, FontFamily = UiFont,
            Background = BackgroundColor, WindowStartupLocation = WindowStartupLocation.CenterOwner
        };
        window.PreviewKeyDown += (sender, args) => { if (args.Key == Key.Escape) window.Close(); };
        var layout = new DockPanel { Margin = new Thickness(16) };
        var hint = Label(isImage ? "滚轮缩放图片" : "滚轮调整字号 · 拖动滚动条浏览", 13, Muted, false);
        hint.Margin = new Thickness(0, 0, 0, 10);
        DockPanel.SetDock(hint, Dock.Top);
        layout.Children.Add(hint);
        window.Content = layout;
        if (isImage) AddImagePreview(window, layout, entry.Path);
        else AddTextPreview(window, layout, entry);
        window.Show();
    }

    private void AddTextPreview(Window window, DockPanel layout, ClipHistory.Entry entry)
    {
        var text = new TextBox
        {
            Text = entry.Text, TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, AcceptsReturn = true,
            FontSize = 16, Foreground = Ink, BorderBrush = BorderColor, Padding = new Thickness(12)
        };
        string original = text.Text;
        bool saving = false, allowClose = false;
        Button save = null;
        Action commit = async () =>
        {
            if (saving) return;
            if (text.Text.Length == 0)
            {
                MessageBox.Show(window, "请输入文字。", "文字预览");
                return;
            }
            saving = true;
            save.IsEnabled = false;
            text.IsReadOnly = true;
            try
            {
                if (!await program.EditHistory(entry.Key, text.Text))
                {
                    MessageBox.Show(window, "这条记录已更新或删除。", "无法保存更改");
                    return;
                }
                allowClose = true;
                window.Close();
            }
            catch (IOException error) { MessageBox.Show(window, error.Message, "无法保存更改"); }
            catch (UnauthorizedAccessException error) { MessageBox.Show(window, error.Message, "无法保存更改"); }
            finally
            {
                saving = false;
                save.IsEnabled = true;
                text.IsReadOnly = false;
            }
        };
        save = Action("保存", true, commit);
        save.Height = 42;
        save.Margin = new Thickness(0, 10, 0, 0);
        save.Visibility = Visibility.Collapsed;
        DockPanel.SetDock(save, Dock.Bottom);
        layout.Children.Add(save);
        layout.Children.Add(text);
        text.TextChanged += (sender, args) =>
            save.Visibility = text.Text == original ? Visibility.Collapsed : Visibility.Visible;
        window.Closing += (sender, args) =>
        {
            if (allowClose || text.Text == original) return;
            args.Cancel = true;
            if (saving) return;
            var answer = MessageBox.Show(window, "你编辑了这条信息，是否保存更改？", "保存更改",
                MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.Yes);
            if (answer == MessageBoxResult.No) args.Cancel = false;
            else window.Dispatcher.BeginInvoke(commit);
        };
        window.PreviewMouseWheel += (sender, args) =>
        {
            text.FontSize = Math.Max(10, Math.Min(72, text.FontSize * Math.Pow(1.12, args.Delta / 120.0)));
            args.Handled = true;
        };
    }

    private void AddImagePreview(Window window, Panel layout, string path)
    {
        var image = new Image { Stretch = Stretch.Uniform };
        var scroll = new ScrollViewer
        {
            Content = image, Background = Brushes.White,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto
        };
        layout.Children.Add(scroll);
        double zoom = 1;
        Action resize = () =>
        {
            var source = image.Source;
            if (source == null || scroll.ActualWidth <= 0 || scroll.ActualHeight <= 0) return;
            double fit = Math.Min(1, Math.Min(scroll.ActualWidth / source.Width, scroll.ActualHeight / source.Height));
            image.Width = source.Width * fit * zoom;
            image.Height = source.Height * fit * zoom;
        };
        scroll.SizeChanged += (sender, args) => resize();
        window.PreviewMouseWheel += (sender, args) =>
        {
            zoom = Math.Max(0.25, Math.Min(12, zoom * Math.Pow(1.15, args.Delta / 120.0)));
            resize();
            args.Handled = true;
        };
        window.Loaded += async (sender, args) =>
        {
            image.Source = await LoadHistoryImage(path, 4096);
            if (image.Source != null) resize();
            else scroll.Content = Label("图片无法读取", 14, Muted, false);
        };
    }

    internal void OpenSettings()
    {
        if (settings != null) { settings.Activate(); return; }
        var dialog = Dialog("剪贴板设置", 410);
        settings = dialog;
        dialog.Closed += (sender, args) => settings = null;
        var body = DialogBody();
        body.Children.Add(Label("连接", 20, Ink, true));
        var publicMode = Check("使用 Tailscale 监听", program.PublicMode);
        body.Children.Add(publicMode);
        var address = new TextBox
        {
            Text = program.AddressText, IsReadOnly = true, Foreground = Ink, Background = BackgroundColor,
            BorderThickness = new Thickness(0), Padding = new Thickness(10, 7, 10, 7), Margin = new Thickness(0, 3, 0, 9)
        };
        publicMode.Checked += (sender, args) => address.Text = publicMode.IsChecked == program.PublicMode
            ? program.AddressText : "保存后查看公网地址";
        publicMode.Unchecked += (sender, args) => address.Text = publicMode.IsChecked == program.PublicMode
            ? program.AddressText : "保存后查看局域网地址";
        body.Children.Add(address);
        Button pair = null;
        pair = Action("复制配对码", false, () =>
        {
            program.CopyPairingCode();
            pair.Content = "配对码已复制";
        });
        pair.Height = 40;
        body.Children.Add(pair);
        var automatic = Check("自动发送刚复制的内容", program.Automatic);
        var images = Check("历史记录保存图片", program.History.SaveImages);
        var startup = Check("开机自启", Program.StartWithWindows);
        body.Children.Add(automatic);
        body.Children.Add(images);
        body.Children.Add(startup);
        var save = Action("保存设置", true, () =>
        {
            bool auto = automatic.IsChecked == true, online = publicMode.IsChecked == true,
                saveImages = images.IsChecked == true;
            try
            {
                bool enabled = startup.IsChecked == true;
                Program.SaveStartup(enabled);
            }
            catch (Exception error)
            {
                MessageBox.Show(dialog, error.Message, "无法保存开机自启设置");
                return;
            }
            dialog.Close();
            program.SaveSettings(auto, online, program.ShowWindow, saveImages);
        });
        save.Height = 43;
        save.Margin = new Thickness(0, 11, 0, 0);
        body.Children.Add(save);
        dialog.Content = body;
        dialog.ShowDialog();
    }

    private void SendText()
    {
        var dialog = Dialog("发送文字", 410);
        var body = DialogBody();
        var input = new TextBox
        {
            AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, Height = 116, Foreground = Ink,
            BorderBrush = BorderColor, Padding = new Thickness(10), VerticalScrollBarVisibility = ScrollBarVisibility.Auto
        };
        body.Children.Add(input);
        var button = Action("发送", true, () =>
        {
            if (input.Text.Length == 0) return;
            program.Send(input.Text);
            dialog.Close();
        });
        button.Height = 43;
        button.Margin = new Thickness(0, 13, 0, 0);
        body.Children.Add(button);
        dialog.Content = body;
        dialog.Loaded += (sender, args) => input.Focus();
        dialog.ShowDialog();
    }

    private void SendImage()
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择要发送的图片", Filter = "图片|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.tif;*.tiff",
            Multiselect = false
        };
        if (dialog.ShowDialog(this) == true) program.Send(new FileInfo(dialog.FileName));
    }

    private Window Dialog(string title, double width)
    {
        var dialog = new Window
        {
            Title = title, Width = width, WindowStyle = WindowStyle.ToolWindow,
            ResizeMode = ResizeMode.NoResize, SizeToContent = SizeToContent.Height,
            ShowInTaskbar = false, Background = Brushes.White, FontFamily = UiFont,
            WindowStartupLocation = IsVisible ? WindowStartupLocation.CenterOwner : WindowStartupLocation.CenterScreen
        };
        if (IsVisible) dialog.Owner = this;
        return dialog;
    }

    private static StackPanel DialogBody() { return new StackPanel { Margin = new Thickness(19) }; }

    private static CheckBox Check(string text, bool value)
    {
        return new CheckBox
        {
            Content = text, IsChecked = value, Foreground = Ink,
            FontSize = 14, Margin = new Thickness(0, 12, 0, 5)
        };
    }

    private static void AddAction(Grid grid, int column, FrameworkElement control)
    {
        if (column != 0) control.Margin = new Thickness(7, 0, 0, 0);
        Grid.SetColumn(control, column);
        grid.Children.Add(control);
    }

    private static TextBlock Label(string text, double size, Brush foreground, bool strong)
    {
        return new TextBlock
        {
            Text = text, FontSize = size, Foreground = foreground,
            FontWeight = strong ? FontWeights.SemiBold : FontWeights.Normal,
            TextTrimming = TextTrimming.CharacterEllipsis
        };
    }

    private static Button Action(string text, bool primary, Action click)
    {
        var border = new FrameworkElementFactory(typeof(Border), "Surface");
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(12));
        border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
        var center = new FrameworkElementFactory(typeof(ContentPresenter));
        center.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
        center.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
        border.AppendChild(center);
        var template = new ControlTemplate(typeof(Button)) { VisualTree = border };
        var hover = new Trigger { Property = IsMouseOverProperty, Value = true };
        hover.Setters.Add(new Setter(OpacityProperty, 0.84, "Surface"));
        template.Triggers.Add(hover);
        var pressed = new Trigger { Property = ButtonBase.IsPressedProperty, Value = true };
        pressed.Setters.Add(new Setter(OpacityProperty, 0.68, "Surface"));
        template.Triggers.Add(pressed);
        var button = new Button
        {
            Content = text, Background = primary ? Blue : Tint,
            Foreground = primary ? Brushes.White : Blue, FontSize = 14,
            FontWeight = FontWeights.SemiBold, Cursor = Cursors.Hand, Template = template
        };
        button.Click += (sender, args) => click();
        return button;
    }

    private static Brush Brush(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
    }
}

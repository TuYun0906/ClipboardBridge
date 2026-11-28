using System;
using System.IO;
using System.Linq;
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
    private static readonly ControlTemplate HistoryItemTemplate = CreateHistoryItemTemplate();
    private readonly Program program;
    private TextBlock status;
    private Window settings;
    private readonly System.Threading.Tasks.TaskScheduler thumbnailLoader =
        new System.Threading.Tasks.ConcurrentExclusiveSchedulerPair().ExclusiveScheduler;

    internal MainWindow(Program program)
    {
        this.program = program;
        Title = "跨设备剪贴板";
        FontFamily = UiFont;
        Width = 600;
        Height = 590;
        MinWidth = 385;
        MinHeight = 380;
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
        if (status != null) status.Text = (program.Status ?? "等待连接") +
            (String.IsNullOrEmpty(program.Feedback) ? "" : " · " + program.Feedback);
    }

    private Border Panel()
    {
        var body = new Grid { Margin = new Thickness(20) };
        body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        body.RowDefinitions.Add(new RowDefinition());
        status = Label("", 13, Muted, false);
        var statusRow = new DockPanel();
        var help = Action("如何上手", false, OpenHelp);
        help.Width = 82;
        help.Height = 32;
        help.VerticalAlignment = VerticalAlignment.Top;
        DockPanel.SetDock(help, Dock.Right);
        statusRow.Children.Add(help);
        var devices = Action("在线设备", false, ShowDevices);
        devices.Width = 82;
        devices.Height = 32;
        devices.Margin = new Thickness(0, 0, 7, 0);
        devices.VerticalAlignment = VerticalAlignment.Top;
        DockPanel.SetDock(devices, Dock.Right);
        statusRow.Children.Add(devices);
        var identity = new StackPanel();
        identity.Children.Add(Label("跨设备剪贴板", 24, Ink, true));
        status.Margin = new Thickness(0, 5, 0, 0);
        identity.Children.Add(status);
        statusRow.Children.Add(identity);
        body.Children.Add(statusRow);
        RefreshStatus();

        var actions = new Grid { Height = 42, Margin = new Thickness(0, 12, 0, 16) };
        for (int i = 0; i < 3; i++) actions.ColumnDefinitions.Add(new ColumnDefinition());
        AddAction(actions, 0, Action("发送文字", true, SendText));
        AddAction(actions, 1, Action("选择图片", false, SendImage));
        AddAction(actions, 2, Action("设置", false, OpenSettings));
        Grid.SetRow(actions, 1);
        body.Children.Add(actions);

        var history = new Grid();
        history.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        history.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        history.RowDefinitions.Add(new RowDefinition());
        history.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var historyCard = new Border { CornerRadius = new CornerRadius(20),
            Padding = new Thickness(14), Background = Brushes.White, Child = history };
        Grid.SetRow(historyCard, 2);
        body.Children.Add(historyCard);
        var searchRow = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
        var heading = Label("剪贴板历史", 16, Ink, true);
        heading.Margin = new Thickness(0, 0, 14, 0);
        heading.VerticalAlignment = VerticalAlignment.Center;
        searchRow.Children.Add(heading);
        byte? kind = null;
        var type = Action("全部", false, () => { });
        type.Width = 67;
        type.Height = 34;
        type.Margin = new Thickness(8, 0, 0, 0);
        type.ToolTip = "点击切换历史类型";
        DockPanel.SetDock(type, Dock.Right);
        searchRow.Children.Add(type);
        var sync = Action("刷新记录", false, program.RefreshHistory);
        sync.Width = 78;
        sync.Height = 34;
        sync.Margin = new Thickness(8, 0, 0, 0);
        DockPanel.SetDock(sync, Dock.Right);
        searchRow.Children.Add(sync);
        var search = new TextBox { Height = 34, Padding = new Thickness(8, 4, 8, 4),
            VerticalContentAlignment = VerticalAlignment.Center, BorderThickness = new Thickness(0),
            Background = Brushes.Transparent, Foreground = Ink, ToolTip = "搜索历史文字或图片" };
        System.Windows.Automation.AutomationProperties.SetName(search, "搜索历史");
        searchRow.Children.Add(new Border { Background = BackgroundColor, CornerRadius = new CornerRadius(10), Child = search });
        history.Children.Add(searchRow);

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
        Grid.SetRow(dates, 1);
        history.Children.Add(dates);

        var items = new ListBox { BorderThickness = new Thickness(0), Background = Brushes.Transparent };
        ScrollViewer.SetHorizontalScrollBarVisibility(items, ScrollBarVisibility.Disabled);
        ScrollViewer.SetCanContentScroll(items, false);
        Grid.SetRow(items, 2);
        history.Children.Add(items);
        int page = 0;
        int[] sizes = { 10, 25, 50, 100 };
        Action refresh = null;
        var pagination = new DockPanel { Margin = new Thickness(0, 6, 0, 0) };
        var sizeButton = Action("", false, () =>
        {
            int index = Array.IndexOf(sizes, program.HistoryPageSize);
            program.SetHistoryPageSize(sizes[(index + 1) % sizes.Length]);
            page = 0;
            refresh();
        });
        sizeButton.Height = 29;
        sizeButton.Width = 85;
        sizeButton.ToolTip = "点击切换每页条数：10、25、50、100";
        pagination.Children.Add(sizeButton);
        var next = Action("下一页", false, () => { page++; refresh(); });
        next.Width = 62;
        DockPanel.SetDock(next, Dock.Right);
        pagination.Children.Add(next);
        var previous = Action("上一页", false, () => { page--; refresh(); });
        previous.Width = 62;
        previous.Margin = new Thickness(0, 0, 6, 0);
        DockPanel.SetDock(previous, Dock.Right);
        pagination.Children.Add(previous);
        var pageLabel = Label("", 12, Muted, false);
        pageLabel.VerticalAlignment = VerticalAlignment.Center;
        pageLabel.HorizontalAlignment = HorizontalAlignment.Center;
        pagination.Children.Add(pageLabel);
        Grid.SetRow(pagination, 3);
        history.Children.Add(pagination);
        refresh = () =>
        {
            items.Items.Clear();
            int pageSize = program.HistoryPageSize;
            sizeButton.Content = "每页 " + pageSize + " 条";
            var matches = from.SelectedDate > through.SelectedDate ? new ClipHistory.Entry[0] :
                program.History.Search(search.Text, from.SelectedDate, through.SelectedDate, kind);
            int pageCount = Math.Max(1, (matches.Length + pageSize - 1) / pageSize);
            page = Math.Max(0, Math.Min(page, pageCount - 1));
            pageLabel.Text = (page + 1) + " / " + pageCount;
            previous.IsEnabled = page > 0;
            next.IsEnabled = page + 1 < pageCount;
            var scroll = items.Template == null ? null : items.Template.FindName("PART_ScrollViewer", items) as ScrollViewer;
            if (scroll != null) scroll.ScrollToTop();
            if (from.SelectedDate > through.SelectedDate)
            {
                items.Items.Add(Label("结束日期须在开始日期当天或之后", 14, Muted, false));
                return;
            }
            foreach (var entry in matches.Skip(page * pageSize).Take(pageSize))
            {
                var row = new DockPanel { LastChildFill = true, Margin = new Thickness(3), Height = 64 };
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
                    row.Height = entry.CompressionLabel != null ? 115 : 98;
                    copy.Height = delete.Height = 36;
                    AddHistoryImage(details, entry.Path);
                }
                else details.Children.Add(Label(entry.Label, 14, Ink, false));
                string timestamp = entry.CopyTime.HasValue
                    ? entry.CopyTime.Value.ToString("yyyy-MM-dd HH:mm:ss", System.Globalization.CultureInfo.InvariantCulture)
                    : "复制时间未知";
                if (entry.CompressionLabel != null)
                {
                    string compression = entry.CompressionLabel;
                    var badge = Label(compression, 12, Blue, false);
                    badge.Margin = new Thickness(0, 3, 0, 0);
                    details.Children.Add(badge);
                }
                var time = Label(timestamp, 12, Muted, false);
                time.Margin = new Thickness(0, 3, 0, 0);
                details.Children.Add(time);
                details.Children.Add(Label("来自 " + (entry.Source ?? "未知设备"), 12, Muted, false));
                row.Children.Add(details);
                var item = new ListBoxItem { Content = row, HorizontalContentAlignment = HorizontalAlignment.Stretch,
                    Margin = new Thickness(0, 0, 0, 6), Template = HistoryItemTemplate };
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
        Action resetPage = () => { page = 0; refresh(); };
        search.TextChanged += (sender, args) => resetPage();
        type.Click += (sender, args) =>
        {
            kind = !kind.HasValue ? Wire.Text : kind.Value == Wire.Text ? Wire.Image : (byte?)null;
            type.Content = kind == Wire.Text ? "文字" : kind == Wire.Image ? "图片" : "全部";
            resetPage();
        };
        from.SelectedDateChanged += (sender, args) => resetPage();
        through.SelectedDateChanged += (sender, args) => resetPage();
        program.HistoryChanged += refresh;
        IsVisibleChanged += (sender, args) => { if (IsVisible) refresh(); };
        refresh();
        return new Border { Background = BackgroundColor, Child = body };
    }

    private static ControlTemplate CreateHistoryItemTemplate()
    {
        var surface = new FrameworkElementFactory(typeof(Border), "Card");
        surface.SetValue(Border.CornerRadiusProperty, new CornerRadius(12));
        surface.SetValue(Border.BackgroundProperty, BackgroundColor);
        surface.SetValue(Border.PaddingProperty, new Thickness(4));
        var content = new FrameworkElementFactory(typeof(ContentPresenter));
        content.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Stretch);
        surface.AppendChild(content);
        var template = new ControlTemplate(typeof(ListBoxItem)) { VisualTree = surface };
        var selected = new Trigger { Property = ListBoxItem.IsSelectedProperty, Value = true };
        selected.Setters.Add(new Setter(Border.BackgroundProperty, Tint, "Card"));
        template.Triggers.Add(selected);
        return template;
    }

    private static DatePicker HistoryDatePicker(string label)
    {
        var picker = new DatePicker { Height = 34, Foreground = Ink,
            Template = DateFieldTemplate };
        System.Windows.Automation.AutomationProperties.SetName(picker, label);
        return picker;
    }

    private static readonly ControlTemplate DateFieldTemplate =
        (ControlTemplate)System.Windows.Markup.XamlReader.Parse(
            @"<ControlTemplate xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation""
                xmlns:x=""http://schemas.microsoft.com/winfx/2006/xaml"" TargetType=""DatePicker"">
                <Border Background=""#F6F7FA"" CornerRadius=""10"">
                    <Grid x:Name=""PART_Root"">
                        <Grid.ColumnDefinitions><ColumnDefinition/><ColumnDefinition Width=""34""/></Grid.ColumnDefinitions>
                        <DatePickerTextBox x:Name=""PART_TextBox"" Margin=""7,0,0,0"" Background=""Transparent""
                            BorderThickness=""0"" VerticalAlignment=""Center"" Foreground=""#222936"" FontSize=""13""/>
                        <Button x:Name=""PART_Button"" Grid.Column=""1"" Margin=""2"" Background=""#EAF0FF""
                            Cursor=""Hand"" AutomationProperties.Name=""打开日历"">
                            <Button.Template>
                                <ControlTemplate TargetType=""Button"">
                                    <Border x:Name=""IconSurface"" CornerRadius=""8"" Background=""{TemplateBinding Background}"">
                                        <Path Width=""16"" Height=""16"" Stretch=""Uniform"" Stroke=""#3368EA""
                                            StrokeThickness=""1.5"" StrokeStartLineCap=""Round"" StrokeEndLineCap=""Round""
                                            StrokeLineJoin=""Round"" HorizontalAlignment=""Center"" VerticalAlignment=""Center""
                                            Data=""M 2,4 L 14,4 Q 15,4 15,5 L 15,14 Q 15,15 14,15 L 2,15 Q 1,15 1,14 L 1,5 Q 1,4 2,4 Z M 1,8 L 15,8 M 5,2 L 5,5 M 11,2 L 11,5 M 5,11 L 7,11""/>
                                    </Border>
                                    <ControlTemplate.Triggers>
                                        <Trigger Property=""IsMouseOver"" Value=""True""><Setter TargetName=""IconSurface"" Property=""Background"" Value=""#DCE7FF""/></Trigger>
                                        <Trigger Property=""IsPressed"" Value=""True""><Setter TargetName=""IconSurface"" Property=""Background"" Value=""#CEDFFF""/></Trigger>
                                    </ControlTemplate.Triggers>
                                </ControlTemplate>
                            </Button.Template>
                        </Button>
                        <Popup x:Name=""PART_Popup"" Placement=""Bottom"" PlacementTarget=""{Binding ElementName=PART_Root}""
                            StaysOpen=""False"" AllowsTransparency=""True""/>
                    </Grid>
                </Border>
            </ControlTemplate>");

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
        AddHelpSection(body, "Android 当主机", "两端连接同一个 Wi-Fi。在 Android 打开「作为主机」，复制配对码。在 Windows「设置」勾选「连接到 Android 主机」，填写 Android 显示的地址和配对码，然后保存。");
        AddHelpSection(body, "Android 后台同步", "先安装并启动 Shizuku。Android 10 用 USB 和电脑上的 ADB 启动。Android 11 及以上可用无线调试配对并启动。回到剪贴板，开启自动发送并授权 Shizuku。");
        AddHelpLink(body, "查看 Shizuku 启动步骤", "https://shizuku.rikka.app/guide/setup/");
        AddHelpSection(body, "Tailscale 公网连接", "让两端加入同一个 Tailscale 网络。在电脑开启公网模式并保存，记下显示的 Tailscale 地址。安卓端也切到公网模式，填写这个地址和原来的配对码，再保存连接。");
        AddHelpSection(body, "内网映射连接", "电脑关闭「使用 Tailscale 监听」。同一台电脑上的 SakuraFrp 等工具可把公网 TCP 端口转发到 127.0.0.1:47832。若映射工具在其他设备上，填写电脑的局域网地址和端口 47832。安卓开启公网模式，填写映射的公网地址和端口，再用原配对码保存。");
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
        var link = Action(title, false, () => OpenLink(url));
        link.Height = 36;
        link.Margin = new Thickness(0, 8, 0, 0);
        body.Children.Add(link);
    }

    private static void OpenLink(string url)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (System.ComponentModel.Win32Exception) { MessageBox.Show("无法打开浏览器。", "跨设备剪贴板"); }
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
        var clientMode = Check("连接到 Android 主机", program.ClientMode);
        body.Children.Add(clientMode);
        var desktop = new StackPanel();
        body.Children.Add(desktop);
        var publicMode = Check("使用 Tailscale 监听", program.PublicMode);
        desktop.Children.Add(publicMode);
        var address = new TextBox
        {
            Text = program.AddressText, IsReadOnly = true, Foreground = Ink, Background = BackgroundColor,
            BorderThickness = new Thickness(0), Padding = new Thickness(10, 7, 10, 7), Margin = new Thickness(0, 3, 0, 9)
        };
        publicMode.Checked += (sender, args) => address.Text = publicMode.IsChecked == program.PublicMode
            ? program.AddressText : "保存后查看公网地址";
        publicMode.Unchecked += (sender, args) => address.Text = publicMode.IsChecked == program.PublicMode
            ? program.AddressText : "保存后查看局域网地址";
        desktop.Children.Add(address);
        Button pair = null;
        pair = Action("复制配对码", false, () =>
        {
            program.CopyPairingCode();
            pair.Content = "配对码已复制";
        });
        pair.Height = 40;
        desktop.Children.Add(pair);
        var mobile = new StackPanel { Margin = new Thickness(0, 6, 0, 0) };
        body.Children.Add(mobile);
        mobile.Children.Add(Label("Android 主机地址（可加端口）", 13, Muted, false));
        var remote = new TextBox { Text = program.RemoteAddress, Padding = new Thickness(9, 6, 9, 6),
            Margin = new Thickness(0, 3, 0, 8), BorderBrush = BorderColor };
        mobile.Children.Add(remote);
        mobile.Children.Add(Label("Android 主机配对码", 13, Muted, false));
        var code = new PasswordBox { Password = program.RemoteCode, Padding = new Thickness(9, 6, 9, 6),
            Margin = new Thickness(0, 3, 0, 0), BorderBrush = BorderColor };
        mobile.Children.Add(code);
        Action updateMode = () =>
        {
            bool connecting = clientMode.IsChecked == true;
            mobile.Visibility = connecting ? Visibility.Visible : Visibility.Collapsed;
            desktop.Visibility = connecting ? Visibility.Collapsed : Visibility.Visible;
        };
        clientMode.Checked += (sender, args) => updateMode();
        clientMode.Unchecked += (sender, args) => updateMode();
        updateMode();
        var automatic = Check("自动发送刚复制的内容", program.Automatic);
        var images = Check("历史记录保存图片", program.History.SaveImages);
        var compress = Check("发送前压缩图片", program.CompressImages);
        compress.ToolTip = "减少发送的数据量，可能降低图片分辨率";
        var startup = Check("开机自启", Program.StartWithWindows);
        body.Children.Add(automatic);
        body.Children.Add(images);
        body.Children.Add(compress);
        body.Children.Add(startup);
        var save = Action("保存设置", true, () =>
        {
            bool auto = automatic.IsChecked == true, online = publicMode.IsChecked == true,
                saveImages = images.IsChecked == true;
            bool connecting = clientMode.IsChecked == true;
            string remoteAddress = remote.Text.Trim(), remoteCode = code.Password.Trim();
            Uri target;
            byte[] pairing;
            if (connecting && !SyncClient.TryEndpoint(remoteAddress, out target))
            {
                MessageBox.Show(dialog, "请填写 Android 显示的主机地址。", "主机地址");
                remote.Focus();
                return;
            }
            if (connecting && !SyncClient.TryPairing(remoteCode, out pairing))
            {
                MessageBox.Show(dialog, "请填写 Android 主机的完整配对码。", "配对码");
                code.Focus();
                return;
            }
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
            program.SaveSettings(auto, online, program.ShowWindow, saveImages, connecting, remoteAddress, remoteCode, compress.IsChecked == true);
        });
        save.Height = 43;
        save.Margin = new Thickness(0, 11, 0, 0);
        body.Children.Add(save);
        const string githubUrl = "https://github.com/TuYun0906/ClipboardBridge";
        var footer = new TextBlock { Margin = new Thickness(0, 12, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Center, FontSize = 13 };
        var github = new System.Windows.Documents.Hyperlink(new System.Windows.Documents.Run("github页"))
        {
            Foreground = Blue, NavigateUri = new Uri(githubUrl), ToolTip = githubUrl
        };
        github.RequestNavigate += (sender, args) =>
        {
            OpenLink(args.Uri.AbsoluteUri);
            args.Handled = true;
        };
        footer.Inlines.Add(github);
        body.Children.Add(footer);
        dialog.Content = body;
        dialog.ShowDialog();
    }

    private void ShowDevices()
    {
        var dialog = Dialog("在线设备", 360);
        var body = DialogBody();
        var list = new StackPanel();
        Action refresh = () =>
        {
            list.Children.Clear();
            if (String.IsNullOrEmpty(program.Devices))
            {
                list.Children.Add(Label("暂无连接", 14, Muted, false));
                return;
            }
            var names = program.Devices.Split('\n');
            for (int i = 0; i < names.Length; i++)
            {
                var item = Label(names[i] + (i == 0 ? "（主机）" : ""), 14, i == 0 ? Blue : Ink, i == 0);
                item.Margin = new Thickness(0, 0, 0, 9);
                list.Children.Add(item);
            }
        };
        refresh();
        program.DevicesChanged += refresh;
        dialog.Closed += (sender, args) => program.DevicesChanged -= refresh;
        body.Children.Add(new ScrollViewer { Content = list, MaxHeight = 350,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        var close = Action("关闭", false, dialog.Close);
        close.Margin = new Thickness(0, 12, 0, 0);
        body.Children.Add(close);
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
            Multiselect = true
        };
        if (dialog.ShowDialog(this) == true)
            program.SendMany(dialog.FileNames.Select(path => (object)new FileInfo(path)).ToArray(), true);
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
        var disabled = new Trigger { Property = IsEnabledProperty, Value = false };
        disabled.Setters.Add(new Setter(OpacityProperty, 0.42, "Surface"));
        template.Triggers.Add(disabled);
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

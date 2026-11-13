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
        for (int i = 0; i < 4; i++) body.RowDefinitions.Add(new RowDefinition {
            Height = i == 3 ? new GridLength(1, GridUnitType.Star) : GridLength.Auto
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
        var search = new TextBox { Height = 32, Padding = new Thickness(8, 4, 8, 4),
            ToolTip = "搜索历史文字或图片" };
        System.Windows.Automation.AutomationProperties.SetName(search, "搜索历史");
        searchRow.Children.Add(search);
        Grid.SetRow(searchRow, 2);
        body.Children.Add(searchRow);

        var items = new ListBox { BorderBrush = BorderColor };
        ScrollViewer.SetHorizontalScrollBarVisibility(items, ScrollBarVisibility.Disabled);
        Grid.SetRow(items, 3);
        body.Children.Add(items);
        Action refresh = () =>
        {
            items.Items.Clear();
            foreach (var entry in program.History.Search(search.Text))
            {
                var row = new DockPanel { LastChildFill = true, Margin = new Thickness(3), Height = 36 };
                var copy = Action("复制", false, () => program.CopyHistory(entry.Path));
                copy.Width = 62;
                var delete = Action("删除", false, () => program.DeleteHistory(entry.Key));
                delete.Width = 62;
                delete.Margin = new Thickness(6, 0, 0, 0);
                DockPanel.SetDock(delete, Dock.Right);
                row.Children.Add(delete);
                DockPanel.SetDock(copy, Dock.Right);
                row.Children.Add(copy);
                if (entry.Kind == Wire.Image)
                {
                    var preview = Action("预览", false, () => PreviewHistory(entry.Path));
                    preview.Width = 62;
                    preview.Margin = new Thickness(0, 0, 6, 0);
                    DockPanel.SetDock(preview, Dock.Right);
                    row.Children.Add(preview);
                }
                var text = Label(entry.Label, 14, Ink, false);
                text.Margin = new Thickness(5, 0, 10, 0);
                text.VerticalAlignment = VerticalAlignment.Center;
                row.Children.Add(text);
                items.Items.Add(new ListBoxItem { Content = row, HorizontalContentAlignment = HorizontalAlignment.Stretch });
            }
            if (items.Items.Count == 0) items.Items.Add(Label("暂无匹配记录", 14, Muted, false));
        };
        search.TextChanged += (sender, args) => refresh();
        program.HistoryChanged += refresh;
        IsVisibleChanged += (sender, args) => { if (IsVisible) refresh(); };
        refresh();
        return new Border { Background = BackgroundColor, Child = body };
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
        AddHelpLink(body, "查看 Tailscale 安装说明", "https://tailscale.com/download");
        dialog.Content = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
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

    private void PreviewHistory(string path)
    {
        try
        {
            var source = WindowsClipboard.ToClipboardData(ClipHistory.Read(path)).GetImage();
            if (source == null) throw new IOException("无法读取图片");
            var preview = new Window
            {
                Owner = this, Title = "图片预览", Width = 640, Height = 510,
                MinWidth = 280, MinHeight = 230, ShowInTaskbar = false,
                WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = BackgroundColor,
                Content = new Image { Source = source, Stretch = Stretch.Uniform, Margin = new Thickness(16) }
            };
            preview.ShowDialog();
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "无法预览图片");
        }
    }

    internal void OpenSettings()
    {
        if (settings != null) { settings.Activate(); return; }
        var dialog = Dialog("剪贴板设置", 410);
        settings = dialog;
        dialog.Closed += (sender, args) => settings = null;
        var body = DialogBody();
        body.Children.Add(Label("连接", 20, Ink, true));
        var publicMode = Check("公网模式（Tailscale）", program.PublicMode);
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

    private static void AddAction(Grid grid, int column, Button button)
    {
        if (column != 0) button.Margin = new Thickness(7, 0, 0, 0);
        Grid.SetColumn(button, column);
        grid.Children.Add(button);
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

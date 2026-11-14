using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Input;
using System.Windows.Threading;

internal static class WindowsAppCheck
{
    [STAThread]
    private static void Main(string[] args)
    {
        try { Check(args[0]); }
        catch (Exception error) { Console.Error.WriteLine(error.Message); Environment.ExitCode = 1; }
    }

    private static void Check(string path)
    {
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        Directory.CreateDirectory(path);
        File.WriteAllText(Path.Combine(path, "settings.txt"), "0111");
        var history = new ClipHistory(Path.Combine(path, "history"));
        string longText = new string('长', 240) + "\n第二行";
        history.Apply(WindowsClipboard.ToFrame(longText), true);
        history.Apply(WindowsClipboard.ToFrame("短文字"), true);
        var bitmap = System.Windows.Media.Imaging.BitmapSource.Create(320, 160, 96, 96,
            PixelFormats.Gray8, null, new byte[320 * 160], 320);
        history.Apply(WindowsClipboard.ToFrame(bitmap), false);
        var pixels = new byte[2400 * 1200 * 3];
        new Random(43).NextBytes(pixels);
        var noise = System.Windows.Media.Imaging.BitmapSource.Create(2400, 1200, 96, 96,
            PixelFormats.Bgr24, null, pixels, 2400 * 3);
        var original = WindowsClipboard.ToFrame(noise);
        var compressed = WindowsClipboard.ToFrame(noise, true);
        var decoded = WindowsClipboard.DecodePreview(compressed.Data, 4096);
        if (decoded.PixelWidth > 1920 || decoded.PixelWidth <= decoded.PixelHeight ||
            compressed.Data.Length >= original.Data.Length)
            throw new Exception("Photo compression did not reduce size");
        var transparent = System.Windows.Media.Imaging.BitmapSource.Create(1, 1, 96, 96,
            PixelFormats.Bgra32, null, new byte[] {0, 0, 0, 0}, 4);
        var alpha = WindowsClipboard.ToFrame(transparent, true);
        if (!Wire.IsCompressed(compressed.Id) || Wire.IsCompressed(original.Id) || Wire.IsCompressed(alpha.Id))
            throw new Exception("Compression provenance does not match actual output");
        if (alpha.Data[0] != 137) throw new Exception("Compression lost transparency");
        Console.WriteLine("PASS: compression reduces photo bytes and preserves transparency");
        using (var program = new Program(app, path))
        {
            var window = app.Windows.OfType<MainWindow>().Single();
            window.Width = window.MinWidth;
            window.Height = window.MinHeight;
            window.UpdateLayout();
            var list = Find<ListBox>(window);
            var scroll = Find<ScrollViewer>(list);
            var viewport = Find<ScrollContentPresenter>(scroll);
            if (scroll.ScrollableWidth > 0.1) throw new Exception("History overflows horizontally");
            foreach (ListBoxItem item in list.Items)
            {
                var row = (Panel)item.Content;
                foreach (var button in row.Children.OfType<Button>())
                {
                    var rect = button.TransformToAncestor(viewport).TransformBounds(
                        new Rect(new Point(), button.RenderSize));
                    if (rect.Width < 61 || rect.Left < 0 || rect.Right > viewport.ActualWidth + 0.1)
                        throw new Exception("History action outside the viewport: " + rect + " / " + viewport.ActualWidth);
                }
            }
            Console.WriteLine("PASS: both history actions fit on short and long rows at minimum window size");
            var type = Descendants<Button>(window).Single(button => Equals(button.Content, "全部"));
            type.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            if (!Equals(type.Content, "文字") || list.Items.OfType<ListBoxItem>().Count() != 2)
                throw new Exception("First click did not filter to text");
            type.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            if (!Equals(type.Content, "图片") || list.Items.OfType<ListBoxItem>().Count() != 1)
                throw new Exception("Second click did not filter to images");
            type.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            if (!Equals(type.Content, "全部") || list.Items.OfType<ListBoxItem>().Count() != 3)
                throw new Exception("Third click did not restore all entries");
            var dates = Descendants<DatePicker>(window).ToArray();
            foreach (var picker in dates)
            {
                picker.ApplyTemplate();
                var calendarButton = picker.Template.FindName("PART_Button", picker) as Button;
                if (calendarButton == null) throw new Exception("Missing date field calendar button");
                calendarButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                if (!picker.IsDropDownOpen) throw new Exception("Calendar button did not open date picker");
                picker.IsDropDownOpen = false;
            }
            Console.WriteLine("PASS: click-to-cycle history types and custom calendar buttons");
            var today = DateTime.Now.Date;
            dates[0].SelectedDate = today;
            dates[1].SelectedDate = today;
            if (list.Items.OfType<ListBoxItem>().Count() != 3 ||
                list.Items.OfType<ListBoxItem>().Any(item => !((Panel)item.Content).Children.OfType<StackPanel>().Single()
                    .Children.OfType<TextBlock>().Any(label => label.Text.StartsWith(
                        today.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture)))))
                throw new Exception("The selected date must show timestamps on all matching history rows");
            dates[0].SelectedDate = null;
            dates[1].SelectedDate = today.AddDays(-1);
            if (list.Items.OfType<ListBoxItem>().Any()) throw new Exception("Out-of-range history still visible");
            Descendants<Button>(window).Single(button => Equals(button.Content, "清除日期"))
                .RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            if (dates.Any(picker => picker.SelectedDate.HasValue) || list.Items.OfType<ListBoxItem>().Count() != 3)
                throw new Exception("Clearing dates did not restore history rows");
            Console.WriteLine("PASS: history timestamps, date picker and clear action");
            var textRow = list.Items.Cast<ListBoxItem>().Single(item => ((Panel)item.Content)
                .Children.OfType<StackPanel>().Single().Children.OfType<TextBlock>()
                .Any(label => label.Text.StartsWith("长")));
            var textWindow = OpenPreview(app, window, textRow);
            var text = Find<TextBox>(textWindow);
            if (text.Text != longText || text.IsReadOnly) throw new Exception("Editable full text preview missing");
            var previewLayout = (DockPanel)textWindow.Content;
            var save = previewLayout.Children.OfType<Button>().Single();
            if (save.Visibility != Visibility.Collapsed) throw new Exception("Save visible before editing");
            double fontSize = text.FontSize;
            Wheel(textWindow, 120);
            if (text.FontSize <= fontSize) throw new Exception("Text wheel did not zoom");
            text.Text = "编辑后的文字";
            if (save.Visibility != Visibility.Visible) throw new Exception("Save hidden after editing");
            text.Text = longText;
            if (save.Visibility != Visibility.Collapsed) throw new Exception("Save still visible after undo");
            text.Text = "编辑后的文字";
            save.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            for (int i = 0; i < 40 && textWindow.IsVisible; i++)
            {
                System.Threading.Thread.Sleep(25);
                Pump();
            }
            if (textWindow.IsVisible || program.History.Search("编辑后的文字").Length != 1 ||
                program.History.Search("第二行").Length != 0)
                throw new Exception("Saving the edited preview did not replace its history entry");

            var imageRow = list.Items.Cast<ListBoxItem>().Single(item => Find<Image>(item) != null);
            var imageWindow = OpenPreview(app, window, imageRow);
            var image = Find<Image>(imageWindow);
            for (int i = 0; i < 40 && image.Source == null; i++)
            {
                System.Threading.Thread.Sleep(25);
                Pump();
            }
            imageWindow.UpdateLayout();
            double width = image.Width;
            if (image.Source == null || !(width > 0)) throw new Exception("Image preview did not load");
            Wheel(imageWindow, 120);
            if (image.Width <= width) throw new Exception("Image wheel did not zoom");
            Wheel(imageWindow, -120);
            if (Math.Abs(image.Width - width) > 0.1) throw new Exception("Image zoom did not reverse");
            imageWindow.Close();
            Console.WriteLine("PASS: text and image double-click previews, full text and mouse wheel zoom");
            app.Shutdown();
        }
    }

    private static Window OpenPreview(Application app, Window owner, ListBoxItem row)
    {
        row.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Left)
            { RoutedEvent = Control.MouseDoubleClickEvent });
        return app.Windows.OfType<Window>().Single(window => window.Owner == owner);
    }

    private static void Wheel(Window window, int delta)
    {
        window.RaiseEvent(new MouseWheelEventArgs(Mouse.PrimaryDevice, Environment.TickCount, delta)
            { RoutedEvent = Mouse.PreviewMouseWheelEvent });
    }

    private static void Pump()
    {
        Dispatcher.CurrentDispatcher.Invoke(new Action(() => { }), DispatcherPriority.Background);
    }

    private static T Find<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T) return (T)root;
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var match = Find<T>(VisualTreeHelper.GetChild(root, i));
            if (match != null) return match;
        }
        return null;
    }

    private static System.Collections.Generic.IEnumerable<T> Descendants<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T) yield return (T)root;
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            foreach (var child in Descendants<T>(VisualTreeHelper.GetChild(root, i))) yield return child;
    }
}

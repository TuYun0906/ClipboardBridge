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

    private static void CheckClipboardFormats(string path)
    {
        string imagePath = Path.Combine(path, "copied.png");
        byte[] pixel = { 37, 82, 210, 255 };
        byte[] png = WindowsClipboard.ToFrame(System.Windows.Media.Imaging.BitmapSource.Create(
            1, 1, 96, 96, PixelFormats.Bgra32, null, pixel, 4)).Data;
        File.WriteAllBytes(imagePath, png);
        string secondImagePath = Path.Combine(path, "second.png");
        File.WriteAllBytes(secondImagePath, png);
        string textPath = Path.Combine(path, "ignored.txt");
        File.WriteAllText(textPath, "ignore");
        var drop = new DataObject(DataFormats.FileDrop, new[] { imagePath, textPath, secondImagePath });
        var captures = new System.Collections.Generic.List<object>();
        WindowsClipboard.ReadContent(drop, captures.Add);
        if (!captures.OfType<FileInfo>().Select(file => file.FullName).SequenceEqual(new[] { imagePath, secondImagePath })
                || captures.Count != 2)
            throw new Exception("FileDrop did not select both image files in order");
        foreach (object capture in captures) CheckPixel(WindowsClipboard.ToFrame(capture).Data, pixel);

        var legacy = System.Windows.Media.Imaging.BitmapSource.Create(
            1, 1, 96, 96, PixelFormats.Bgra32, null, new byte[] { 37, 82, 210, 0 }, 4);
        var mixed = new DataObject(DataFormats.Bitmap, legacy);
        mixed.SetData("PNG", new MemoryStream(png, false), false);
        captures.Clear();
        WindowsClipboard.ReadContent(mixed, captures.Add);
        CheckPixel(WindowsClipboard.ToFrame(captures.Single()).Data, pixel);

        captures.Clear();
        WindowsClipboard.ReadContent(new DataObject(DataFormats.Bitmap, legacy), captures.Add);
        CheckPixel(WindowsClipboard.ToFrame(captures.Single()).Data, pixel);
        captures.Clear();
        var internalImage = WindowsClipboard.ToClipboardData(new Frame(Wire.Image, new byte[Wire.IdSize], png));
        WindowsClipboard.ReadContent(internalImage, captures.Add);
        if (captures.Count != 0) throw new Exception("Received image was captured again");
        Console.WriteLine("PASS: clipboard image files, PNG preference and legacy bitmap alpha");
    }

    private static void CheckPixel(byte[] image, byte[] expected)
    {
        var decoded = new System.Windows.Media.Imaging.FormatConvertedBitmap(
            WindowsClipboard.DecodePreview(image, 8), PixelFormats.Bgra32, null, 0);
        byte[] actual = new byte[4];
        decoded.CopyPixels(actual, 4, 0);
        if (!actual.SequenceEqual(expected)) throw new Exception("Copied image pixels were lost");
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
        CheckClipboardFormats(path);
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
            var marked = WindowsClipboard.ToFrame(bitmap);
            Wire.MarkCompressed(marked.Id, 70, 100);
            program.History.Apply(marked, false);
            for (int i = 0; i < 53; i++)
                program.History.Apply(WindowsClipboard.ToFrame("分页检查 " + i), true);
            var query = Descendants<TextBox>(window).Single(box =>
                System.Windows.Automation.AutomationProperties.GetName(box) == "搜索历史");
            query.Text = "分页检查";
            window.UpdateLayout();
            var nextPage = Descendants<Button>(window).Single(button => Equals(button.Content, "下一页"));
            var previousPage = Descendants<Button>(window).Single(button => Equals(button.Content, "上一页"));
            if (list.Items.Count != 50 || !nextPage.IsEnabled || previousPage.IsEnabled)
                throw new Exception("History did not start with 50 records");
            nextPage.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            if (list.Items.Count != 3 || nextPage.IsEnabled || !previousPage.IsEnabled)
                throw new Exception("Next page did not contain the remainder");
            query.Text = "图片";
            window.UpdateLayout();
            if (previousPage.IsEnabled || !Descendants<TextBlock>(list).Any(label => label.Text == "已压缩至原70%"))
                throw new Exception("Image filter did not reset page or display the compression percentage");
            query.Text = "分页检查";
            var changeSize = Descendants<Button>(window).Single(button => Equals(button.Content, "每页 50 条"));
            for (int i = 0; i < 3; i++) changeSize.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            if (list.Items.Count != 25 || File.ReadAllText(Path.Combine(path, "history-page-size.txt")) != "25")
                throw new Exception("Page size is not applied and persisted");
            Console.WriteLine("PASS: default 50, next page, filter reset, saved page size and compression percentage");
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

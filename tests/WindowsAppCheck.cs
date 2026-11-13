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
        using (var program = new Program(app, path))
        {
            var window = app.Windows.OfType<MainWindow>().Single();
            window.Width = window.MinWidth;
            window.Height = window.MinHeight;
            window.UpdateLayout();
            var list = Find<ListBox>(window);
            var scroll = Find<ScrollViewer>(list);
            if (scroll.ScrollableWidth > 0.1) throw new Exception("History overflows horizontally");
            foreach (ListBoxItem item in list.Items)
            {
                var row = (Panel)item.Content;
                foreach (var button in row.Children.OfType<Button>())
                {
                    var rect = button.TransformToAncestor(scroll).TransformBounds(
                        new Rect(new Point(), button.RenderSize));
                    if (rect.Width < 61 || rect.Left < 0 || rect.Right > scroll.ViewportWidth + 0.1)
                        throw new Exception("History action outside the viewport");
                }
            }
            Console.WriteLine("PASS: both history actions fit on short and long rows at minimum window size");
            var textRow = list.Items.Cast<ListBoxItem>().Single(item => ((Panel)item.Content)
                .Children.OfType<TextBlock>().Any(label => label.Text.StartsWith("长")));
            var textWindow = OpenPreview(app, window, textRow);
            var text = Find<TextBox>(textWindow);
            if (text.Text != longText || !text.IsReadOnly) throw new Exception("Full text preview missing");
            double fontSize = text.FontSize;
            Wheel(textWindow, 120);
            if (text.FontSize <= fontSize) throw new Exception("Text wheel did not zoom");
            textWindow.Close();

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
}

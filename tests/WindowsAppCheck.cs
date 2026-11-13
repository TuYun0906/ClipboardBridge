using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

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
        File.WriteAllText(Path.Combine(path, "settings.txt"), "0110");
        var history = new ClipHistory(Path.Combine(path, "history"));
        history.Apply(WindowsClipboard.ToFrame(new string('长', 140)), true);
        history.Apply(WindowsClipboard.ToFrame("短文字"), true);
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
            app.Shutdown();
        }
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

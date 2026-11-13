using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

internal sealed class WindowsClipboard : IDisposable
{
    private const int ClipboardUpdated = 0x031D;
    private const string InternalFormat = "Clip.Bridge.Internal";
    private static readonly UTF8Encoding Utf8 = new UTF8Encoding(false, true);
    private readonly HwndSource window;
    private readonly DispatcherTimer retry;
    private readonly Action<object> submit;
    private int attempts;

    internal WindowsClipboard(Action<object> submit)
    {
        this.submit = submit;
        retry = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(45) };
        retry.Tick += delegate { Capture(); };
        window = new HwndSource(new HwndSourceParameters("Clipboard Bridge Listener")
        {
            ParentWindow = new IntPtr(-3), Width = 1, Height = 1, WindowStyle = 0
        });
        window.AddHook(OnMessage);
        if (!AddClipboardFormatListener(window.Handle))
        {
            window.Dispose();
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }
    }

    private IntPtr OnMessage(IntPtr handle, int message, IntPtr wparam, IntPtr lparam, ref bool handled)
    {
        if (message == ClipboardUpdated)
        {
            attempts = 0;
            retry.Start();
        }
        return IntPtr.Zero;
    }

    private void Capture()
    {
        try
        {
            IDataObject clipboard = Clipboard.GetDataObject();
            if (clipboard != null && !clipboard.GetDataPresent(InternalFormat, false))
            {
                var image = clipboard.GetData(DataFormats.Bitmap) as BitmapSource;
                if (image != null)
                {
                    ValidateDimensions(image);
                    submit(DetachedBitmap(image));
                }
                else
                {
                    var png = clipboard.GetData("PNG", false) as MemoryStream;
                    if (png != null && png.Length <= Wire.ImageLimit) submit(png.ToArray());
                    else
                    {
                        string text = clipboard.GetData(DataFormats.UnicodeText) as string;
                        if (!String.IsNullOrEmpty(text)) submit(text);
                    }
                }
            }
        }
        catch (ExternalException)
        {
            if (++attempts < 5) return;
        }
        catch (IOException) { }
        finally
        {
            if (attempts == 0 || attempts >= 5) retry.Stop();
        }
        retry.Stop();
    }

    internal static Frame ToFrame(object content)
    {
        string text = content as string;
        if (text != null)
        {
            if (text.Length == 0 || Utf8.GetByteCount(text) > Wire.TextLimit)
                throw new IOException("文字超过大小限制");
            return new Frame(Wire.Text, Guid.NewGuid().ToByteArray(), Utf8.GetBytes(text));
        }

        BitmapSource bitmap = content as BitmapSource;
        byte[] image = content as byte[];
        var file = content as FileInfo;
        if (file != null)
        {
            if (file.Length > Wire.ImageLimit) throw new IOException("图片超过大小限制");
            image = File.ReadAllBytes(file.FullName);
        }
        if (bitmap == null && image != null) bitmap = Decode(image);
        if (bitmap == null) throw new IOException("无法读取图片");
        ValidateDimensions(bitmap);
        using (var png = new MemoryStream())
        {
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            encoder.Save(png);
            if (png.Length > Wire.ImageLimit) throw new IOException("图片超过大小限制");
            return new Frame(Wire.Image, Guid.NewGuid().ToByteArray(), png.ToArray());
        }
    }

    internal static DataObject ToClipboardData(Frame frame)
    {
        if (frame.Kind == Wire.Text) return InternalText(Utf8.GetString(frame.Data));
        if (frame.Kind != Wire.Image) throw new IOException("不支持的剪贴板类型");
        BitmapSource image = Decode(frame.Data);
        var value = new DataObject();
        value.SetImage(image);
        if (frame.Data.Length > 8 && frame.Data[0] == 137 && frame.Data[1] == 80 &&
            frame.Data[2] == 78 && frame.Data[3] == 71)
            value.SetData("PNG", new MemoryStream(frame.Data, false), false);
        value.SetData(InternalFormat, "1", false);
        return value;
    }

    internal static DataObject InternalText(string text)
    {
        var data = new DataObject();
        data.SetText(text, TextDataFormat.UnicodeText);
        data.SetData(InternalFormat, "1", false);
        return data;
    }

    private static BitmapSource Decode(byte[] bytes)
    {
        if (bytes.Length == 0 || bytes.Length > Wire.ImageLimit) throw new IOException("图片超过大小限制");
        using (var input = new MemoryStream(bytes, false))
        {
            var decoder = BitmapDecoder.Create(input, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.None);
            if (decoder.Frames.Count == 0) throw new IOException("无法读取图片");
            BitmapFrame bitmap = decoder.Frames[0];
            ValidateDimensions(bitmap);
            return DetachedBitmap(bitmap);
        }
    }

    private static BitmapSource DetachedBitmap(BitmapSource source)
    {
        var bitmap = new WriteableBitmap(source);
        bitmap.Freeze();
        return bitmap;
    }

    private static void ValidateDimensions(BitmapSource bitmap)
    {
        if (bitmap.PixelWidth <= 0 || bitmap.PixelHeight <= 0 ||
            (long)bitmap.PixelWidth * bitmap.PixelHeight > 24000000L)
            throw new IOException("图片分辨率超过限制");
    }

    public void Dispose()
    {
        retry.Stop();
        RemoveClipboardFormatListener(window.Handle);
        window.Dispose();
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AddClipboardFormatListener(IntPtr window);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveClipboardFormatListener(IntPtr window);
}

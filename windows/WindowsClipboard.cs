using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
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

    private static byte[] NewId()
    {
        byte[] id = Guid.NewGuid().ToByteArray();
        long time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        for (int i = 5; i >= 0; i--, time >>= 8) id[i] = (byte)time;
        return id;
    }

    internal static Frame ToFrame(object content, bool compress = false)
    {
        string text = content as string;
        if (text != null)
        {
            if (text.Length == 0 || Utf8.GetByteCount(text) > Wire.TextLimit)
                throw new IOException("文字超过大小限制");
            return new Frame(Wire.Text, NewId(), Utf8.GetBytes(text));
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
        byte[] result;
        bool compressed = false;
        if (!compress) result = Encode(bitmap, new PngBitmapEncoder());
        else if (image != null && IsGif(image)) result = image;
        else
        {
            if (image != null) bitmap = OrientForCompression(bitmap, image);
            bool transparent = HasTransparency(bitmap);
            int longest = Math.Max(bitmap.PixelWidth, bitmap.PixelHeight);
            // Never upscale an image.
            BitmapSource reduced = bitmap;
            if (longest > 1920)
            {
                double scale = 1920.0 / longest;
                reduced = new TransformedBitmap(bitmap, new ScaleTransform(
                    Math.Max(1, bitmap.PixelWidth * scale) / bitmap.PixelWidth,
                    Math.Max(1, bitmap.PixelHeight * scale) / bitmap.PixelHeight));
            }
            result = Encode(reduced, transparent ? (BitmapEncoder)new PngBitmapEncoder()
                : new JpegBitmapEncoder { QualityLevel = 82 });
            byte[] baseline = image != null && IsStandardImage(image) ? image :
                Encode(bitmap, new PngBitmapEncoder());
            compressed = result.Length < baseline.Length;
            if (!compressed) result = baseline;
        }
        if (result.Length > Wire.ImageLimit) throw new IOException("图片超过大小限制");
        byte[] id = NewId();
        if (compressed) Wire.MarkCompressed(id);
        return new Frame(Wire.Image, id, result);
    }

    private static BitmapSource OrientForCompression(BitmapSource bitmap, byte[] image)
    {
        if (image.Length < 4 || image[0] != 255 || image[1] != 216) return bitmap;
        try
        {
            using (var input = new MemoryStream(image, false))
            {
                var jpeg = new JpegBitmapDecoder(input, BitmapCreateOptions.DelayCreation, BitmapCacheOption.None);
                var metadata = jpeg.Frames[0].Metadata as BitmapMetadata;
                object value = metadata == null ? null : metadata.GetQuery("/app1/ifd/{ushort=274}");
                int orientation = value is ushort ? (ushort)value : 1;
                if (orientation < 2 || orientation > 8) return bitmap;
                var transforms = new TransformGroup();
                if (orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7)
                    transforms.Children.Add(new ScaleTransform(-1, 1));
                if (orientation == 3 || orientation == 4) transforms.Children.Add(new RotateTransform(180));
                if (orientation == 6 || orientation == 7) transforms.Children.Add(new RotateTransform(90));
                if (orientation == 5 || orientation == 8) transforms.Children.Add(new RotateTransform(270));
                return new TransformedBitmap(bitmap, transforms);
            }
        }
        catch (NotSupportedException) { return bitmap; }
        catch (System.Runtime.InteropServices.COMException) { return bitmap; }
    }

    private static byte[] Encode(BitmapSource image, BitmapEncoder encoder)
    {
        using (var output = new MemoryStream())
        {
            encoder.Frames.Add(BitmapFrame.Create(image));
            encoder.Save(output);
            return output.ToArray();
        }
    }

    private static bool IsGif(byte[] data)
    {
        return data.Length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
    }

    private static bool IsStandardImage(byte[] data)
    {
        return data.Length >= 8 && ((data[0] == 137 && data[1] == 80 && data[2] == 78 && data[3] == 71) ||
            (data[0] == 255 && data[1] == 216 && data[2] == 255) ||
            (data[0] == 'B' && data[1] == 'M') || IsGif(data));
    }

    private static bool HasTransparency(BitmapSource bitmap)
    {
        if (bitmap.Palette != null)
        {
            foreach (var color in bitmap.Palette.Colors) if (color.A != 255) return true;
            return false;
        }
        var format = bitmap.Format;
        if (format != PixelFormats.Bgra32 && format != PixelFormats.Pbgra32 &&
            format != PixelFormats.Rgba64 && format != PixelFormats.Prgba64 &&
            format != PixelFormats.Rgba128Float && format != PixelFormats.Prgba128Float) return false;
        var rgba = new FormatConvertedBitmap(bitmap, PixelFormats.Bgra32, null, 0);
        var line = new byte[rgba.PixelWidth * 4];
        for (int y = 0; y < rgba.PixelHeight; y++)
        {
            rgba.CopyPixels(new Int32Rect(0, y, rgba.PixelWidth, 1), line, line.Length, 0);
            for (int x = 3; x < line.Length; x += 4) if (line[x] != 255) return true;
        }
        return false;
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

    internal static BitmapSource DecodePreview(byte[] bytes, int limit)
    {
        using (var input = new MemoryStream(bytes, false))
        {
            var metadata = BitmapDecoder.Create(input, BitmapCreateOptions.DelayCreation, BitmapCacheOption.None).Frames[0];
            int width = metadata.PixelWidth, height = metadata.PixelHeight;
            input.Position = 0;
            var image = new BitmapImage();
            image.BeginInit();
            image.StreamSource = input;
            image.CacheOption = BitmapCacheOption.OnLoad;
            if (Math.Max(width, height) > limit)
            {
                if (width >= height) image.DecodePixelWidth = limit;
                else image.DecodePixelHeight = limit;
            }
            image.EndInit();
            image.Freeze();
            return image;
        }
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

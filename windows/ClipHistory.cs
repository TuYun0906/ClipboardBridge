using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

internal sealed class ClipHistory
{
    internal sealed class Entry
    {
        internal string Path, Label, Text;
    }

    private readonly string directory;
    private readonly List<Entry> entries = new List<Entry>();
    internal volatile bool SaveImages;

    internal ClipHistory(string directory)
    {
        this.directory = directory;
        Directory.CreateDirectory(directory);
        foreach (string path in Directory.GetFiles(directory, "*.clip").OrderByDescending(Path.GetFileName))
        {
            try
            {
                var entry = Describe(path, Read(path));
                if (entry != null) entries.Add(entry);
            }
            catch (IOException) { }
        }
        Trim();
    }

    internal static Frame Read(string path)
    {
        using (var stream = File.OpenRead(path)) return Wire.Read(stream);
    }

    internal bool Add(Frame frame)
    {
        lock (entries)
        {
            if (frame.Kind == Wire.Image && !SaveImages) return false;
            string path = System.IO.Path.Combine(directory, DateTime.UtcNow.Ticks.ToString("D19") +
                "-" + Guid.NewGuid().ToString("N") + ".clip");
            using (var stream = File.Create(path + ".tmp")) Wire.Write(stream, frame);
            File.Move(path + ".tmp", path);
            entries.Insert(0, Describe(path, frame));
            Trim();
            return true;
        }
    }

    internal Entry[] Search(string query)
    {
        lock (entries)
            return entries.Where(entry => entry.Text.IndexOf(query.Trim(), StringComparison.OrdinalIgnoreCase) >= 0).ToArray();
    }

    private static Entry Describe(string path, Frame frame)
    {
        if (frame == null || (frame.Kind != Wire.Text && frame.Kind != Wire.Image)) return null;
        string text = frame.Kind == Wire.Text ? Encoding.UTF8.GetString(frame.Data) : "图片";
        string label = text.Replace('\r', ' ').Replace('\n', ' ');
        if (label.Length > 160) label = label.Substring(0, 160) + "…";
        return new Entry { Path = path, Text = text, Label = label };
    }

    private void Trim()
    {
        long bytes = 0;
        for (int i = 0; i < entries.Count;)
        {
            var file = new FileInfo(entries[i].Path);
            bytes += file.Exists ? file.Length : 0;
            if (i < 100 && bytes <= 64L * 1024 * 1024) { i++; continue; }
            try { file.Delete(); }
            catch (IOException) { }
            entries.RemoveAt(i);
        }
    }
}

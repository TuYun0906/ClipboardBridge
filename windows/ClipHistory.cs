using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

internal sealed class ClipHistory
{
    internal sealed class Entry
    {
        internal string Path, Label, Text, Key;
        internal byte Kind;
        internal DateTime? CopyTime;
    }

    private readonly string directory;
    private readonly Dictionary<string, Entry> entries = new Dictionary<string, Entry>();
    private readonly HashSet<string> deleted = new HashSet<string>();
    internal volatile bool SaveImages;

    internal ClipHistory(string directory)
    {
        this.directory = directory;
        Directory.CreateDirectory(directory);
        foreach (string path in Directory.GetFiles(directory, "*.deleted"))
            deleted.Add(Path.GetFileNameWithoutExtension(path));
        foreach (string path in Directory.GetFiles(directory, "*.clip"))
        {
            try
            {
                Frame frame = Read(path);
                if (frame == null || (frame.Kind != Wire.Text && frame.Kind != Wire.Image)) continue;
                string key = Key(frame.Id);
                if (deleted.Contains(key) || entries.ContainsKey(key)) { File.Delete(path); continue; }
                entries.Add(key, Describe(path, key, frame));
            }
            catch (IOException) { }
        }
        foreach (var group in entries.Values.Where(e => e.Kind == Wire.Text).GroupBy(e => e.Text).ToArray())
            foreach (var old in group.OrderByDescending(e => Order(e.Key), StringComparer.Ordinal).Skip(1)) Delete(old.Key);
        Trim();
    }

    internal static string Key(byte[] id) { return BitConverter.ToString(id).Replace("-", "").ToLowerInvariant(); }

    private static long? CopyTimestamp(string key)
    {
        long stamp = Convert.ToInt64(key.Substring(0, 12), 16);
        return stamp >= 1577836800000L && stamp <= 4102444800000L ? (long?)stamp : null;
    }

    private static string Order(string key) { return (CopyTimestamp(key).HasValue ? "1" : "0") + key; }

    internal static Frame Read(string path)
    {
        using (var stream = File.OpenRead(path)) return Wire.Read(stream);
    }

    internal Frame[] Apply(Frame frame, bool local)
    {
        return Apply(frame, local, true);
    }

    private Frame[] Apply(Frame frame, bool local, bool trimAfter)
    {
        lock (entries)
        {
            string key = Key(frame.Id);
            if (frame.Kind == Wire.Delete)
                return Delete(key) ? new[] { frame } : new Frame[0];
            if (deleted.Contains(key) || entries.ContainsKey(key) ||
                (frame.Kind == Wire.Image && local && !SaveImages)) return new Frame[0];

            string path = Path.Combine(directory, key + ".clip");
            var entry = Describe(path, key, frame);
            var changes = new List<Frame>();
            var duplicate = frame.Kind == Wire.Text
                ? entries.Values.FirstOrDefault(e => e.Kind == Wire.Text && e.Text == entry.Text) : null;
            if (duplicate != null && String.CompareOrdinal(Order(duplicate.Key), Order(key)) > 0)
            {
                if (Delete(key)) changes.Add(Tombstone(key));
                return changes.ToArray();
            }

            string pending = path + ".tmp";
            try
            {
                using (var stream = File.Create(pending)) Wire.Write(stream, frame);
                File.Move(pending, path);
            }
            finally { File.Delete(pending); }
            entries.Add(key, entry);
            changes.Add(new Frame(frame.Kind == Wire.Text ? Wire.HistoryText : Wire.HistoryImage, frame.Id, frame.Data));
            if (duplicate != null && Delete(duplicate.Key)) changes.Add(Tombstone(duplicate.Key));
            if (trimAfter) changes.AddRange(Trim());
            return changes.ToArray();
        }
    }

    internal Frame[] Edit(string key, Frame replacement)
    {
        lock (entries)
        {
            Entry previous;
            if (!entries.TryGetValue(key, out previous) || previous.Kind != Wire.Text ||
                previous.Text == Encoding.UTF8.GetString(replacement.Data)) return new Frame[0];
            Frame removal = Tombstone(key);
            byte[] id = (byte[])replacement.Id.Clone();
            Array.Copy(removal.Id, id, 6);
            string editedKey = Key(id);
            if (entries.ContainsKey(editedKey) || deleted.Contains(editedKey)) throw new IOException("无法生成编辑记录");
            var edited = new Frame(Wire.Text, id, replacement.Data);
            var changes = new List<Frame>(Apply(edited, true, false));
            if (Delete(key)) changes.Insert(0, removal);
            changes.AddRange(Trim());
            return changes.ToArray();
        }
    }

    internal Entry[] Search(string query, DateTime? from = null, DateTime? through = null)
    {
        string term = query.Trim();
        lock (entries)
            return entries.Values.OrderByDescending(e => Order(e.Key), StringComparer.Ordinal)
                .Where(e => (!from.HasValue || e.CopyTime >= from.Value.Date) &&
                    (!through.HasValue || e.CopyTime.HasValue && e.CopyTime.Value.Date <= through.Value.Date) &&
                    e.Text.IndexOf(term, StringComparison.OrdinalIgnoreCase) >= 0).ToArray();
    }

    internal IEnumerable<Frame> Replay()
    {
        string[] tombstones;
        Entry[] snapshot;
        lock (entries) { tombstones = deleted.ToArray(); snapshot = Search(""); }
        foreach (string key in tombstones) yield return Tombstone(key);
        foreach (Entry entry in snapshot)
        {
            Frame frame;
            lock (entries) frame = entries.ContainsKey(entry.Key) ? Read(entry.Path) : null;
            if (frame != null) yield return new Frame(frame.Kind == Wire.Text ? Wire.HistoryText : Wire.HistoryImage, frame.Id, frame.Data);
        }
    }

    internal static Frame Tombstone(string key)
    {
        var id = new byte[Wire.IdSize];
        for (int i = 0; i < id.Length; i++) id[i] = Convert.ToByte(key.Substring(i * 2, 2), 16);
        return new Frame(Wire.Delete, id, new byte[0]);
    }

    private bool Delete(string key)
    {
        if (deleted.Contains(key)) return false;
        using (File.Create(Path.Combine(directory, key + ".deleted"))) { }
        deleted.Add(key);
        Entry entry;
        if (entries.TryGetValue(key, out entry))
        {
            entries.Remove(key);
            try { File.Delete(entry.Path); } catch (IOException) { }
        }
        return true;
    }

    private static Entry Describe(string path, string key, Frame frame)
    {
        string text = frame.Kind == Wire.Text ? Encoding.UTF8.GetString(frame.Data) : "图片";
        string label = text.Replace('\r', ' ').Replace('\n', ' ');
        if (label.Length > 160) label = label.Substring(0, 160) + "…";
        long? stamp = CopyTimestamp(key);
        return new Entry
        {
            Path = path, Key = key, Text = text, Label = label, Kind = frame.Kind,
            CopyTime = stamp.HasValue ? DateTimeOffset.FromUnixTimeMilliseconds(stamp.Value).LocalDateTime : (DateTime?)null
        };
    }

    private IEnumerable<Frame> Trim()
    {
        long bytes = 0;
        int count = 0;
        var changes = new List<Frame>();
        foreach (Entry entry in entries.Values.OrderByDescending(e => Order(e.Key), StringComparer.Ordinal).ToArray())
        {
            bytes += new FileInfo(entry.Path).Length;
            if (++count <= 100 && bytes <= 64L * 1024 * 1024) continue;
            if (Delete(entry.Key)) changes.Add(Tombstone(entry.Key));
        }
        return changes;
    }
}

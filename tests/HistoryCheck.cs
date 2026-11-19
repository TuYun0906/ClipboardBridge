using System;
using System.IO;
using System.Linq;
using System.Text;

static class HistoryCheck
{
    static Frame Text(long time)
    {
        byte[] id = new byte[16];
        id[15] = (byte)time;
        for (int i = 5; i >= 0; i--, time >>= 8) id[i] = (byte)time;
        return new Frame(Wire.Text, id, Encoding.UTF8.GetBytes("完全相同的文字"));
    }

    static void Main(string[] args)
    {
        var first = new ClipHistory(Path.Combine(args[0], "first"));
        var second = new ClipHistory(Path.Combine(args[0], "second"));
        Frame older = Text(1760000000000);
        Frame newer = Text(1760000000010);
        first.Apply(older, true);
        second.Apply(newer, true);
        foreach (Frame frame in first.Replay()) Receive(second, frame);
        foreach (Frame frame in second.Replay()) Receive(first, frame);
        string key = ClipHistory.Key(newer.Id);
        if (first.Search("文字").Single().Key != key || second.Search("文字").Single().Key != key)
            throw new Exception("Offline duplicate did not converge to newest");
        first.Apply(ClipHistory.Tombstone(key), true);
        first = new ClipHistory(Path.Combine(args[0], "first"));
        foreach (Frame frame in first.Replay()) Receive(second, frame);
        second.Apply(newer, false);
        if (first.Search("").Length != 0 || second.Search("").Length != 0)
            throw new Exception("Deleted history was restored");
        second.Apply(Text(1760000000020), true);
        if (second.Search("").Length != 1) throw new Exception("A new copy must be accepted");
        Console.WriteLine("PASS: offline merge, newest exact duplicate, durable deletion, and subsequent copy");
        Frame original = Text(1760000000030);
        Frame revised = new Frame(Wire.Text, Text(1760000000040).Id, Encoding.UTF8.GetBytes("编辑后\n第二行"));
        first.Apply(original, true);
        var changes = first.Edit(ClipHistory.Key(original.Id), revised);
        if (changes.Length != 2 || first.Search("").Single().Text != "编辑后\n第二行")
            throw new Exception("Edit did not replace the original");
        second.Apply(original, false);
        foreach (Frame change in changes) Receive(second, change);
        first = new ClipHistory(Path.Combine(args[0], "first"));
        foreach (Frame replay in first.Replay()) Receive(second, replay);
        second.Apply(original, false);
        if (second.Search("完全相同").Any() || second.Search("第二行").Single().Text != "编辑后\n第二行")
            throw new Exception("History edit did not survive replay");
        if (first.Edit(ClipHistory.Key(original.Id), revised).Length != 0)
            throw new Exception("A stale editor recreated a removed entry");
        Console.WriteLine("PASS: edit replaces the old ID and survives offline replay");

        var dated = new ClipHistory(Path.Combine(args[0], "dates"));
        var day = new DateTime(2025, 10, 9);
        long start = new DateTimeOffset(day).ToUnixTimeMilliseconds();
        long end = new DateTimeOffset(day.AddDays(1)).ToUnixTimeMilliseconds();
        dated.Apply(DatedText(start - 1, "日期前"), true);
        var firstOnDay = DatedText(start, "日期内开始");
        dated.Apply(firstOnDay, true);
        dated.Apply(DatedText(end - 1, "日期内结束"), true);
        dated.Apply(DatedText(end, "日期后"), true);
        dated.Apply(DatedText(0, "日期未知"), true);
        dated.Edit(ClipHistory.Key(firstOnDay.Id), DatedText(end + 100, "日期内编辑后"));
        var selected = dated.Search("日期内", day, day);
        if (selected.Length != 2 || selected[1].Text != "日期内编辑后" ||
            selected[1].CopyTime != day || dated.Search("", day, day).Length != 2 ||
            dated.Search("", null, day).Length != 3 || dated.Search("", day, null).Length != 3 ||
            dated.Search("日期未知").Single().CopyTime != null)
            throw new Exception("Date filtering or original copy timestamp is incorrect");
        Console.WriteLine("PASS: inclusive local dates, keyword intersection, unknown time, and original time after edit");

        string imagesPath = Path.Combine(args[0], "images");
        var images = new ClipHistory(imagesPath);
        var oldImage = new Frame(Wire.Image, Text(start).Id, new byte[] { 1, 2, 3 });
        var newImage = new Frame(Wire.Image, Text(start + 1).Id, new byte[] { 1, 2, 3 });
        var different = new Frame(Wire.Image, Text(start + 2).Id, new byte[] { 1, 2, 4 });
        images.Apply(oldImage, false);
        images.Apply(different, false);
        var imageChanges = images.Apply(newImage, false);
        images = new ClipHistory(imagesPath);
        images.Apply(oldImage, false);
        if (!imageChanges.Any(frame => frame.Kind == Wire.Delete && frame.Id.SequenceEqual(oldImage.Id)) ||
            images.Search("").Length != 2 || !images.Search("").Any(entry => entry.Key == ClipHistory.Key(newImage.Id)))
            throw new Exception("Identical images did not converge to the newest record");
        File.Delete(Path.Combine(imagesPath, ClipHistory.Key(oldImage.Id) + ".deleted"));
        using (var saved = File.Create(Path.Combine(imagesPath, ClipHistory.Key(oldImage.Id) + ".clip")))
            Wire.Write(saved, oldImage);
        images = new ClipHistory(imagesPath);
        images.Apply(DatedText(start + 3, "文字图片"), true);
        if (images.Search("图片", day, day, Wire.Image).Length != 2 ||
            images.Search("图片", day, day, Wire.Text).Single().Text != "文字图片" ||
            !images.Replay().Any(frame => frame.Kind == Wire.Delete && frame.Id.SequenceEqual(oldImage.Id)))
            throw new Exception("History migration or content type filter failed");
        Console.WriteLine("PASS: exact image deduplication, durable deletion, and content type filtering");
        CheckRetention(args[0], start);
    }

    static void CheckRetention(string root, long start)
    {
        const int textCount = 125;
        const int imageCount = 56;
        string leftPath = Path.Combine(root, "retention-left");
        var left = new ClipHistory(leftPath);
        var right = new ClipHistory(Path.Combine(root, "retention-right"));
        for (int i = 0; i < textCount; i++)
            left.Apply(DatedText(start + i, "retained text " + i), true);
        for (int i = 0; i < imageCount; i++)
        {
            // Exceed the old 64 MiB quota with three otherwise valid-sized payloads.
            byte[] bytes = new byte[i < 53 ? 1 : 22 * 1024 * 1024];
            bytes[0] = (byte)i;
            Frame frame = new Frame(Wire.Image, Text(start + textCount + i).Id, bytes);
            if (i % 2 == 0) left.Apply(frame, false);
            else right.Apply(frame, false);
        }
        foreach (Frame frame in right.Replay()) Receive(left, frame);
        foreach (Frame frame in left.Replay()) Receive(right, frame);
        left = new ClipHistory(leftPath);
        var expected = Enumerable.Range(6, 50).Reverse()
            .Select(i => ClipHistory.Key(Text(start + textCount + i).Id)).ToArray();
        foreach (ClipHistory history in new[] { left, right })
        {
            if (history.Search("", kind: Wire.Text).Length != textCount ||
                !history.Search("", kind: Wire.Image).Select(e => e.Key).SequenceEqual(expected))
                throw new Exception("Retention should keep all distinct text and the newest 50 images after sync");
            var evicted = new Frame(Wire.Image, Text(start + textCount).Id, new byte[] { 0 });
            if (history.Apply(evicted, false).Length != 0)
                throw new Exception("An evicted image came back after replay");
        }
        Console.WriteLine("PASS: unlimited text, newest 50 images, and eviction replay");
    }

    static Frame DatedText(long time, string text)
    {
        return new Frame(Wire.Text, Text(time).Id, Encoding.UTF8.GetBytes(text));
    }

    static void Receive(ClipHistory history, Frame frame)
    {
        if (frame.Kind == Wire.HistoryText) frame = new Frame(Wire.Text, frame.Id, frame.Data);
        else if (frame.Kind == Wire.HistoryImage) frame = new Frame(Wire.Image, frame.Id, frame.Data);
        history.Apply(frame, false);
    }
}

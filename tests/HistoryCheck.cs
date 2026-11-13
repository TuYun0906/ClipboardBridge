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
    }

    static Frame DatedText(long time, string text)
    {
        return new Frame(Wire.Text, Text(time).Id, Encoding.UTF8.GetBytes(text));
    }

    static void Receive(ClipHistory history, Frame frame)
    {
        history.Apply(frame.Kind == Wire.HistoryText ? new Frame(Wire.Text, frame.Id, frame.Data) : frame, false);
    }
}

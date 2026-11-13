using System;
using System.IO;
using System.Linq;
using System.Text;

static class HistoryCheck
{
    static Frame Text(long time)
    {
        byte[] id = new byte[16];
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
    }

    static void Receive(ClipHistory history, Frame frame)
    {
        history.Apply(frame.Kind == Wire.HistoryText ? new Frame(Wire.Text, frame.Id, frame.Data) : frame, false);
    }
}

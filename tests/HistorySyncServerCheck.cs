using System;
using System.IO;
using System.Net;
using System.Text;

internal static class HistorySyncServerCheck
{
    private static void Main(string[] args)
    {
        using (var identity = Identity.Open(Path.Combine(args[0], "identity.bin")))
        {
            var history = new ClipHistory(Path.Combine(args[0], "history")) { SaveImages = true };
            SyncServer server = null;
            Action<Frame, Action<Frame>> receive = (frame, relay) =>
            {
                // The peer uses a live message to ask the Windows process to perform its own edit.
                if (frame.Kind == Wire.Text && frame.Id[15] == 10)
                {
                    var entry = history.Search("离线编辑")[0];
                    var id = new byte[Wire.IdSize];
                    id[15] = 4;
                    var edited = new Frame(Wire.Text, id, Encoding.UTF8.GetBytes("电脑编辑"));
                    foreach (Frame change in history.Edit(entry.Key, edited)) server.Publish(change);
                    return;
                }
                if (frame.Kind == Wire.HistoryText) frame = new Frame(Wire.Text, frame.Id, frame.Data);
                foreach (Frame change in history.Apply(frame, false)) relay(change);
            };
            using (server = new SyncServer(IPAddress.Loopback, 0, identity, receive, connected => { }, history.Replay))
            {
                Console.WriteLine(server.Start() + " " + identity.PairingCode);
                Console.ReadLine();
            }
        }
    }
}

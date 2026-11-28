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
                if (frame.Kind == Wire.Text && frame.Id[15] == 11)
                {
                    foreach (byte kind in new[] { Wire.Text, Wire.Image })
                    {
                        byte[] outgoingId = new byte[Wire.IdSize];
                        outgoingId[15] = (byte)(30 + kind);
                        var outgoing = new Frame(kind, outgoingId, Encoding.UTF8.GetBytes("电脑手动发送"));
                        outgoing.Source = "测试电脑";
                        outgoing.Completion = success =>
                        {
                            if (success)
                                server.Publish(new Frame(Wire.HistoryText, outgoingId, Encoding.UTF8.GetBytes("电脑收到回执"))
                                    .WithSource("测试电脑"));
                        };
                        server.Submit(outgoing);
                    }
                    return;
                }
                if (frame.Kind == Wire.HistoryText || frame.Kind == Wire.HistoryImage)
                    frame = new Frame(frame.Kind == Wire.HistoryText ? Wire.Text : Wire.Image, frame.Id, frame.Data)
                        .WithSource(frame.Source);
                foreach (Frame change in history.Apply(frame, false))
                    if (!frame.SaveImage || change.Kind == Wire.Delete) relay(change);
            };
            var alternateAddress = IPAddress.Parse("127.0.0.2");
            using (server = new SyncServer(alternateAddress, 0, identity, receive, connected => { }, history.Replay,
                includeLoopback: true, inventory: history.ImageInventory))
            {
                server.ConfigureDevices("测试电脑", names => { });
                int port = server.Start();
                using (var probe = new System.Net.Sockets.TcpClient()) probe.Connect(alternateAddress, port);
                Console.WriteLine(port + " " + identity.PairingCode);
                Console.ReadLine();
            }
        }
    }
}

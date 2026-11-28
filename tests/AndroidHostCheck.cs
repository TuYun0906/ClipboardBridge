using System;
using System.Collections.Concurrent;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;

internal static class AndroidHostCheck
{
    private static void Main(string[] args)
    {
        try { Run(args); }
        catch (Exception error) { Console.Error.WriteLine(error.GetType().Name + ": " + error.Message); Environment.ExitCode = 1; }
    }

    private static void Run(string[] args)
    {
        int port = int.Parse(args[0]);
        byte[] pin;
        using (var sha = SHA256.Create()) pin = sha.ComputeHash(System.IO.File.ReadAllBytes(args[1]));
        byte[] key = new byte[32], pair = new byte[64];
        for (int i = 0; i < key.Length; i++) key[i] = 42;
        using (var probe = new TcpClient())
        {
            try
            {
                using (TlsLink.Connect(probe, "127.0.0.1", port, pin, new byte[32]))
                    throw new Exception("Host accepted the wrong pairing key");
            }
            catch (AuthenticationException) { }
        }
        Array.Copy(pin, pair, 32);
        Array.Copy(key, 0, pair, 32, 32);
        var received = new BlockingCollection<Frame>();
        using (var client = new SyncClient(new Uri("tcp://127.0.0.1:" + port), pair,
            (frame, publish) => received.Add(frame), connected => {}, () => new Frame[0], () => Make(Wire.Image, 9, "").Id))
        {
            client.ConfigureDevices("Windows 客户端", names => { });
            client.Start();
            Check(received, Wire.HistoryText, "host history");
            if (!client.RefreshHistory()) throw new Exception("Android host refresh request was rejected");
            Check(received, Wire.HistoryText, "host history");
            var manualText = Make(Wire.Text, 1, "Windows copy");
            var textAck = new System.Threading.ManualResetEventSlim();
            bool textDelivered = false;
            manualText.Completion = success => { textDelivered = success; textAck.Set(); };
            client.Submit(manualText);
            for (byte i = 11; i <= 13; i++)
            {
                Frame image = Make(Wire.Image, i, "image " + i);
                if (i == 11) image = new Frame(image.Kind, image.Id, image.Data, true);
                if (i == 12) Wire.MarkCompressed(image.Id, 70, 100);
                client.Submit(image);
            }
            if (!textAck.Wait(5000) || !textDelivered) throw new Exception("Missing Windows client manual text ACK");
            for (int i = 21; i <= 23; i++) Check(received, Wire.Image, "host image " + i);
            client.Publish(Make(Wire.Delete, 8, ""));
            Check(received, Wire.Text, "Android copy");
            client.Publish(Make(Wire.HistoryText, 2, "edited history"));
            Check(received, Wire.Text, "edit received");
            client.Publish(Make(Wire.HistoryText, 7, "test done"));
            Check(received, Wire.HistoryText, "test done");
        }
    }

    private static Frame Make(byte kind, byte firstId, string text)
    {
        var id = new byte[Wire.IdSize];
        id[0] = firstId;
        return new Frame(kind, id, Encoding.UTF8.GetBytes(text));
    }

    private static void Check(BlockingCollection<Frame> received, byte kind, string text)
    {
        Frame frame;
        do
        {
            if (!received.TryTake(out frame, 8000)) throw new Exception("Missing frame " + kind + ": " + text);
        } while (frame.Kind == Wire.Delete);
        if (frame.Kind != kind || Encoding.UTF8.GetString(frame.Data) != text ||
            (kind == Wire.Image && frame.Source != "Android 主机") ||
            (kind == Wire.Image && frame.SaveImage != (text == "host image 21")) ||
            (kind == Wire.Image && Wire.CompressionPercent(frame.Id) != (text == "host image 22" ? 70 : -1)))
            throw new Exception("Expected " + text + "; got " + frame.Kind + ": " + Encoding.UTF8.GetString(frame.Data));
    }
}

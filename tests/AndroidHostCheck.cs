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
            (frame, publish) => received.Add(frame), connected => {}, () => new Frame[0]))
        {
            client.Start();
            Check(received, Wire.HistoryText, "host history");
            client.Submit(Make(Wire.Text, 1, "Windows copy"));
            client.Publish(Make(Wire.Delete, 8, ""));
            Check(received, Wire.Text, "Android copy");
            client.Publish(Make(Wire.HistoryText, 2, "edited history"));
            Check(received, Wire.Text, "edit received");
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
            if (!received.TryTake(out frame, 8000)) throw new Exception("Missing frame " + kind);
        } while (frame.Kind == Wire.Delete);
        if (frame.Kind != kind || Encoding.UTF8.GetString(frame.Data) != text)
            throw new Exception("Unexpected frame " + frame.Kind);
    }
}

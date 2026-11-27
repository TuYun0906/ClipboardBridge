using System;
using System.IO;
using System.Linq;
using System.Text;

static class ProtocolCheck
{
    static void Main(string[] args)
    {
        byte[] id = Enumerable.Range(0, 16).Select(i => (byte)i).ToArray();
        byte[] text = Encoding.UTF8.GetBytes("跨端剪贴板\n🙂");
        byte[] image = { 137, 80, 78, 71, 0, 255 };
        var imageId = (byte[])id.Clone();
        Wire.MarkCompressedSize(imageId, 19);
        if (args[0] == "write")
        {
            using (var stream = File.Create(args[1]))
            {
                Wire.Write(stream, new Frame(Wire.Text, id, text));
                Wire.Write(stream, new Frame(Wire.Image, imageId, image));
                Wire.Write(stream, new Frame(Wire.Ack, id, new byte[0]));
            }
            return;
        }
        using (var stream = File.OpenRead(args[1]))
        {
            Check(Wire.Read(stream), Wire.Text, id, text);
            Frame received = Wire.Read(stream);
            Check(received, Wire.Image, imageId, image);
            if (Wire.CompressionLabel(received.Id, received.Data.Length) != "已压缩至原31.58%")
                throw new Exception("Incorrect compression ratio");
            Check(Wire.Read(stream), Wire.Ack, id, new byte[0]);
            if (Wire.Read(stream) != null) throw new Exception("Expected EOF");
        }
        Reject(new byte[] { Wire.Text });
        var huge = new byte[21];
        huge[0] = Wire.Image;
        huge[17] = 0x7f;
        Reject(huge);
        huge[0] = 0xff;
        Reject(huge);
        var negative = new byte[21];
        negative[0] = Wire.ImageInventory;
        for (int i = 17; i < 20; i++) negative[i] = 0xff;
        negative[20] = 0xf0;
        Reject(negative);
    }

    static void Check(Frame frame, byte kind, byte[] id, byte[] data)
    {
        if (frame.Kind != kind || !frame.Id.SequenceEqual(id) || !frame.Data.SequenceEqual(data))
            throw new Exception("Frame mismatch");
    }

    static void Reject(byte[] bytes)
    {
        try { Wire.Read(new MemoryStream(bytes)); }
        catch (IOException) { return; }
        throw new Exception("Malformed frame accepted");
    }
}

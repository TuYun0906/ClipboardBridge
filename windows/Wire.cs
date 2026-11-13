using System;
using System.IO;

internal sealed class Frame
{
    internal readonly byte Kind;
    internal readonly byte[] Id;
    internal readonly byte[] Data;

    internal Frame(byte kind, byte[] id, byte[] data)
    {
        Kind = kind;
        Id = id;
        Data = data;
    }
}

internal static class Wire
{
    internal const byte Auth = 1, Text = 2, Image = 3, Ack = 4, HistoryText = 5, HistoryImage = 6, Delete = 7;
    internal const int IdSize = 16, KeySize = 32;
    internal const int TextLimit = 1024 * 1024, ImageLimit = 24 * 1024 * 1024;

    internal static Frame Read(Stream input)
    {
        int kind = input.ReadByte();
        if (kind == -1) return null;
        byte[] id = ReadExactly(input, IdSize);
        byte[] size = ReadExactly(input, 4);
        int count = (size[0] << 24) | (size[1] << 16) | (size[2] << 8) | size[3];
        ValidateLength(kind, count);
        return new Frame((byte)kind, id, ReadExactly(input, count));
    }

    internal static void Write(Stream output, Frame frame)
    {
        int count = frame.Data.Length;
        ValidateLength(frame.Kind, count);
        if (frame.Id.Length != IdSize) throw new IOException("Invalid message ID");

        byte[] header = new byte[1 + IdSize + 4];
        header[0] = frame.Kind;
        Buffer.BlockCopy(frame.Id, 0, header, 1, IdSize);
        for (int i = 0; i < 4; i++) header[1 + IdSize + i] = (byte)(count >> (24 - 8 * i));
        output.Write(header, 0, header.Length);
        output.Write(frame.Data, 0, count);
        output.Flush();
    }

    private static byte[] ReadExactly(Stream input, int count)
    {
        byte[] data = new byte[count];
        int offset = 0;
        while (offset < count)
        {
            int read = input.Read(data, offset, count - offset);
            if (read == 0) throw new EndOfStreamException("Truncated message");
            offset += read;
        }
        return data;
    }

    private static void ValidateLength(int kind, int count)
    {
        bool valid;
        switch (kind)
        {
            case Auth: valid = count == KeySize; break;
            case Delete:
            case Ack: valid = count == 0; break;
            case HistoryText:
            case Text: valid = count > 0 && count <= TextLimit; break;
            case HistoryImage:
            case Image: valid = count > 0 && count <= ImageLimit; break;
            default: valid = false; break;
        }
        if (!valid) throw new IOException("Invalid message type or length");
    }
}

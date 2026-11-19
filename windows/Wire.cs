using System;
using System.IO;

internal sealed class Frame
{
    internal readonly byte Kind;
    internal readonly byte[] Id;
    internal readonly byte[] Data;
    internal readonly bool SaveImage;

    internal Frame(byte kind, byte[] id, byte[] data, bool saveImage = false)
    {
        Kind = kind;
        Id = id;
        Data = data;
        SaveImage = saveImage;
    }
}

internal static class Wire
{
    internal const byte Auth = 1, Text = 2, Image = 3, Ack = 4, HistoryText = 5, HistoryImage = 6, Delete = 7;
    internal const byte ImageInventory = 8, SavedImage = 9;
    private static readonly byte[] SyncVersion = {67, 66, 73, 77, 71, 2, 117, 57};

    internal static void OfferImageSync(byte[] id) { Array.Copy(SyncVersion, id, SyncVersion.Length); }

    internal static bool OffersImageSync(byte[] id)
    {
        for (int i = 0; i < SyncVersion.Length; i++) if (id[i] != SyncVersion[i]) return false;
        return true;
    }

    internal static byte[] AcceptImageSync(byte[] id)
    {
        var reply = (byte[])id.Clone();
        reply[15] ^= 1;
        return reply;
    }

    internal const int IdSize = 16, KeySize = 32;
    // IDs carry image provenance through live sync, history replay, and older peers.
    private static readonly byte[] CompressedMark = { 0xC1, 0x19, 0x3B, 0xA6, 0x5D };
    internal static void MarkCompressed(byte[] id) { Array.Copy(CompressedMark, 0, id, 6, CompressedMark.Length); }
    private static readonly byte[] RatioMark = { 0xC1, 0x19, 0x3B, 0xA7, 0x5E };

    internal static void MarkCompressed(byte[] id, int remainingBytes, int originalBytes)
    {
        Array.Copy(RatioMark, 0, id, 6, RatioMark.Length);
        id[11] = (byte)Math.Max(1, Math.Min(99, (100L * remainingBytes + originalBytes / 2) / originalBytes));
    }

    // -1 means an original image, 0 means an older compressed image with no known ratio.
    internal static int CompressionPercent(byte[] id)
    {
        if (HasMark(id, RatioMark) && id[11] >= 1 && id[11] <= 99) return id[11];
        return HasMark(id, CompressedMark) ? 0 : -1;
    }

    internal static bool IsCompressed(byte[] id) { return CompressionPercent(id) >= 0; }

    private static bool HasMark(byte[] id, byte[] mark)
    {
        for (int i = 0; i < mark.Length; i++) if (id[6 + i] != mark[i]) return false;
        return true;
    }

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
            case ImageInventory: valid = count >= 0 && count <= 50 * IdSize && count % IdSize == 0; break;
            case SavedImage:
            case HistoryImage:
            case Image: valid = count > 0 && count <= ImageLimit; break;
            default: valid = false; break;
        }
        if (!valid) throw new IOException("Invalid message type or length");
    }
}

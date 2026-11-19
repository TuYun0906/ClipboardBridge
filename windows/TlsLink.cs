using System;
using System.IO;
using System.Collections.Generic;
using System.Linq;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

internal sealed class TlsLink : IDisposable
{
    private readonly TcpClient socket;
    private readonly SslStream stream;
    private readonly object sendLock = new object();
    private readonly HashSet<string> knownImages = new HashSet<string>();
    private bool imageSync;
    private readonly Queue<Frame> acknowledgements = new Queue<Frame>();
    private bool ackWriterScheduled;

    private TlsLink(TcpClient socket, SslStream stream)
    {
        this.socket = socket;
        this.stream = stream;
    }

    internal static TlsLink Accept(TcpClient socket, X509Certificate2 certificate, byte[] key,
        Func<byte[]> inventory = null)
    {
        try
        {
            socket.NoDelay = true;
            socket.ReceiveTimeout = 6000;
            socket.SendTimeout = 6000;
            var stream = new SslStream(socket.GetStream(), false);
            var link = new TlsLink(socket, stream);
            try
            {
                stream.AuthenticateAsServer(certificate, false, SslProtocols.Tls12, false);
                Frame hello = link.Read();
                if (hello == null || hello.Kind != Wire.Auth || !EqualKey(key, hello.Data))
                    throw new AuthenticationException("Pairing key mismatch");
                bool upgraded = inventory != null && Wire.OffersImageSync(hello.Id);
                link.Send(new Frame(Wire.Ack, upgraded ? Wire.AcceptImageSync(hello.Id) : hello.Id, new byte[0]));
                if (upgraded)
                {
                    link.Send(new Frame(Wire.ImageInventory, hello.Id, inventory()));
                    link.ReadInventory();
                    link.imageSync = true;
                }
                socket.ReceiveTimeout = 0;
                return link;
            }
            catch
            {
                link.Dispose();
                throw;
            }
        }
        catch
        {
            socket.Close();
            throw;
        }
    }

    internal static TlsLink Connect(TcpClient socket, string host, int port, byte[] pin, byte[] key,
        Func<byte[]> inventory = null)
    {
        var connected = socket.BeginConnect(host, port, null, null);
        using (var wait = connected.AsyncWaitHandle)
        {
            if (!wait.WaitOne(6000)) throw new IOException("Connection timed out");
            socket.EndConnect(connected);
        }
        socket.NoDelay = true;
        socket.ReceiveTimeout = 6000;
        socket.SendTimeout = 6000;
        var stream = new SslStream(socket.GetStream(), false, (sender, certificate, chain, errors) =>
        {
            if (certificate == null) return false;
            using (var leaf = new X509Certificate2(certificate))
            using (var sha = SHA256.Create())
                return DateTime.Now >= leaf.NotBefore && DateTime.Now <= leaf.NotAfter &&
                    EqualKey(pin, sha.ComputeHash(leaf.RawData));
        });
        var link = new TlsLink(socket, stream);
        try
        {
            stream.AuthenticateAsClient(host, new X509CertificateCollection(), SslProtocols.Tls12, false);
            byte[] id = Guid.NewGuid().ToByteArray();
            if (inventory != null) Wire.OfferImageSync(id);
            link.Send(new Frame(Wire.Auth, id, key));
            Frame ack = link.Read();
            if (ack == null || ack.Kind != Wire.Ack) throw new AuthenticationException("Pairing rejected");
            if (inventory != null && ack.Id.SequenceEqual(Wire.AcceptImageSync(id)))
            {
                link.ReadInventory();
                link.Send(new Frame(Wire.ImageInventory, id, inventory()));
                link.imageSync = true;
            }
            else if (!ack.Id.SequenceEqual(id)) throw new AuthenticationException("Pairing rejected");
            socket.ReceiveTimeout = 0;
            return link;
        }
        catch
        {
            link.Dispose();
            throw;
        }
    }

    internal Frame Read()
    {
        var frame = Wire.Read(stream);
        return imageSync && frame != null && frame.Kind == Wire.SavedImage
            ? new Frame(Wire.Image, frame.Id, frame.Data, true) : frame;
    }

    private void ReadInventory()
    {
        var frame = Read();
        if (frame == null || frame.Kind != Wire.ImageInventory) throw new IOException("Image inventory missing");
        for (int offset = 0; offset < frame.Data.Length; offset += Wire.IdSize)
            knownImages.Add(Convert.ToBase64String(frame.Data, offset, Wire.IdSize));
    }

    internal void Send(Frame frame)
    {
        lock (sendLock)
        {
            FlushAcknowledgements();
            string key = Convert.ToBase64String(frame.Id);
            if (frame.Kind == Wire.HistoryImage && knownImages.Contains(key)) return;
            if (frame.Kind == Wire.Delete) knownImages.Remove(key);
            bool savedLive = frame.Kind == Wire.Image && frame.SaveImage;
            var outgoing = imageSync && savedLive ? new Frame(Wire.SavedImage, frame.Id, frame.Data) : frame;
            Wire.Write(stream, outgoing);
            if (savedLive && !imageSync && !knownImages.Contains(key))
                Wire.Write(stream, new Frame(Wire.HistoryImage, frame.Id, frame.Data));
            if (frame.Kind == Wire.HistoryImage || savedLive)
            {
                if (knownImages.Count >= 128) knownImages.Clear();
                knownImages.Add(key);
            }
        }
    }

    internal void Acknowledge(byte[] id)
    {
        lock (acknowledgements)
        {
            acknowledgements.Enqueue(new Frame(Wire.Ack, id, new byte[0]));
            if (ackWriterScheduled) return;
            ackWriterScheduled = true;
        }
        // The reader must stay available while the peer is writing an image.
        System.Threading.ThreadPool.QueueUserWorkItem(delegate { WriteAcknowledgements(); });
    }

    private void WriteAcknowledgements()
    {
        try
        {
            lock (sendLock) FlushAcknowledgements();
        }
        catch (IOException) { socket.Close(); }
        catch (ObjectDisposedException) { }
    }

    private void FlushAcknowledgements()
    {
        for (;;)
        {
            Frame ack;
            lock (acknowledgements)
            {
                if (acknowledgements.Count == 0) { ackWriterScheduled = false; return; }
                ack = acknowledgements.Dequeue();
            }
            Wire.Write(stream, ack);
        }
    }

    public void Dispose()
    {
        socket.Close();
        stream.Dispose();
    }

    private static bool EqualKey(byte[] expected, byte[] provided)
    {
        if (expected.Length != Wire.KeySize || provided.Length != Wire.KeySize) return false;
        int differences = 0;
        for (int i = 0; i < Wire.KeySize; i++) differences |= expected[i] ^ provided[i];
        return differences == 0;
    }
}

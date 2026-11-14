using System;
using System.IO;
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

    private TlsLink(TcpClient socket, SslStream stream)
    {
        this.socket = socket;
        this.stream = stream;
    }

    internal static TlsLink Accept(TcpClient socket, X509Certificate2 certificate, byte[] key)
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
                link.Send(new Frame(Wire.Ack, hello.Id, new byte[0]));
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

    internal static TlsLink Connect(TcpClient socket, string host, int port, byte[] pin, byte[] key)
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
            link.Send(new Frame(Wire.Auth, id, key));
            Frame ack = link.Read();
            if (ack == null || ack.Kind != Wire.Ack || !ack.Id.SequenceEqual(id))
                throw new AuthenticationException("Pairing rejected");
            socket.ReceiveTimeout = 0;
            return link;
        }
        catch
        {
            link.Dispose();
            throw;
        }
    }

    internal Frame Read() { return Wire.Read(stream); }

    internal void Send(Frame frame)
    {
        lock (sendLock) Wire.Write(stream, frame);
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

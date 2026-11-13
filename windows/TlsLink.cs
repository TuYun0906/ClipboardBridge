using System;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
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

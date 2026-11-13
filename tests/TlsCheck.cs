using System;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;

static class TlsCheck
{
    static void Main(string[] args)
    {
        var key = Enumerable.Repeat((byte)42, Wire.KeySize).ToArray();
        using (var cert = new X509Certificate2(args[0], "clipboard-test", X509KeyStorageFlags.UserKeySet))
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            try
            {
                Console.WriteLine(((IPEndPoint)listener.LocalEndpoint).Port);
                for (int i = 0; i < 2; i++)
                {
                    using (TcpClient socket = listener.AcceptTcpClient())
                    {
                        try
                        {
                            using (TlsLink link = TlsLink.Accept(socket, cert, key))
                                throw new Exception("Invalid peer accepted");
                        }
                        catch (System.IO.IOException e) { Console.Error.WriteLine(e); }
                        catch (System.Security.Authentication.AuthenticationException e) { Console.Error.WriteLine(e); }
                    }
                }
                using (TlsLink link = TlsLink.Accept(listener.AcceptTcpClient(), cert, key))
                {
                    Frame received = link.Read();
                    if (received.Kind != Wire.Text || Encoding.UTF8.GetString(received.Data) != "手机→电脑")
                        throw new Exception("Text failed");
                    link.Send(new Frame(Wire.Ack, received.Id, new byte[0]));
                    byte[] imageId = Guid.NewGuid().ToByteArray();
                    link.Send(new Frame(Wire.Image, imageId, new byte[] { 137, 80, 78, 71 }));
                    Frame ack = link.Read();
                    if (ack.Kind != Wire.Ack || !ack.Id.SequenceEqual(imageId))
                        throw new Exception("Image confirmation failed");
                }
                for (int attempt = 0; attempt < 2; attempt++)
                {
                    using (TlsLink link = TlsLink.Accept(listener.AcceptTcpClient(), cert, key))
                    {
                        Frame latest = link.Read();
                        if (latest.Kind != Wire.Text || Encoding.UTF8.GetString(latest.Data) != "新")
                            throw new Exception("Latest content was not sent");
                        if (attempt == 0) continue;
                        link.Send(new Frame(Wire.Ack, latest.Id, new byte[0]));
                        byte[] id = Guid.NewGuid().ToByteArray();
                        link.Send(new Frame(Wire.Text, id, Encoding.UTF8.GetBytes("已收到")));
                        Frame confirmation = link.Read();
                        if (confirmation.Kind != Wire.Ack || !confirmation.Id.SequenceEqual(id))
                            throw new Exception("Incoming clipboard confirmation failed");
                    }
                }
            }
            finally { listener.Stop(); }
        }
    }
}

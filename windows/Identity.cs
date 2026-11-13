using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

internal sealed class Identity : IDisposable
{
    internal X509Certificate2 Certificate { get; private set; }
    internal byte[] Key { get; private set; }

    private Identity(X509Certificate2 certificate, byte[] key)
    {
        Certificate = certificate;
        Key = key;
    }

    internal string PairingCode
    {
        get
        {
            using (SHA256 sha = SHA256.Create())
            {
                byte[] combined = new byte[64];
                Buffer.BlockCopy(sha.ComputeHash(Certificate.RawData), 0, combined, 0, 32);
                Buffer.BlockCopy(Key, 0, combined, 32, 32);
                return Convert.ToBase64String(combined).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            }
        }
    }

    internal static Identity Open(string path)
    {
        if (!File.Exists(path)) Create(path);
        byte[] raw = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
        if (raw.Length <= Wire.KeySize) throw new CryptographicException("Invalid pairing identity");
        byte[] key = new byte[Wire.KeySize];
        byte[] pfx = new byte[raw.Length - key.Length];
        Buffer.BlockCopy(raw, 0, key, 0, key.Length);
        Buffer.BlockCopy(raw, key.Length, pfx, 0, pfx.Length);
        try
        {
            return new Identity(new X509Certificate2(pfx, "", X509KeyStorageFlags.UserKeySet), key);
        }
        finally
        {
            Array.Clear(raw, 0, raw.Length);
            Array.Clear(pfx, 0, pfx.Length);
        }
    }

    private static void Create(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        using (RSA rsa = RSA.Create(2048))
        using (RandomNumberGenerator random = RandomNumberGenerator.Create())
        {
            var request = new CertificateRequest("CN=Clipboard", rsa, HashAlgorithmName.SHA256,
                RSASignaturePadding.Pkcs1);
            using (var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1),
                       DateTimeOffset.UtcNow.AddYears(10)))
            {
                byte[] pfx = cert.Export(X509ContentType.Pfx, "");
                byte[] key = new byte[Wire.KeySize];
                random.GetBytes(key);
                byte[] raw = new byte[key.Length + pfx.Length];
                Buffer.BlockCopy(key, 0, raw, 0, key.Length);
                Buffer.BlockCopy(pfx, 0, raw, key.Length, pfx.Length);
                string temp = path + "." + Guid.NewGuid().ToString("N");
                try
                {
                    File.WriteAllBytes(temp, ProtectedData.Protect(raw, null, DataProtectionScope.CurrentUser));
                    try { File.Move(temp, path); }
                    catch (IOException) { if (!File.Exists(path)) throw; }
                }
                finally
                {
                    Array.Clear(raw, 0, raw.Length);
                    Array.Clear(key, 0, key.Length);
                    Array.Clear(pfx, 0, pfx.Length);
                    File.Delete(temp);
                }
            }
        }
    }

    public void Dispose()
    {
        Certificate.Dispose();
        Array.Clear(Key, 0, Key.Length);
    }
}

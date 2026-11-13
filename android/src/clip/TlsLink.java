package clip;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class TlsLink implements Closeable {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SSLSocket socket;
    private final Object sendLock = new Object();

    private TlsLink(SSLSocket socket) {
        this.socket = socket;
    }

    static TlsLink connect(String host, int port, byte[] pin, byte[] key) throws IOException {
        if (pin.length != 32 || key.length != Wire.KEY_SIZE)
            throw new IOException("Invalid pairing credentials");

        SSLSocket socket = createSocket(pin.clone());
        try {
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(6000);
            socket.connect(new InetSocketAddress(host, port), 6000);
            socket.startHandshake();

            TlsLink link = new TlsLink(socket);
            byte[] id = new byte[Wire.ID_SIZE];
            RANDOM.nextBytes(id);
            link.send(new Wire.Frame(Wire.AUTH, id, key));
            Wire.Frame ack = link.read();
            if (ack == null || ack.kind != Wire.ACK || !Arrays.equals(ack.id, id))
                throw new IOException("Pairing rejected");
            socket.setSoTimeout(0);
            return link;
        } catch (IOException | RuntimeException failure) {
            try { socket.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    Wire.Frame read() throws IOException {
        return Wire.read(socket.getInputStream());
    }

    void send(Wire.Frame frame) throws IOException {
        synchronized (sendLock) { Wire.write(socket.getOutputStream(), frame); }
    }

    @Override public void close() throws IOException {
        socket.close();
    }

    private static SSLSocket createSocket(byte[] pin) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new PinnedCertificate(pin)}, RANDOM);
            return (SSLSocket) context.getSocketFactory().createSocket();
        } catch (GeneralSecurityException error) {
            throw new IOException("TLS unavailable", error);
        }
    }

    private static final class PinnedCertificate implements X509TrustManager {
        private final byte[] pin;

        PinnedCertificate(byte[] pin) { this.pin = pin; }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("No server certificate");
            try {
                chain[0].checkValidity();
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(pin, hash)) throw new CertificateException("Server pin mismatch");
            } catch (GeneralSecurityException error) {
                throw new CertificateException("Server certificate rejected", error);
            }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificates are not used");
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}

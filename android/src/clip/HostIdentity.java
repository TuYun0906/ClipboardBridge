package clip;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;

final class HostIdentity {
    private static final String ALIAS = "clipboard_host";
    final SSLContext tls;
    final byte[] key;
    final String code;

    private HostIdentity(SSLContext tls, byte[] key, String code) {
        this.tls = tls;
        this.key = key;
        this.code = code;
    }

    static HostIdentity open(android.content.SharedPreferences prefs) throws java.io.IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            long now = System.currentTimeMillis();
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(new X500Principal("CN=Clipboard Bridge"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(new Date(now - 86400000L))
                    .setCertificateNotAfter(new Date(now + 20L * 365 * 86400000L)).build());
            generator.generateKeyPair();
        }

        byte[] key;
        try { key = Base64.getDecoder().decode(prefs.getString(Prefs.HOST_KEY, "")); }
        catch (IllegalArgumentException invalid) { key = new byte[0]; }
        SecureRandom random = new SecureRandom();
        if (key.length != Wire.KEY_SIZE) {
            key = new byte[Wire.KEY_SIZE];
            random.nextBytes(key);
            if (!prefs.edit().putString(Prefs.HOST_KEY, Base64.getEncoder().encodeToString(key)).commit())
                throw new java.io.IOException("Could not save host key");
        }

        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, null);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, random);
        byte[] data = new byte[64];
        byte[] pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(ALIAS).getEncoded());
        System.arraycopy(pin, 0, data, 0, pin.length);
        System.arraycopy(key, 0, data, pin.length, key.length);
        return new HostIdentity(context, key, Base64.getUrlEncoder().withoutPadding().encodeToString(data));
    }
}

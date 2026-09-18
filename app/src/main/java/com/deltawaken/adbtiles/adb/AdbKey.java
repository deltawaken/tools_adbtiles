package com.deltawaken.adbtiles.adb;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * La clé RSA de l'app, vue par adbd comme celle d'un ordinateur.
 *
 * <p>Elle sert deux fois : pour signer le jeton {@code AUTH} de l'adb classique, et comme certificat
 * client du TLS du débogage sans fil. Une fois autorisée par l'adb classique, adbd l'accepte aussi en
 * TLS, sans appairage (mesuré sur le Jelly Max le 2026-09-18).
 */
public final class AdbKey {

    private static final String KEY_FILE = "adbkey.pk8";
    private static final String CERT_FILE = "adbkey.crt";

    /** En-tête DigestInfo SHA-1 : adbd vérifie le jeton comme s'il s'agissait d'un condensé SHA-1. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    final PrivateKey privateKey;
    final X509Certificate certificate;

    private AdbKey(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    public static boolean exists(Context context) {
        return new File(context.getFilesDir(), KEY_FILE).exists()
                && new File(context.getFilesDir(), CERT_FILE).exists();
    }

    public static AdbKey loadOrCreate(Context context) throws IOException, GeneralSecurityException {
        File keyFile = new File(context.getFilesDir(), KEY_FILE);
        File certFile = new File(context.getFilesDir(), CERT_FILE);
        if (keyFile.exists() && certFile.exists()) {
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            return new AdbKey(key, parseCertificate(Files.readAllBytes(certFile.toPath())));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        byte[] certificate = SelfSignedCertificate.create(pair);
        Files.write(keyFile.toPath(), pair.getPrivate().getEncoded());
        Files.write(certFile.toPath(), certificate);
        return new AdbKey(pair.getPrivate(), parseCertificate(certificate));
    }

    byte[] signToken(byte[] token) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(privateKey);
        signature.update(SHA1_DIGEST_INFO);
        signature.update(token);
        return signature.sign();
    }

    /** Clé publique au format d'{@code adb_keys} : structure RSA d'Android en base64, puis un nom. */
    byte[] adbPublicKey() {
        RSAPublicKey key = (RSAPublicKey) certificate.getPublicKey();
        BigInteger modulus = key.getModulus();
        int words = 64;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = modulus.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 64).mod(modulus);

        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(words);
        buffer.putInt(n0inv.intValue());
        buffer.put(littleEndian(modulus, words * 4));
        buffer.put(littleEndian(rr, words * 4));
        buffer.putInt(key.getPublicExponent().intValue());

        String encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP) + " adbtiles@android\0";
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger value, int length) {
        byte[] bigEndian = value.toByteArray();
        byte[] out = new byte[length];
        for (int i = 0; i < length && i < bigEndian.length; i++) {
            out[i] = bigEndian[bigEndian.length - 1 - i];
        }
        return out;
    }

    private static X509Certificate parseCertificate(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    /** Certificat X.509 v1 auto-signé, encodé à la main pour éviter toute dépendance. */
    private static final class SelfSignedCertificate {

        private static final byte[] SHA256_WITH_RSA = {
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00
        };

        static byte[] create(KeyPair pair) throws GeneralSecurityException, IOException {
            byte[] name = der(0x30, der(0x31, der(0x30,
                    new byte[] {0x06, 0x03, 0x55, 0x04, 0x03},
                    der(0x0c, "adbtiles".getBytes(StandardCharsets.US_ASCII)))));
            byte[] validity = der(0x30,
                    der(0x17, "250101000000Z".getBytes(StandardCharsets.US_ASCII)),
                    der(0x18, "20991231235959Z".getBytes(StandardCharsets.US_ASCII)));
            byte[] tbs = der(0x30,
                    new byte[] {0x02, 0x01, 0x01},
                    SHA256_WITH_RSA, name, validity, name,
                    pair.getPublic().getEncoded());

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(pair.getPrivate());
            signature.update(tbs);
            byte[] signed = signature.sign();
            byte[] bitString = new byte[signed.length + 1];
            System.arraycopy(signed, 0, bitString, 1, signed.length);

            return der(0x30, tbs, SHA256_WITH_RSA, der(0x03, bitString));
        }

        private static byte[] der(int tag, byte[]... parts) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (byte[] part : parts) {
                body.write(part);
            }
            int length = body.size();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(tag);
            if (length < 0x80) {
                out.write(length);
            } else if (length < 0x100) {
                out.write(0x81);
                out.write(length);
            } else {
                out.write(0x82);
                out.write(length >> 8);
                out.write(length & 0xff);
            }
            body.writeTo(out);
            return out.toByteArray();
        }
    }
}

package com.deltawaken.adbtile.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Client adb minimal : se connecter à l'adbd du téléphone même, puis lancer un service
 * ({@code tcpip:5555}, {@code usb:}). Parle l'adb classique et, si adbd le demande par {@code STLS},
 * le TLS du débogage sans fil.
 */
public final class AdbClient implements Closeable {

    /** adbd a refusé la clé : elle n'est pas (ou plus) autorisée. */
    public static final class AuthException extends IOException {
        AuthException() {
            super("Clé adb refusée");
        }
    }

    private static final int VERSION = 0x01000001;
    private static final int STLS_VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int LOCAL_ID = 1;

    private final AdbKey key;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private AdbClient(AdbKey key) {
        this.key = key;
    }

    /**
     * Ouvre une connexion authentifiée.
     *
     * @param offerPublicKey si la signature est refusée, présenter la clé publique : Android affiche
     *                       alors « Autoriser le débogage ? », et l'on attend {@code promptTimeoutMs}.
     */
    public static AdbClient connect(String host, int port, AdbKey key,
            boolean offerPublicKey, int promptTimeoutMs)
            throws IOException, GeneralSecurityException {
        AdbClient client = new AdbClient(key);
        try {
            client.handshake(host, port, offerPublicKey, promptTimeoutMs);
            return client;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            client.close();
            throw e;
        }
    }

    private void handshake(String host, int port, boolean offerPublicKey, int promptTimeoutMs)
            throws IOException, GeneralSecurityException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setSoTimeout(10000);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        AdbMessage.write(out, AdbMessage.CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        boolean offered = false;
        while (true) {
            AdbMessage message = AdbMessage.read(in);
            switch (message.command) {
                case AdbMessage.CNXN:
                    socket.setSoTimeout(10000);
                    return;
                case AdbMessage.STLS:
                    AdbMessage.write(out, AdbMessage.STLS, STLS_VERSION, 0, null);
                    upgradeToTls(host, port);
                    break;
                case AdbMessage.AUTH:
                    if (message.arg0 != AUTH_TOKEN) {
                        throw new IOException("AUTH inattendu");
                    }
                    if (!signed) {
                        AdbMessage.write(out, AdbMessage.AUTH, AUTH_SIGNATURE, 0,
                                key.signToken(message.payload));
                        signed = true;
                    } else if (offerPublicKey && !offered) {
                        AdbMessage.write(out, AdbMessage.AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey());
                        offered = true;
                        socket.setSoTimeout(promptTimeoutMs);
                    } else {
                        throw new AuthException();
                    }
                    break;
                default:
                    throw new IOException("Réponse adb inattendue");
            }
        }
    }

    private void upgradeToTls(String host, int port) throws IOException, GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(new KeyManager[] {new ClientKeyManager(key)},
                new TrustManager[] {new TrustAdbd()}, null);
        SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, true);
        tls.setUseClientMode(true);
        tls.setEnabledProtocols(new String[] {"TLSv1.3"});
        try {
            tls.startHandshake();
        } catch (IOException e) {
            // adbd ferme la poignée de main quand le certificat client n'est pas autorisé.
            throw new AuthException();
        }
        socket = tls;
        in = tls.getInputStream();
        out = tls.getOutputStream();
    }

    /**
     * Lance un service et renvoie sa sortie. {@code tcpip:} et {@code usb:} redémarrent adbd : la
     * connexion peut se fermer avant le {@code CLSE}, ce qui vaut réussite.
     */
    public String run(String service) throws IOException {
        AdbMessage.write(out, AdbMessage.OPEN, LOCAL_ID, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            while (true) {
                AdbMessage message = AdbMessage.read(in);
                if (message.command == AdbMessage.WRTE) {
                    output.write(message.payload);
                    AdbMessage.write(out, AdbMessage.OKAY, LOCAL_ID, message.arg0, null);
                } else if (message.command == AdbMessage.CLSE) {
                    break;
                }
            }
        } catch (EOFException | SocketException e) {
            // adbd redémarre : fin normale pour tcpip: et usb:.
        }
        return output.toString("UTF-8");
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Rien à faire : on abandonne la connexion.
            }
        }
    }

    private static final class ClientKeyManager extends X509ExtendedKeyManager {
        private static final String ALIAS = "adb";
        private final AdbKey key;

        ClientKeyManager(AdbKey key) {
            this.key = key;
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[] {key.certificate};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key.privateKey;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {ALIAS};
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }

    /** adbd présente un certificat auto-signé ; on se connecte à son propre téléphone. */
    private static final class TrustAdbd implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

package com.deltawaken.adbtile;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

import com.deltawaken.adbtile.adb.AdbClient;
import com.deltawaken.adbtile.adb.AdbKey;
import com.deltawaken.adbtile.adb.TlsPortFinder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;

/**
 * Ouvrir et fermer le port {@value #PORT} d'adbd, comme {@code adb tcpip} et {@code adb usb}.
 *
 * <p>Fermer passe par l'adb classique sur le port ouvert, sans Wi-Fi. Ouvrir, quand le port est
 * fermé (après un redémarrage), passe par le débogage sans fil — qu'Android n'accepte qu'avec une
 * connexion Wi-Fi (mesuré, même point d'accès allumé).
 */
final class Tcpip {

    static final int PORT = 5555;
    private static final String LOCALHOST = "127.0.0.1";
    private static final String PREFS = "adbtile";
    private static final String PREF_AUTHORIZED = "authorized";

    private Tcpip() {
    }

    static boolean isPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOCALHOST, PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Attend que le port prenne l'état voulu, adbd mettant une ou deux secondes à redémarrer. */
    static boolean waitForPort(boolean open, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen() == open) {
                return true;
            }
            Thread.sleep(400);
        }
        return isPortOpen() == open;
    }

    static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return true;
            }
        }
        return false;
    }

    static boolean isAuthorized(Context context) {
        return AdbKey.exists(context) && prefs(context).getBoolean(PREF_AUTHORIZED, false);
    }

    static void setAuthorized(Context context, boolean authorized) {
        prefs(context).edit().putBoolean(PREF_AUTHORIZED, authorized).apply();
    }

    /**
     * Fait autoriser la clé de l'app : adb classique sur le port ouvert, Android affiche
     * « Autoriser le débogage ? ». Exige que le port soit déjà ouvert.
     */
    static void authorize(Context context) throws IOException, GeneralSecurityException {
        AdbKey key = AdbKey.loadOrCreate(context);
        try (AdbClient ignored = AdbClient.connect(LOCALHOST, PORT, key, true, 60000)) {
            setAuthorized(context, true);
        }
    }

    static void close(Context context) throws IOException, GeneralSecurityException {
        AdbKey key = AdbKey.loadOrCreate(context);
        try (AdbClient client = AdbClient.connect(LOCALHOST, PORT, key, false, 0)) {
            client.run("usb:");
        } catch (AdbClient.AuthException e) {
            setAuthorized(context, false);
            throw e;
        }
    }

    static void open(Context context)
            throws IOException, GeneralSecurityException, InterruptedException {
        AdbKey key = AdbKey.loadOrCreate(context);
        ContentResolver resolver = context.getContentResolver();
        int previous = Settings.Global.getInt(resolver, "adb_wifi_enabled", 0);
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1);
        try {
            InetSocketAddress address = TlsPortFinder.find(context, 15000);
            if (address == null) {
                throw new IOException("Port du débogage sans fil introuvable");
            }
            try (AdbClient client = AdbClient.connect(
                    address.getAddress().getHostAddress(), address.getPort(), key, false, 0)) {
                client.run("tcpip:" + PORT);
            } catch (AdbClient.AuthException e) {
                setAuthorized(context, false);
                throw e;
            }
        } finally {
            Settings.Global.putInt(resolver, "adb_wifi_enabled", previous);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

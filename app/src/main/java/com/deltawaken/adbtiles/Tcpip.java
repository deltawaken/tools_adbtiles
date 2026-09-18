package com.deltawaken.adbtiles;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.deltawaken.adbtiles.adb.AdbClient;
import com.deltawaken.adbtiles.adb.AdbKey;
import com.deltawaken.adbtiles.adb.TlsPortFinder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Ouvrir et fermer le port TCP d'adbd, comme {@code adb tcpip} et {@code adb usb}.
 *
 * <p>Fermer passe par l'adb classique sur le port ouvert, sans Wi-Fi. Ouvrir, quand le port est
 * fermé (après un redémarrage), passe par le débogage sans fil — qu'Android n'accepte qu'avec une
 * connexion Wi-Fi (mesuré, même point d'accès allumé).
 */
final class Tcpip {

    static final int DEFAULT_PORT = 5555;
    /** adbd ne tourne pas en root : il ne peut pas écouter sous 1024. */
    static final int MIN_PORT = 1024;
    static final int MAX_PORT = 65535;
    private static final String LOCALHOST = "127.0.0.1";
    private static final String PREFS = "adbtiles";
    private static final String PREF_AUTHORIZED = "authorized";
    private static final String PREF_PORT = "port";

    // État partagé par les deux tuiles et l'écran. Il vit au niveau du processus parce que SystemUI
    // détache et recrée les tuiles toutes les cinq secondes environ sur cette ROM (mesuré le
    // 2026-09-18) : un état gardé dans l'instance de la tuile se perdait à chaque réveil.

    /** Port choisi dans l'app ; relu des préférences par {@link #loadPort}. */
    static volatile int port = DEFAULT_PORT;
    /** Dernier état sondé du port ; null tant qu'on ne sait pas. */
    static volatile Boolean portOpen;
    /** Une bascule TCP/IP, ou l'attente d'adbd après le rallumage du débogage USB, est en cours. */
    static volatile boolean tcpipBusy;
    /** Le système jette les paquets de l'app : la sonde ne dit rien du port. */
    static volatile boolean networkBlocked;
    /** La dernière bascule TCP/IP a échoué. */
    static volatile boolean tcpipFailed;
    /** Jusqu'à quand ignorer les appuis sur la tuile USB, le temps qu'adbd s'arrête ou démarre. */
    static volatile long usbBusyUntil;

    private static final Set<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Tcpip() {
    }

    static void loadPort(Context context) {
        port = prefs(context).getInt(PREF_PORT, DEFAULT_PORT);
    }

    static boolean isValidPort(int value) {
        return value >= MIN_PORT && value <= MAX_PORT;
    }

    /** Change le port. N'a de sens que TCP/IP éteint : l'écran ne le propose pas autrement. */
    static void setPort(Context context, int value) {
        prefs(context).edit().putInt(PREF_PORT, value).apply();
        port = value;
        portOpen = null;
        notifyChanged();
    }

    static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** Prévient les tuiles et l'écran vivants qu'il faut se redessiner. */
    static void notifyChanged() {
        for (Runnable listener : LISTENERS) {
            MAIN.post(listener);
        }
    }

    /** Sonde le port hors du fil principal, puis prévient. */
    static void probeAsync() {
        new Thread(() -> {
            Probe result = probe();
            networkBlocked = result == Probe.BLOCKED;
            if (!networkBlocked) {
                portOpen = result == Probe.OPEN;
            }
            notifyChanged();
        }, "adbtiles-probe").start();
    }

    /**
     * Le débogage USB vient d'être rallumé : adbd redémarre, et rouvre son port s'il était en mode TCP.
     * On attend qu'il réponde plutôt que de parier sur un délai.
     */
    static void awaitAdbd() {
        tcpipBusy = true;
        notifyChanged();
        new Thread(() -> {
            try {
                portOpen = waitForPort(true, 6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                tcpipBusy = false;
                notifyChanged();
            }
        }, "adbtiles-await").start();
    }

    static boolean isPortOpen() {
        return probe() == Probe.OPEN;
    }

    enum Probe { OPEN, CLOSED, BLOCKED }

    /**
     * Sonde le port. Un port fermé refuse la connexion aussitôt ; un délai dépassé vers
     * {@code 127.0.0.1} signifie que le système jette les paquets de l'app — Android coupe le réseau
     * des apps en veille, loopback compris (mesuré sur le Jelly Max le 2026-09-18 : la tuile affichait
     * « Désactivé » alors que 5555 répondait au shell).
     */
    static Probe probe() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOCALHOST, port), 1000);
            return Probe.OPEN;
        } catch (SocketTimeoutException e) {
            return Probe.BLOCKED;
        } catch (IOException e) {
            return Probe.CLOSED;
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
        try (AdbClient ignored = AdbClient.connect(LOCALHOST, port, key, true, 60000)) {
            setAuthorized(context, true);
        }
    }

    static void close(Context context) throws IOException, GeneralSecurityException {
        AdbKey key = AdbKey.loadOrCreate(context);
        try (AdbClient client = AdbClient.connect(LOCALHOST, port, key, false, 0)) {
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
                client.run("tcpip:" + port);
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

package com.deltawaken.adbtiles;

import android.content.Context;
import android.provider.Settings;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remet le port TCP d'adbd dans l'état où l'utilisateur l'avait laissé avant un redémarrage.
 *
 * <p><b>Pourquoi ça doit exister.</b> {@code adb tcpip 5555} ne pose que
 * {@code service.adb.tcp.port}, une propriété de <i>runtime</i> : elle ne survit pas au
 * redémarrage. Celle qui survivrait, {@code persist.adb.tcp.port}, n'est pas écrivable sous
 * uid 2000 (SELinux refuse, mesuré sur HONOR 70 Lite le 2026-09-25). Mesuré le même jour sur
 * sept téléphones : <b>0 sur 7</b> retrouvent l'adb sans fil après un redémarrage, et quatre
 * d'entre eux deviennent <b>totalement injoignables</b> faute de câble.
 *
 * <p><b>Ce que cette classe ne fait PAS.</b> Elle n'allume jamais l'adb sans fil de sa propre
 * initiative. Elle ne restaure que ce que l'utilisateur avait <b>lui-même</b> ouvert, lu dans
 * {@link Tcpip#isDesiredOpen}. Port fermé par l'utilisateur ⇒ il reste fermé après le
 * redémarrage. C'est la différence entre rendre un réglage et en imposer un.
 *
 * <p><b>Comment l'intention s'apprend.</b> Pas seulement par la tuile : chaque passage observe le
 * port, et un port <b>ouvert</b> vaut intention. C'est nécessaire — sur un appareil où le port
 * était déjà ouvert avant que cette fonction n'existe, la préférence serait à « faux » et on ne
 * restaurerait jamais rien. Et c'est juste : un port ouvert a été ouvert par quelqu'un, la tuile ou
 * {@code adb tcpip} depuis un hôte, et les deux sont l'utilisateur.
 *
 * <p>⚠️ L'observation se lit avec {@link Tcpip#probe()} et <b>jamais</b> avec
 * {@link Tcpip#isPortOpen()}, qui rend {@code false} pour « fermé » <i>comme</i> pour « le système
 * jette nos paquets ». Sur cette confusion, une mise en veille effacerait l'intention.
 *
 * <p><b>Par où le réveil arrive, et pourquoi deux chemins.</b>
 * <ul>
 *   <li>{@code onCreate()} des deux {@link android.service.quicksettings.TileService} —
 *       <b>le chemin visé</b>, parce qu'une liaison de service n'est pas une diffusion : elle ne
 *       passe donc structurellement pas par le filtre de diffusions d'iAware.
 *       ⚠️ <b>NON ENCORE PROUVÉ au 2026-09-25.</b> On a bien observé le processus d'ADB Tiles
 *       démarrer +38 s après le boot sur HONOR 70 Lite, et on l'a <i>attribué</i> à SystemUI parce
 *       que l'app n'avait ni alarme ni receveur de boot — mais c'est une <b>inférence</b>, pas une
 *       lecture. Le premier redémarrage instrumenté, sur vivo Y19s, a été gagné par
 *       {@code BOOT_COMPLETED} : {@code am_proc_start} le nomme, et les tuiles d'ADB Tiles
 *       n'étaient même pas dans le volet de ce téléphone — le chemin n'existait pas.
 *       C'est pour trancher ça que chaque ligne de journal porte son origine.</li>
 *   <li>{@link BootReceiver} sur {@code BOOT_COMPLETED} — le chemin de secours. ⚠️ Il est
 *       <b>inopérant</b> sur les ROM qui filtrent les diffusions des applications laissées dans
 *       leur état d'installation : mesuré sur MagicOS le 2026-09-25, une application inconnue en
 *       « Gérée automatiquement » reçoit <b>0 diffusion sur 3</b> essais, contre 5 sur 5 une fois
 *       réglée. D'où l'ordre des deux : la tuile d'abord, la diffusion en plus.</li>
 * </ul>
 *
 * <p>Aucun service d'avant-plan : en API 31+ on ne peut pas en démarrer un depuis l'arrière-plan
 * sans motif autorisé, et il faudrait une notification. Un fil suffit — s'il meurt avec le
 * processus, la liaison suivante de SystemUI refait une tentative.
 */
final class Restore {

    private static final String TAG = "AdbTiles";

    /** Le débogage sans fil, seul chemin pour rouvrir le port, n'existe qu'à partir d'API 30. */
    private static final int MIN_API = android.os.Build.VERSION_CODES.R;

    /** Combien de temps attendre un Wi-Fi qui monte après un démarrage. */
    private static final long WIFI_WAIT_MS = 90_000;
    private static final long WIFI_POLL_MS = 3_000;

    /**
     * SystemUI relie les tuiles toutes les ~5 s : sans ce délai de garde, chaque liaison
     * relancerait une découverte mDNS de 15 s. Une tentative au plus par fenêtre.
     */
    private static final long COOLDOWN_MS = 30_000;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile long lastAttempt;

    private Restore() {
    }

    /** Ce qui a réveillé l'app. Écrit dans CHAQUE ligne de journal : c'est la question à trancher. */
    static final String ORIGIN_TILE_TCPIP = "TUILE:tcpip";
    static final String ORIGIN_TILE_USB = "TUILE:usb";
    static final String ORIGIN_BOOT = "BOOT_COMPLETED";
    static final String ORIGIN_SCREEN = "ECRAN";

    /**
     * Tente une restauration si elle a un sens, sans jamais bloquer l'appelant.
     *
     * <p>Appelable depuis {@code onCreate()} d'une tuile ou depuis un receveur : tout le travail
     * part sur un fil, et l'appelant rend la main aussitôt.
     *
     * @param origin lequel des deux chemins a réveillé l'app — {@link #ORIGIN_TILE_TCPIP},
     *     {@link #ORIGIN_TILE_USB}, {@link #ORIGIN_BOOT} ou {@link #ORIGIN_SCREEN}. ⚠️ <b>C'est la
     *     donnée pour laquelle ce paramètre existe</b> : savoir si la restauration passe par la
     *     liaison de tuile (hors diffusion, donc hors du filtre d'iAware) ou par la diffusion de
     *     démarrage (bloquée sur certaines ROM). Ne PAS s'en remettre à {@code am_proc_start} du
     *     système : son tampon tourne, et son format change d'une ROM à l'autre.
     */
    static void maybeRestore(Context context, String origin) {
        Context app = context.getApplicationContext();
        // Le débogage USB d'abord : il vaut à toute version, et sans lui rien d'autre n'a de sens.
        UsbDebug.maybeRestore(app, origin);
        if (android.os.Build.VERSION.SDK_INT < MIN_API) {
            Journal.log(app, "Restore[" + origin + "] ignoré : API "
                    + android.os.Build.VERSION.SDK_INT + " < 30, pas de débogage sans fil");
            return;
        }
        Tcpip.loadPort(app);
        long now = System.currentTimeMillis();
        if (now - lastAttempt < COOLDOWN_MS) {
            // ⚠️ Journalisé quand même, en une ligne : c'était la seule sortie muette de cette
            // méthode, et une sortie muette rend un « jamais appelé » indiscernable d'un
            // « appelé et ignoré ». Le fichier absorbe une ligne toutes les 5 s sans problème.
            Journal.log(app, "Restore[" + origin + "] ignoré : délai de garde ("
                    + (now - lastAttempt) / 1000 + " s depuis la dernière tentative)");
            return;
        }
        // Une bascule manuelle en cours a la priorité : elle écrit la même clé secure que nous.
        if (Tcpip.tcpipBusy) {
            Journal.log(app, "Restore[" + origin + "] ignoré : une bascule est déjà en cours");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Journal.log(app, "Restore[" + origin + "] ignoré : une autre origine a pris la main");
            return;
        }
        lastAttempt = now;
        Journal.log(app, "Restore[" + origin + "] réveil — c'est CE chemin qui a démarré le processus");
        new Thread(() -> {
            try {
                run(app, origin);
            } catch (Throwable t) {
                // Ne jamais laisser remonter : ce fil tourne sous le processus de SystemUI-lié.
                Journal.warn(app, "Restore[" + origin + "] échec", t);
            } finally {
                RUNNING.set(false);
            }
        }, "adbtiles-restore").start();
    }

    private static void run(Context context, String origin) throws Exception {
        // ⚠️ `probe()` et NON `isPortOpen()` : ce dernier rend `false` aussi bien pour « fermé »
        // que pour « le système jette nos paquets » (veille : Android coupe le réseau de l'app,
        // loopback compris). Confondre les deux ferait enregistrer « l'utilisateur voulait fermé »
        // à chaque mise en veille, et détruirait l'intention qu'on est censé garder.
        Tcpip.Probe observed = Tcpip.probe();
        switch (observed) {
            case BLOCKED:
                // On ne sait rien du port. Ne rien écrire, ne rien tenter.
                Tcpip.networkBlocked = true;
                Journal.log(context, "Restore[" + origin + "] abandon : sonde BLOQUÉE, le système jette nos paquets");
                Tcpip.notifyChanged();
                return;
            case OPEN:
                // Le port est ouvert : quelqu'un l'a ouvert — la tuile, ou `adb tcpip` depuis un
                // hôte. Dans les deux cas c'est l'intention de l'utilisateur, et c'est la SEULE
                // façon dont cette intention peut s'apprendre sur un appareil où le réglage
                // préexistait à cette fonction. Rien d'autre à faire.
                Tcpip.networkBlocked = false;
                Tcpip.portOpen = true;
                boolean knew = Tcpip.isDesiredOpen(context);
                Tcpip.setDesiredOpen(context, true);
                // Tracé : sans ça, « l'intention est retenue » ne serait pas vérifiable de
                // l'extérieur — l'app n'est pas debuggable, donc ses préférences ne se lisent pas.
                Journal.log(context, "Restore[" + origin + "] " + (knew
                        ? "port " + Tcpip.port + " déjà ouvert, intention déjà connue"
                        : "port " + Tcpip.port + " ouvert — intention RETENUE pour le prochain démarrage"));
                Tcpip.notifyChanged();
                return;
            default:
                Tcpip.networkBlocked = false;
                Tcpip.portOpen = false;
                break;
        }
        // Port fermé pour de bon. On ne rouvre QUE si l'utilisateur l'avait ouvert avant.
        if (!Tcpip.isDesiredOpen(context)) {
            Journal.log(context, "Restore[" + origin + "] rien à faire : port fermé et l'utilisateur l'avait fermé");
            Tcpip.notifyChanged();
            return;
        }
        if (!canAct(context, origin)) {
            return;
        }
        if (!awaitWifi(context)) {
            Journal.log(context, "Restore[" + origin + "] abandon : pas de Wi-Fi dans la fenêtre de 90 s");
            return;
        }
        Tcpip.tcpipBusy = true;
        Tcpip.notifyChanged();
        try {
            Tcpip.open(context);
            boolean ok = Tcpip.waitForPort(true, 8000);
            Tcpip.tcpipFailed = !ok;
            Journal.log(context, "Restore[" + origin + "] " + (ok
                    ? "⭐ PORT " + Tcpip.port + " RESTAURÉ — c'est " + origin + " qui a fait le travail"
                    : "restauration tentée, port toujours fermé"));
        } finally {
            Tcpip.portOpen = Tcpip.isPortOpen();
            Tcpip.tcpipBusy = false;
            Tcpip.notifyChanged();
        }
    }

    /**
     * Les conditions sans lesquelles {@link Tcpip#open} ne peut pas aboutir. Les lire ici évite
     * d'écrire {@code adb_wifi_enabled} pour rien, et de laisser cette clé modifiée si on
     * échouait plus loin.
     */
    private static boolean canAct(Context context, String origin) {
        if (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1) {
            Journal.log(context, "Restore[" + origin + "] impossible : options développeur éteintes");
            return false;
        }
        if (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) != 1) {
            Journal.log(context, "Restore[" + origin + "] impossible : débogage USB éteint");
            return false;
        }
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // ⚠️ Cette permission ne survit PAS à une réinstallation : il faut la réaccorder par
            // `adb shell pm grant`. C'est la panne la plus probable après une mise à jour.
            Journal.log(context, "Restore[" + origin + "] impossible : WRITE_SECURE_SETTINGS non accordée");
            return false;
        }
        if (!Tcpip.isAuthorized(context)) {
            Journal.log(context, "Restore[" + origin + "] impossible : clé adb non autorisée");
            return false;
        }
        return true;
    }

    /** Au démarrage le Wi-Fi n'est pas encore monté, et {@link Tcpip#open} l'exige. */
    private static boolean awaitWifi(Context context) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WIFI_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Tcpip.isWifiConnected(context)) {
                return true;
            }
            Thread.sleep(WIFI_POLL_MS);
        }
        return Tcpip.isWifiConnected(context);
    }
}

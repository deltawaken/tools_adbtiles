package com.deltawaken.adbtiles;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Rend le <b>débogage USB</b> dans l'état où l'utilisateur l'avait laissé — le pendant câble de
 * {@link Restore}, et il vaut à toute version d'Android (le sans-fil, lui, exige API 30).
 *
 * <p><b>Pourquoi ça doit exister.</b> Mesuré sur HONOR 8X (EMUI 10, API 29) le 2026-09-25 : après
 * un redémarrage, la ROM applique {@code persist.sys.usb.config} — et cette valeur peut avoir
 * <b>perdu {@code adb}</b> (lu : {@code hisuite,mtp,mass_storage}) alors que {@code adb_enabled=1}.
 * Le port USB se ré-énumère alors sans interface adb, et un téléphone au câble seul devient
 * injoignable jusqu'à un appui humain. {@code setprop} est refusé au shell, et une bascule
 * {@code adb_enabled} 0 → 1 depuis un shell adb meurt avec adbd (mesuré le même soir : le
 * téléphone est resté éteint côté débogage). Seule une application qui tourne <i>sur</i> le
 * téléphone survit à l'arrêt d'adbd — et SystemUI lie nos tuiles à +24 s après le boot sur ce
 * même 8X (mesuré 2/2), avant que la ROM ne recycle le processus (~40 s).
 *
 * <p><b>Ce que ça fait, et rien d'autre.</b>
 * <ul>
 *   <li>Débogage voulu ({@code adb_enabled=1}) mais <b>fonction {@code adb} absente</b> de l'état
 *       USB alors qu'un câble est branché → bascule 0 → 1, ce qui fait réécrire la configuration
 *       persistée par {@code UsbDeviceManager}. C'est le geste de l'interrupteur des options
 *       développeur, fait par l'app.</li>
 *   <li>Débogage <b>éteint</b> alors que l'utilisateur le voulait allumé (intention apprise par
 *       observation, comme {@link Tcpip#isDesiredOpen}), et on est <b>dans la fenêtre de
 *       démarrage</b> → rallumer. Hors de cette fenêtre, un débogage éteint est lu comme un choix
 *       de l'utilisateur et l'intention est mise à jour, jamais contredite.</li>
 * </ul>
 *
 * <p>Une tentative au plus par processus. Tout est journalisé dans {@link Journal} avec l'origine.
 */
final class UsbDebug {

    private static final String PREFS = "usbdebug";
    private static final String PREF_DESIRED = "desiredAdb";

    /** Après ce délai depuis le boot, un débogage éteint est un choix de l'utilisateur. */
    private static final long BOOT_WINDOW_MS = 5 * 60_000;

    /** Le temps qu'adbd s'arrête avant de le relancer ; en dessous, la ROM avale la seconde écriture. */
    private static final long TOGGLE_GAP_MS = 2_500;

    /** Le temps laissé à adbd et à l'USB pour se ré-énumérer avant de VÉRIFIER que ça a marché. */
    private static final long VERIFY_AFTER_MS = 6_000;

    /** Essais au plus : demande du porteur (2026-09-25) — regarder si ça a marché, et recommencer sinon. */
    private static final int MAX_ATTEMPTS = 3;

    private static volatile boolean attempted;

    private UsbDebug() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isDesired(Context context) {
        return prefs(context).getBoolean(PREF_DESIRED, false);
    }

    static void setDesired(Context context, boolean desired) {
        prefs(context).edit().putBoolean(PREF_DESIRED, desired).apply();
    }

    private static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    private static boolean hasPermission(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * L'état USB courant, tel que le système le publie en diffusion collante
     * ({@code android.hardware.usb.action.USB_STATE}) : {@code connected}, et un booléen par
     * fonction, dont {@code adb}. Lisible sans permission. {@code null} si le système n'a encore
     * rien publié.
     */
    private static Intent usbState(Context context) {
        try {
            return context.registerReceiver(null, new IntentFilter("android.hardware.usb.action.USB_STATE"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Appelé à chaque réveil de {@link Restore}. Rend la main vite ; l'écriture part sur un fil. */
    static void maybeRestore(Context context, String origin) {
        Context app = context.getApplicationContext();
        boolean enabled = isEnabled(app);
        Intent usb = usbState(app);
        boolean connected = usb != null && usb.getBooleanExtra("connected", false);
        boolean adbFunction = usb != null && usb.getBooleanExtra("adb", false);
        long uptime = SystemClock.elapsedRealtime();
        boolean bootWindow = uptime < BOOT_WINDOW_MS;

        if (enabled) {
            // Observation : allumé, c'est l'intention — d'où qu'elle vienne (options dev, notre tuile).
            if (!isDesired(app)) {
                setDesired(app, true);
                Journal.log(app, "UsbDebug[" + origin + "] débogage USB allumé — intention RETENUE");
            }
            if (connected && !adbFunction) {
                if (attempted) {
                    return;
                }
                attempted = true;
                Journal.log(app, "UsbDebug[" + origin + "] débogage allumé mais fonction adb ABSENTE de l'USB"
                        + " (câble branché) — bascule 0 → 1 pour réécrire la configuration persistée");
                repair(app, origin, true);
            } else {
                Journal.log(app, "UsbDebug[" + origin + "] rien à faire : débogage allumé, adb "
                        + (connected ? "présent sur l'USB" : "sans câble"));
            }
            return;
        }
        // Éteint.
        if (!isDesired(app)) {
            Journal.log(app, "UsbDebug[" + origin + "] rien à faire : débogage éteint et jamais voulu");
            return;
        }
        if (!bootWindow) {
            setDesired(app, false);
            Journal.log(app, "UsbDebug[" + origin + "] débogage éteint hors fenêtre de démarrage (up="
                    + uptime / 1000 + " s) — lu comme un choix de l'utilisateur, intention OUBLIÉE");
            return;
        }
        if (attempted) {
            return;
        }
        attempted = true;
        if (!hasPermission(app)) {
            Journal.log(app, "UsbDebug[" + origin + "] impossible : WRITE_SECURE_SETTINGS non accordée");
            return;
        }
        Journal.log(app, "UsbDebug[" + origin + "] débogage éteint au démarrage alors qu'il était voulu"
                + " (up=" + uptime / 1000 + " s) — RALLUMAGE");
        repair(app, origin, false);
    }

    /** La fonction adb est-elle servie sur un câble branché ? {@code null} = pas de câble, invérifiable. */
    private static Boolean adbOnCable(Context app) {
        Intent usb = usbState(app);
        if (usb == null || !usb.getBooleanExtra("connected", false)) {
            return null;
        }
        return usb.getBooleanExtra("adb", false);
    }

    /**
     * Rallume le débogage USB et <b>vérifie</b> que la fonction adb est revenue sur le câble ;
     * sinon recommence, {@link #MAX_ATTEMPTS} fois au plus, en basculant 0 → 1 à chaque essai
     * (demande du porteur : « regarde si le adb on a marché, et recommence quelques secondes plus
     * tard sinon »). Sans câble, la vérification est impossible : un seul essai, et on le dit.
     *
     * @param toggleFirst vrai si le débogage est déjà « allumé » côté réglage : il faut alors
     *     passer par 0 pour qu'adbd redémarre et que la configuration persistée soit réécrite.
     */
    private static void repair(Context app, String origin, boolean toggleFirst) {
        if (!hasPermission(app)) {
            Journal.log(app, "UsbDebug[" + origin + "] impossible : WRITE_SECURE_SETTINGS non accordée");
            return;
        }
        new Thread(() -> {
            boolean viaToggle = toggleFirst;
            try {
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    Tcpip.usbBusyUntil = System.currentTimeMillis() + TOGGLE_GAP_MS + VERIFY_AFTER_MS;
                    if (viaToggle) {
                        Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
                        Thread.sleep(TOGGLE_GAP_MS);
                    }
                    Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                    Thread.sleep(VERIFY_AFTER_MS);
                    Boolean ok = adbOnCable(app);
                    if (ok == null) {
                        Journal.log(app, "UsbDebug[" + origin + "] essai " + attempt + " : débogage remis à 1,"
                                + " mais pas de câble — invérifiable, on s'arrête là");
                        return;
                    }
                    if (ok) {
                        Journal.log(app, "UsbDebug[" + origin + "] ⭐ ADB DE RETOUR SUR LE CÂBLE à l'essai "
                                + attempt + " — c'est " + origin + " qui a fait le travail");
                        return;
                    }
                    Journal.log(app, "UsbDebug[" + origin + "] essai " + attempt + " : adb toujours ABSENT"
                            + " de l'USB " + VERIFY_AFTER_MS / 1000 + " s après — "
                            + (attempt < MAX_ATTEMPTS ? "on recommence par une bascule 0 → 1" : "abandon"));
                    viaToggle = true;
                }
            } catch (Throwable t) {
                Journal.warn(app, "UsbDebug[" + origin + "] réparation échouée", t);
            } finally {
                try {
                    // Ne jamais laisser le débogage éteint derrière nous.
                    Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                } catch (Throwable ignored) {
                    // rien de plus à faire
                }
            }
        }, "adbtiles-usbdebug").start();
    }
}

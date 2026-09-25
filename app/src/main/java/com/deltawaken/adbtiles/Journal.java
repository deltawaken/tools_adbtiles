package com.deltawaken.adbtiles;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Journal de {@link Restore} : logcat <b>et</b> un fichier qui survit à la rotation du tampon.
 *
 * <p><b>Pourquoi le fichier.</b> Mesuré sur HONOR 70 Lite le 2026-09-25 : le tampon {@code main}
 * de logcat fait <b>256 Kio</b> et {@code persist.logd.size} n'est pas écrivable depuis adb. Au
 * démarrage, la ROM écrit assez pour que la ligne de la tuile (+57 s) ait disparu quand on lit le
 * journal après le retour du lien — on a cru à un trou dans le code, c'était le tampon. Une ligne
 * qui n'est plus lisible n'est pas une preuve d'absence.
 *
 * <p>Le fichier vit dans {@code getExternalFilesDir(null)/restore.log}, lisible par le shell adb :
 * {@code adb shell cat /sdcard/Android/data/com.deltawaken.adbtiles/files/restore.log}. Chaque ligne
 * porte l'heure murale, l'uptime (pour dater par rapport au démarrage sans dépendre de l'horloge,
 * que {@code TIME_SET} recale) et le pid (pour voir un processus qui a été redémarré).
 *
 * <p>Outil de banc : l'écriture est synchrone, sur le fil appelant, et ne doit jamais faire
 * échouer l'appelant.
 */
final class Journal {

    private static final String TAG = "AdbTiles";
    private static final String FILE = "restore.log";

    private Journal() {
    }

    static void log(Context context, String message) {
        Log.i(TAG, message);
        write(context, message);
    }

    static void warn(Context context, String message, Throwable t) {
        Log.w(TAG, message, t);
        write(context, message + " — " + t);
    }

    private static void write(Context context, String message) {
        try {
            File dir = context.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            String line = String.format(Locale.ROOT, "%s up=%ds pid=%d %s%n",
                    stamp, SystemClock.elapsedRealtime() / 1000, Process.myPid(), message);
            try (FileWriter w = new FileWriter(new File(dir, FILE), true)) {
                w.write(line);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Journal: écriture impossible — " + t);
        }
    }
}

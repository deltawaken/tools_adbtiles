package com.deltawaken.adbtiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Chemin de secours pour {@link Restore} : le démarrage du téléphone.
 *
 * <p>⚠️ <b>Ce n'est pas le chemin principal</b>, et il ne faut pas compter dessus. Mesuré le
 * 2026-09-25 sur MagicOS 8.0 : une application laissée en « Gérée automatiquement » — l'état
 * d'installation, celui de tout le monde — ne reçoit <b>aucune</b> diffusion système au démarrage
 * (0 sur 3 essais, contre 5 sur 5 la même application une fois le réglage posé). iAware nomme
 * lui-même son refus dans les journaux. Le chemin qui tient est
 * {@code TileService.onCreate()} : SystemUI lie les tuiles sans passer par une diffusion.
 *
 * <p>On garde celui-ci quand même parce qu'il est gratuit et qu'il est <b>plus rapide</b> là où il
 * fonctionne (AOSP, Pixel, One UI) : il n'attend pas que SystemUI relie une tuile.
 *
 * <p>⛔ <b>Pas de {@code LOCKED_BOOT_COMPLETED}</b> : il n'est livré qu'aux composants
 * {@code directBootAware}, et l'intention de l'utilisateur vit dans des SharedPreferences en
 * stockage chiffré par identifiant. Avant déverrouillage, {@link Tcpip#isDesiredOpen} lirait
 * « faux » et on ne restaurerait rien — un chemin qui ne peut pas aboutir ne mérite pas d'être
 * déclaré.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Journal.log(context, "BOOT_COMPLETED : diffusion reçue (" + intent.getAction() + ")");
        // On ne bloque pas : maybeRestore rend la main tout de suite et travaille sur un fil.
        // Un receveur a une dizaine de secondes, et attendre le Wi-Fi en prend jusqu'à 90.
        Restore.maybeRestore(context, Restore.ORIGIN_BOOT);
    }
}

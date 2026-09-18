package com.deltawaken.adbtiles;

import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Tuile « Débogage USB » : bascule {@code Settings.Global.ADB_ENABLED}.
 *
 * <p>Sert d'interrupteur du port ouvert par {@code adb tcpip 5555} : couper le débogage USB arrête
 * adbd et ferme le port, le rallumer le rouvre tant que le téléphone n'a pas redémarré.
 */
public class UsbDebuggingTileService extends TileService {

    private static final String ADB_ENABLED = Settings.Global.ADB_ENABLED;
    private static final String DEV_ENABLED = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED;

    /** Temps laissé à adbd pour s'arrêter ou démarrer ; les appuis sont ignorés pendant ce temps. */
    private static final long SETTLE_MS = 2000;

    private final Runnable refresher = this::refresh;

    /** Vrai tant que le système a refusé la dernière écriture, malgré la permission. */
    private boolean writeRefused;

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tcpip.addListener(refresher);
        refresh();
    }

    @Override
    public void onStopListening() {
        Tcpip.removeListener(refresher);
        super.onStopListening();
    }

    @Override
    public void onClick() {
        if (!isDeveloperOptionsEnabled() || !hasWriteSecureSettings() || writeRefused
                || System.currentTimeMillis() < Tcpip.usbBusyUntil || Tcpip.tcpipBusy) {
            return;
        }
        boolean target = !isAdbEnabled();
        try {
            Settings.Global.putInt(getContentResolver(), ADB_ENABLED, target ? 1 : 0);
        } catch (SecurityException | IllegalArgumentException e) {
            // Politique d'entreprise (DISALLOW_DEBUGGING_FEATURES) ou restriction de la ROM.
            writeRefused = true;
        }
        Tcpip.usbBusyUntil = System.currentTimeMillis() + SETTLE_MS;
        if (target) {
            // adbd redémarre et rouvre 5555 s'il était en mode TCP : la tuile TCP/IP attend.
            Tcpip.awaitAdbd();
        } else {
            Tcpip.portOpen = false;
            Tcpip.notifyChanged();
        }
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        if (!isDeveloperOptionsEnabled()) {
            setTile(tile, Tile.STATE_UNAVAILABLE, R.string.subtitle_no_dev_options);
        } else if (!hasWriteSecureSettings()) {
            setTile(tile, Tile.STATE_UNAVAILABLE, R.string.subtitle_no_permission);
        } else if (writeRefused) {
            setTile(tile, Tile.STATE_UNAVAILABLE, R.string.subtitle_write_refused);
        } else if (isAdbEnabled()) {
            setTile(tile, Tile.STATE_ACTIVE, R.string.subtitle_on);
        } else {
            setTile(tile, Tile.STATE_INACTIVE, R.string.subtitle_off);
        }
        tile.updateTile();
    }

    private void setTile(Tile tile, int state, int subtitleRes) {
        tile.setState(state);
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getString(subtitleRes));
        tile.setContentDescription(
                getString(R.string.tile_label) + ", " + getString(subtitleRes));
    }

    private boolean isAdbEnabled() {
        return Settings.Global.getInt(getContentResolver(), ADB_ENABLED, 0) == 1;
    }

    private boolean isDeveloperOptionsEnabled() {
        return Settings.Global.getInt(getContentResolver(), DEV_ENABLED, 0) == 1;
    }

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }
}

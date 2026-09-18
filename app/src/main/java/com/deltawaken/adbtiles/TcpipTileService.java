package com.deltawaken.adbtiles;

import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Tuile « Débogage TCP/IP » : ouvre et ferme le port TCP d'adbd, comme {@code adb tcpip <port>} et
 * {@code adb usb}. Dépend entièrement du débogage USB : sans lui, adbd est arrêté.
 *
 * <p>Tout l'état vit dans {@link Tcpip} : SystemUI recrée cette tuile toutes les cinq secondes.
 */
public class TcpipTileService extends TileService {

    private static final String TAG = "AdbTiles";

    private final Runnable refresher = this::refresh;

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tcpip.loadPort(this);
        Tcpip.addListener(refresher);
        refresh();
        if (!Tcpip.tcpipBusy) {
            Tcpip.probeAsync();
        }
    }

    @Override
    public void onStopListening() {
        Tcpip.removeListener(refresher);
        super.onStopListening();
    }

    @Override
    public void onClick() {
        // Une opération en cours, ou le débogage USB qui vient de changer : l'appui est ignoré.
        if (Tcpip.tcpipBusy || System.currentTimeMillis() < Tcpip.usbBusyUntil || !isUsable()) {
            return;
        }
        Tcpip.tcpipBusy = true;
        Tcpip.tcpipFailed = false;
        Tcpip.notifyChanged();
        new Thread(() -> {
            boolean ok = true;
            try {
                // Sonder d'abord : l'état en mémoire peut dater d'avant un redémarrage d'adbd.
                boolean open = Tcpip.isPortOpen();
                if (open) {
                    Tcpip.close(this);
                    ok = Tcpip.waitForPort(false, 8000);
                } else if (Tcpip.isWifiConnected(this)) {
                    Tcpip.open(this);
                    ok = Tcpip.waitForPort(true, 8000);
                }
            } catch (Exception e) {
                Log.w(TAG, "Bascule TCP/IP échouée", e);
                ok = false;
            } finally {
                Tcpip.portOpen = Tcpip.isPortOpen();
                Tcpip.tcpipFailed = !ok;
                Tcpip.tcpipBusy = false;
                Tcpip.notifyChanged();
            }
        }, "adbtiles-tcpip").start();
    }

    private boolean isUsable() {
        return isDeveloperOptionsEnabled() && isAdbEnabled() && hasWriteSecureSettings()
                && Tcpip.isAuthorized(this) && !Tcpip.networkBlocked;
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        Boolean portOpen = Tcpip.portOpen;
        String subtitle;
        int state;
        if (!isDeveloperOptionsEnabled()) {
            state = Tile.STATE_UNAVAILABLE;
            subtitle = getString(R.string.subtitle_no_dev_options);
        } else if (!isAdbEnabled()) {
            state = Tile.STATE_UNAVAILABLE;
            subtitle = getString(R.string.tile_label) + " · " + getString(R.string.subtitle_off);
        } else if (!hasWriteSecureSettings()) {
            state = Tile.STATE_UNAVAILABLE;
            subtitle = getString(R.string.subtitle_no_permission);
        } else if (!Tcpip.isAuthorized(this) || Tcpip.networkBlocked) {
            // Réseau de l'app bloqué en veille : l'écran de l'app propose l'exemption.
            state = Tile.STATE_UNAVAILABLE;
            subtitle = getString(R.string.subtitle_setup_needed);
        } else if (Tcpip.tcpipBusy || portOpen == null) {
            state = Boolean.TRUE.equals(portOpen) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
            subtitle = getString(R.string.subtitle_working);
        } else if (portOpen) {
            state = Tile.STATE_ACTIVE;
            subtitle = Tcpip.tcpipFailed ? getString(R.string.subtitle_failed)
                    : getString(R.string.subtitle_port_open, Tcpip.port);
        } else if (!Tcpip.isWifiConnected(this)) {
            state = Tile.STATE_UNAVAILABLE;
            subtitle = getString(R.string.subtitle_wifi_needed);
        } else {
            state = Tile.STATE_INACTIVE;
            subtitle = getString(Tcpip.tcpipFailed ? R.string.subtitle_failed : R.string.subtitle_off);
        }
        tile.setState(state);
        tile.setLabel(getString(R.string.tcpip_tile_label));
        tile.setSubtitle(subtitle);
        tile.setContentDescription(getString(R.string.tcpip_tile_label) + ", " + subtitle);
        tile.updateTile();
    }

    private boolean isAdbEnabled() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    private boolean isDeveloperOptionsEnabled() {
        return Settings.Global.getInt(getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }
}

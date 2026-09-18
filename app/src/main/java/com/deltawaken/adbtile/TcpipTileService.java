package com.deltawaken.adbtile;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Tuile « Débogage TCP/IP » : ouvre et ferme le port 5555 d'adbd, comme {@code adb tcpip 5555} et
 * {@code adb usb}. Dépend entièrement du débogage USB : sans lui, adbd est arrêté.
 */
public class TcpipTileService extends TileService {

    private static final String TAG = "AdbTile";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;

    /** État du port, sondé hors du fil principal ; null tant qu'on ne sait pas. */
    private volatile Boolean portOpen;
    private volatile boolean busy;
    private volatile boolean failed;

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (observer == null) {
            observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    probe();
                }
            };
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observer);
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), false, observer);
        }
        refresh();
        probe();
    }

    @Override
    public void onStopListening() {
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        if (busy || portOpen == null || !isUsable()) {
            return;
        }
        boolean open = portOpen;
        if (!open && !Tcpip.isWifiConnected(this)) {
            return;
        }
        busy = true;
        failed = false;
        refresh();
        new Thread(() -> {
            boolean ok;
            try {
                if (open) {
                    Tcpip.close(this);
                } else {
                    Tcpip.open(this);
                }
                ok = Tcpip.waitForPort(!open, 8000);
            } catch (Exception e) {
                Log.w(TAG, "Bascule TCP/IP échouée", e);
                ok = false;
            }
            portOpen = Tcpip.isPortOpen();
            failed = !ok;
            busy = false;
            handler.post(this::refresh);
        }, "adbtile-tcpip").start();
    }

    private void probe() {
        new Thread(() -> {
            portOpen = Tcpip.isPortOpen();
            handler.post(this::refresh);
        }, "adbtile-probe").start();
    }

    private boolean isUsable() {
        return isDeveloperOptionsEnabled() && isAdbEnabled() && hasWriteSecureSettings()
                && Tcpip.isAuthorized(this);
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
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
        } else if (!Tcpip.isAuthorized(this)) {
            state = Tile.STATE_UNAVAILABLE;
            subtitle = getString(R.string.subtitle_setup_needed);
        } else if (busy || portOpen == null) {
            state = tile.getState() == Tile.STATE_ACTIVE ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
            subtitle = getString(R.string.subtitle_working);
        } else if (portOpen) {
            state = Tile.STATE_ACTIVE;
            subtitle = failed ? getString(R.string.subtitle_failed) : getString(R.string.subtitle_port_open);
        } else if (!Tcpip.isWifiConnected(this)) {
            state = Tile.STATE_UNAVAILABLE;
            subtitle = getString(R.string.subtitle_wifi_needed);
        } else {
            state = Tile.STATE_INACTIVE;
            subtitle = failed ? getString(R.string.subtitle_failed) : getString(R.string.subtitle_off);
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

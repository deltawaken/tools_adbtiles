package com.deltawaken.adbtile;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.deltawaken.adbtile.adb.AdbClient;

import java.net.ConnectException;

/**
 * Le seul écran de l'app : l'état des deux tuiles, suivi en direct, les deux prérequis de la tuile
 * TCP/IP, et le bouton qui fait autoriser la clé de l'app par adbd, une fois par téléphone.
 */
public class MainActivity extends Activity {

    private static final long PORT_POLL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateStatus();
        }
    };
    /** Sonde le port tant que l'écran est visible : la tuile peut le basculer à tout moment. */
    private final Runnable portPoll = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> {
                boolean open = Tcpip.isPortOpen();
                handler.post(() -> {
                    portOpen = open;
                    updateStatus();
                });
            }, "adbtile-poll").start();
            handler.postDelayed(this, PORT_POLL_MS);
        }
    };

    private Boolean portOpen;
    private TextView usbStatus;
    private TextView tcpipStatus;
    private TextView permissionStatus;
    private TextView keyStatus;
    private TextView hint;
    private TextView result;
    private Button authorize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int padding = dp(24);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(padding, padding, padding, padding);

        TextView title = text(getString(R.string.app_name), 24);
        usbStatus = text("", 18);
        tcpipStatus = text("", 18);
        permissionStatus = text("", 16);
        permissionStatus.setTextIsSelectable(true);
        keyStatus = text("", 16);
        hint = text(getString(R.string.main_authorize_hint), 14);
        authorize = new Button(this);
        authorize.setText(R.string.main_authorize);
        authorize.setOnClickListener(v -> startAuthorization());
        result = text("", 14);
        result.setMovementMethod(LinkMovementMethod.getInstance());

        column.addView(title);
        column.addView(usbStatus);
        column.addView(tcpipStatus);
        column.addView(permissionStatus);
        column.addView(keyStatus);
        column.addView(hint);
        column.addView(authorize);
        column.addView(result);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observer);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), false, observer);
        updateStatus();
        handler.post(portPoll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(portPoll);
        getContentResolver().unregisterContentObserver(observer);
        super.onPause();
    }

    private void updateStatus() {
        boolean adbEnabled = Settings.Global.getInt(getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) == 1;
        usbStatus.setText(getString(R.string.status_line, getString(R.string.tile_label),
                getString(adbEnabled ? R.string.subtitle_on : R.string.subtitle_off)));
        String tcpip = portOpen == null ? getString(R.string.subtitle_working)
                : getString(portOpen ? R.string.subtitle_port_open : R.string.subtitle_off);
        tcpipStatus.setText(getString(R.string.status_line, getString(R.string.tcpip_tile_label), tcpip));

        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(granted ? R.string.main_perm_ok : R.string.main_perm_missing);
        boolean authorized = Tcpip.isAuthorized(this);
        keyStatus.setText(authorized ? R.string.main_key_ok : R.string.main_key_missing);
        // Une fois la clé autorisée, le bouton n'a plus d'usage : on le masque.
        int setupVisibility = authorized ? View.GONE : View.VISIBLE;
        hint.setVisibility(setupVisibility);
        authorize.setVisibility(setupVisibility);
    }

    private void startAuthorization() {
        authorize.setEnabled(false);
        result.setText(R.string.main_working);
        new Thread(() -> {
            String message;
            try {
                Tcpip.authorize(this);
                message = getString(R.string.main_key_ok);
            } catch (ConnectException e) {
                message = getString(R.string.main_port_closed);
            } catch (AdbClient.AuthException e) {
                message = getString(R.string.main_refused);
            } catch (Exception e) {
                message = getString(R.string.main_error, String.valueOf(e.getMessage()));
            }
            String shown = message;
            runOnUiThread(() -> {
                result.setText(shown);
                authorize.setEnabled(true);
                updateStatus();
            });
        }, "adbtile-authorize").start();
    }

    private TextView text(String value, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setPadding(0, 0, 0, dp(16));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

package com.deltawaken.adbtiles;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.deltawaken.adbtiles.adb.AdbClient;

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
            }, "adbtiles-poll").start();
            handler.postDelayed(this, PORT_POLL_MS);
        }
    };

    private Boolean portOpen;
    private TextView usbStatus;
    private TextView tcpipStatus;
    private TextView permissionStatus;
    private TextView keyStatus;
    private TextView batteryStatus;
    private Button batteryAllow;
    private TextView hint;
    private EditText portField;
    private TextView portNote;
    private TextView result;
    private Button authorize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int padding = dp(24);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(padding, padding, padding, padding);

        usbStatus = text("", 18);
        tcpipStatus = text("", 18);
        TextView portLabel = text(getString(R.string.main_port_label), 14);
        portLabel.setPadding(0, 0, 0, 0);
        portField = new EditText(this);
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portField.setSingleLine(true);
        Tcpip.loadPort(this);
        portField.setText(String.valueOf(Tcpip.port));
        portField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onPortEdited(s.toString());
            }
        });
        portNote = text("", 14);
        permissionStatus = text("", 16);
        permissionStatus.setTextIsSelectable(true);
        keyStatus = text("", 16);
        batteryStatus = text("", 16);
        batteryAllow = new Button(this);
        batteryAllow.setText(R.string.main_battery_allow);
        batteryAllow.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.fromParts("package", getPackageName(), null))));
        hint = text(getString(R.string.main_authorize_hint), 14);
        authorize = new Button(this);
        authorize.setText(R.string.main_authorize);
        authorize.setOnClickListener(v -> startAuthorization());
        result = text("", 14);
        result.setMovementMethod(LinkMovementMethod.getInstance());

        column.addView(usbStatus);
        column.addView(tcpipStatus);
        column.addView(portLabel);
        column.addView(portField);
        column.addView(portNote);
        column.addView(permissionStatus);
        column.addView(keyStatus);
        column.addView(batteryStatus);
        column.addView(batteryAllow);
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
        Tcpip.loadPort(this);
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
                : portOpen ? getString(R.string.subtitle_port_open, Tcpip.port)
                : getString(R.string.subtitle_off);
        tcpipStatus.setText(getString(R.string.status_line, getString(R.string.tcpip_tile_label), tcpip));

        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(granted ? R.string.main_perm_ok : R.string.main_perm_missing);
        boolean authorized = Tcpip.isAuthorized(this);
        keyStatus.setText(authorized ? R.string.main_key_ok : R.string.main_key_missing);
        PowerManager power = getSystemService(PowerManager.class);
        boolean unrestricted = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        batteryStatus.setText(unrestricted ? R.string.main_battery_ok : R.string.main_battery_restricted);
        batteryAllow.setVisibility(unrestricted ? View.GONE : View.VISIBLE);
        // Le port ne se change que TCP/IP éteint : adbd écoute sur l'ancien tant qu'il est ouvert.
        boolean portEditable = Boolean.FALSE.equals(portOpen);
        portField.setEnabled(portEditable);
        if (!portEditable) {
            portNote.setText(R.string.main_port_locked);
        } else if (!isValidPortText(portField.getText().toString())) {
            portNote.setText(R.string.main_port_invalid);
        } else {
            portNote.setText("");
        }
        // Une fois la clé autorisée, le bouton n'a plus d'usage : on le masque.
        int setupVisibility = authorized ? View.GONE : View.VISIBLE;
        hint.setVisibility(setupVisibility);
        authorize.setVisibility(setupVisibility);
    }

    private void onPortEdited(String value) {
        if (Boolean.FALSE.equals(portOpen) && isValidPortText(value)) {
            int port = Integer.parseInt(value);
            if (port != Tcpip.port) {
                Tcpip.setPort(this, port);
                portOpen = false; // setPort a oublié l'état : le nouveau port n'a pas encore été sondé
            }
        }
        updateStatus();
    }

    private static boolean isValidPortText(String value) {
        try {
            return Tcpip.isValidPort(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
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
        }, "adbtiles-authorize").start();
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

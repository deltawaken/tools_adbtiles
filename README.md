# ADB Tiles

Two Quick Settings tiles to control adb from the phone itself, without root and without Shizuku.

| Tile | What it does |
|---|---|
| **USB debugging** | Turns `adb_enabled` on and off — the master switch of adbd. |
| **TCP/IP debugging** | Opens and closes adbd's TCP port, like `adb tcpip <port>` and `adb usb`, from the phone. |

The typical use: reaching your phone with adb **over a VPN** (WireGuard, Tailscale…) while you are away,
and closing the port with one tap when you no longer need it.

## How it works

- **USB debugging** writes `Settings.Global.ADB_ENABLED`, which needs `WRITE_SECURE_SETTINGS`. Android
  does not let an app ask for it: you grant it once with adb (see below).
- **TCP/IP debugging** ships a tiny adb client, written from scratch with no dependency. The app talks
  to the adbd of its own phone:
  - to **close** the port, it connects to `127.0.0.1:<port>` and sends `usb:`;
  - to **open** it (for instance after a reboot), it turns wireless debugging on for a few seconds,
    finds its random port over mDNS, connects over TLS and sends `tcpip:<port>`.

  The app's RSA key is authorized once, through the usual « Allow USB debugging? » dialog. adbd then
  also accepts it over TLS, so no pairing code is needed.
- Turning USB debugging off stops adbd and closes the port. Turning it back on restarts adbd, which
  reopens the port by itself — until the phone reboots.
- **After a reboot, the app puts things back the way you left them** — without waiting for a
  system broadcast, which some ROMs (Honor/Huawei with iAware) never deliver to an app in its
  install state. SystemUI binds every tile that sits in the Quick Settings panel a few seconds after
  boot; that binding starts the app, and the app then:
  - turns **USB debugging back on** if you had it on and the ROM dropped it (measured on an
    EMUI 10 phone whose persisted USB config had lost `adb`: back on the cable 6 s after the tile
    was bound), checking that adb is really back on the cable and retrying up to three times;
  - reopens the **TCP/IP port** if you had it open (Android 11+, needs Wi-Fi), the same way the
    tile does.
  It only restores what you had: a port you closed stays closed, debugging you turned off stays off.
  A `BOOT_COMPLETED` receiver does the same where ROMs deliver it. Every decision is written to
  `Android/data/com.deltawaken.adbtiles/files/restore.log` (readable with `adb shell cat`), with the
  uptime and which path woke the app — logcat's boot buffer is too small on some ROMs to be trusted.

## Limits

- **Opening the port needs a Wi-Fi connection.** Android refuses to start wireless debugging without
  one, even with a hotspot on. Once open, the port stays reachable without Wi-Fi (mobile data + VPN).
  Closing it never needs Wi-Fi.
- **A reboot closes the port.** The app reopens it by itself a minute or so after boot **if at least
  one of its tiles is in the Quick Settings panel** and the phone is on Wi-Fi; otherwise reopen it
  with the tile. No tile in the panel means no wake-up, on any ROM.
- The tiles do nothing before the first unlock after a reboot (on phones with a lock credential).
- **Battery optimization cuts the app's network when idle**, even to `127.0.0.1`, and the TCP/IP tile
  can no longer see adbd. The app shows this and offers to exempt itself.
- The TCP/IP tile needs Android 11 or later (wireless debugging). The USB tile works from Android 10.

## Setup

1. Build or download the APK, then install it:
   ```sh
   adb install app-release.apk
   ```
2. Grant the settings permission, once:
   ```sh
   adb shell pm grant com.deltawaken.adbtiles android.permission.WRITE_SECURE_SETTINGS
   ```
   It survives reboots and updates, not an uninstall.
3. Open the port once from your computer (`adb tcpip 5555`), open **ADB Tiles**, tap **Authorize this
   app**, tick « Always allow », then Allow.
4. In the app, allow it in the background (battery unrestricted).
5. Add both tiles to the Quick Settings panel with the pencil icon.

The port (5555 by default) can be changed in the app while TCP/IP debugging is off.

## Security

With TCP/IP debugging on, adbd listens on **every** network interface, public Wi-Fi included. Only
computers whose key you authorized can connect, but keep the port closed when you don't need it —
that is what the tile is for.

## Build

```sh
./gradlew assembleRelease
```

Plain Java, no dependency. The release APK is about 85 KB, signed with the debug key.

## Translations

The tile labels use the exact wording of the matching Android settings, in 50 languages.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). [NOTICE](NOTICE) adds, under section 7, that the name
« Deltawaken » may not be used by modified versions.

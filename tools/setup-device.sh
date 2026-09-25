#!/usr/bin/env bash
# Prépare un téléphone pour qu'ADB Tiles rouvre son port TCP/IP après un redémarrage.
#
# Fait tout ce qui ne demande pas d'humain, puis DIT ce qui en demande un.
# Usage :  tools/setup-device.sh <serial-ou-ip:5555> [chemin/vers/app-release.apk]
#
# ⛔ Trois pièges, tous rencontrés le 2026-09-25 et tous coûteux :
#   1. `WRITE_SECURE_SETTINGS` NE SURVIT PAS à une réinstallation. Sans elle les deux tuiles
#      passent en grisé et `Restore` s'arrête. Ce script la réaccorde systématiquement.
#   2. `adb tcpip 5555` FAIT DISPARAÎTRE le point USB sur certaines ROM (vivo Y19s mesuré) : le
#      téléphone doit ensuite être adressé par son IP. Ce script ne lance donc jamais `tcpip` de
#      lui-même — il le dit.
#   3. Tout appel `adb` dans une boucle `while read` VOLE le stdin de la boucle. D'où les
#      `</dev/null` partout. Voir tools_dontkillrisetime/bin/_bench.sh.
set -u

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG=com.deltawaken.adbtiles
DEV="${1:-}"
APK="${2:-$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk}"

[ -z "$DEV" ] && { echo "usage: $0 <serial|ip:5555> [apk]" >&2; exit 64; }

a() { timeout 30 "$ADB" -s "$DEV" "$@" </dev/null 2>&1; }
say() { printf '%s\n' "$*"; }

say "=== $DEV"
MODEL=$(a shell getprop ro.product.model | tr -d '\r')
[ -z "$MODEL" ] && { say "  ⛔ injoignable"; exit 1; }
API=$(a shell getprop ro.build.version.sdk | tr -d '\r')
FIRST=$(a shell getprop ro.product.first_api_level | tr -d '\r')
say "  $MODEL — API $API (livré avec API ${FIRST:-?})"

if [ "${API:-0}" -lt 30 ]; then
  say "  ⛔ API $API < 30 : le débogage sans fil n'existe pas. Ce téléphone restera AU CÂBLE."
  exit 3
fi

# --- 1. installer, sans rien perdre -----------------------------------------------------------
if [ -r "$APK" ]; then
  OUT=$(a install -r "$APK")
  case "$OUT" in
    *Success*)                       say "  ✅ APK installée (données conservées : clé et préférences intactes)" ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      say "  ⛔ signature DIFFÉRENTE de la build installée."
      say "     La mettre à jour exige \`pm uninstall\`, ce qui EFFACE la clé adb autorisée —"
      say "     donc un appui humain de réautorisation derrière. DÉCISION DU PORTEUR, pas la mienne." ;;
    *) say "  ⚠️ install : $(printf '%s' "$OUT" | tail -1)" ;;
  esac
else
  say "  ⚠️ APK absente ($APK) — étape sautée"
fi

# --- 2. les autorisations que la machine peut donner -------------------------------------------
a shell "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS" >/dev/null
a shell "dumpsys deviceidle whitelist +$PKG" >/dev/null
WSS=$(a shell "dumpsys package $PKG" | grep WRITE_SECURE_SETTINGS | grep -oE 'granted=(true|false)' | head -1)
BAT=$(a shell "dumpsys deviceidle whitelist" | grep -c "$PKG")
say "  WRITE_SECURE_SETTINGS : ${WSS:-absente}   exemption batterie : $BAT"

# --- 3. la tuile dans le volet, sans humain ----------------------------------------------------
# `settings put secure` marche ici parce que le SHELL adb détient WRITE_SECURE_SETTINGS. Une app
# ordinaire ne peut PAS écrire cette clé : pour Risetime il faudrait requestAddTileService() (API
# 33+), qui affiche une demande à l'utilisateur.
TILES=$(a shell 'settings get secure sysui_qs_tiles' | tr -d '\r')
if printf '%s' "$TILES" | grep -q "$PKG"; then
  say "  ✅ tuile déjà dans le volet"
elif [ -n "$TILES" ] && [ "$TILES" != "null" ]; then
  a shell "settings put secure sysui_qs_tiles 'custom($PKG/.TcpipTileService),$TILES'" >/dev/null
  RELU=$(a shell 'settings get secure sysui_qs_tiles' | grep -c "$PKG")
  say "  tuile ajoutée au volet — relu : $RELU (1 = en place)"
else
  say "  ⚠️ sysui_qs_tiles vide ou absente sur cette ROM : tuile à ajouter à la main"
fi

# --- 4. ce qui reste à un humain ---------------------------------------------------------------
DEV_ON=$(a shell settings get global development_settings_enabled | tr -d '\r')
ADB_ON=$(a shell settings get global adb_enabled | tr -d '\r')
say ""
say "  --- ce qui reste, et qui demande une main ---"
[ "$DEV_ON" != "1" ] && say "  ⛔ options développeur DÉSACTIVÉES → à activer dans les réglages"
[ "$ADB_ON" != "1" ] && say "  ⛔ débogage USB DÉSACTIVÉ → à activer dans les réglages"
say "  • clé adb : NON LISIBLE d'ici (l'app n'est pas debuggable, donc pas de \`run-as\`)."
say "    La lire et l'accorder : python3 tools/authorize.py $DEV"
say "    Elle exige l'écran DÉVERROUILLÉ et le port DÉJÀ OUVERT (elle passe par le loopback)."
say "  • si le port est fermé : un appui sur la tuile « Débogage TCP/IP », ou \`adb -s $DEV tcpip 5555\`"
say "    ⚠️ \`tcpip\` peut faire disparaître le point USB : réadresser ensuite par l'IP."
say ""
say "  Vérification finale, après un redémarrage :"
say "    adb -s <ip>:5555 logcat -d | grep 'I AdbTiles:'"
say "    → la ligne porte son origine : Restore[TUILE:tcpip] ou Restore[BOOT_COMPLETED]."

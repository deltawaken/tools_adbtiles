#!/bin/sh
# reboot-adbtiles.sh — banc HONOR 70 Lite : par quel chemin adbtiles se réveille
# après un `adb reboot`, et si l'app rouvre elle-même le port adb tcp 5555.
#
# Usage : reboot-adbtiles.sh <label> --expect "<texte>" [--max-wait 420]
#
# ⛔ Ce script REBOOTE le téléphone et lit dessus (adb shell) : c'est son objet.
#    C'est celui qui le LANCE qui touche le téléphone, pas celui qui l'a écrit.
#
# Sorties (dans le cwd) : reboot-<label>-<HHMMSS>.txt (journal humain, tee)
#                          dumps-<label>-<HHMMSS>/     (dumps bruts)
# Code de sortie : 0 = mesure faite (résultat quel qu'il soit)
#                  1 = erreur d'usage / adb ou cible introuvable
#                  2 = abandon AVANT reboot (hygiène : une alarme est armée)
#                  3 = appareil injoignable après reboot, ni USB ni tcpip

set -u

ADB="$HOME/Android/Sdk/platform-tools/adb"
SERIAL="AE9DCP3920206091"
TCPIP_ADDR="192.168.1.159:5555"
PKG="com.deltawaken.adbtiles"
RLOG="/sdcard/Android/data/${PKG}/files/restore.log"
POLL_INTERVAL=5
MIN_RESTORED_WAIT=240
ADB_TIMEOUT=15

# ============================================================
# 0. Arguments
# ============================================================
if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <label> --expect \"<texte>\" [--max-wait 420]" >&2
    exit 1
fi

LABEL="$1"
shift

EXPECT=""
MAXWAIT=420

while [ "$#" -gt 0 ]; do
    case "$1" in
        --expect)
            if [ "$#" -lt 2 ]; then
                echo "⛔ --expect requiert un argument" >&2
                exit 1
            fi
            EXPECT="$2"
            shift 2
            ;;
        --max-wait)
            if [ "$#" -lt 2 ]; then
                echo "⛔ --max-wait requiert un argument" >&2
                exit 1
            fi
            MAXWAIT="$2"
            shift 2
            ;;
        *)
            echo "⛔ Argument inconnu : $1" >&2
            exit 1
            ;;
    esac
done

if [ -z "$EXPECT" ]; then
    echo "⛔ --expect \"<texte>\" est obligatoire (prédiction consignée avant le reboot)" >&2
    exit 1
fi

if [ ! -x "$ADB" ]; then
    FALLBACK="$(command -v adb 2>/dev/null || true)"
    if [ -n "$FALLBACK" ]; then
        ADB="$FALLBACK"
    fi
fi
if [ ! -x "$ADB" ]; then
    echo "⛔ adb introuvable ($HOME/Android/Sdk/platform-tools/adb, ni dans PATH)" >&2
    exit 1
fi

TS="$(date +%H%M%S)"
JOURNAL="reboot-${LABEL}-${TS}.txt"
DUMPDIR="dumps-${LABEL}-${TS}"

if ! mkdir -p "$DUMPDIR"; then
    echo "⛔ impossible de créer $DUMPDIR" >&2
    exit 1
fi

: > "$JOURNAL"

log() {
    printf '%s\n' "$*" | tee -a "$JOURNAL"
}

trim() {
    tr -d '\r'
}

log "=== reboot-adbtiles.sh — label=$LABEL max-wait=${MAXWAIT}s — $(date '+%Y-%m-%d %H:%M:%S') ==="
log "Prédiction (--expect) : $EXPECT"
log "Journal   : $JOURNAL"
log "Dumps     : $DUMPDIR/"

# ---- seul droit adb hors mesure : vérifier que la cible existe ----
if ! timeout "$ADB_TIMEOUT" "$ADB" devices -l 2>/dev/null | grep -q "^${SERIAL}[[:space:]]"; then
    log "⛔ Cible USB $SERIAL absente de « adb devices -l » — abandon avant tout."
    exit 1
fi

# ============================================================
# Helpers adb (chaque appel adb shell est redirigé </dev/null)
# ============================================================
adb_usb_shell() {
    timeout "$ADB_TIMEOUT" "$ADB" -s "$SERIAL" shell "$@" </dev/null 2>/dev/null
}

adb_shell_via() {
    # $1 = serial/adresse active, le reste = la commande shell distante
    _serial="$1"
    shift
    timeout "$ADB_TIMEOUT" "$ADB" -s "$_serial" shell "$@" </dev/null 2>/dev/null
}

# ============================================================
# 1. Ligne de base AVANT (tout par USB)
# ============================================================
log ""
log "--- 1. Ligne de base AVANT (USB) ---"

FP_BEFORE="$(adb_usb_shell getprop ro.build.fingerprint | trim)"
INCR_BEFORE="$(adb_usb_shell getprop ro.build.version.incremental | trim)"
SLOT_BEFORE="$(adb_usb_shell getprop ro.boot.slot_suffix | trim)"
{
    echo "fingerprint=$FP_BEFORE"
    echo "incremental=$INCR_BEFORE"
    echo "slot_suffix=$SLOT_BEFORE"
} > "$DUMPDIR/ota-witnesses-before.txt"
log "Témoins OTA AVANT : fingerprint=$FP_BEFORE incremental=$INCR_BEFORE slot=$SLOT_BEFORE"

NEXT_ALARM_BEFORE="$(adb_usb_shell settings get global next_alarm_formatted | trim)"
PENDING_BEFORE="$(adb_usb_shell dumpsys alarm | grep -oE 'PendingIntentRecord\{[^ ]+ com\.deltawaken\.[a-z.]+' | sort -u)"

log "Hygiène AVANT : next_alarm_formatted=[$NEXT_ALARM_BEFORE]"
if [ -n "$PENDING_BEFORE" ]; then
    log "Hygiène AVANT : PendingIntentRecord deltawaken trouvés :"
    printf '%s\n' "$PENDING_BEFORE" | tee -a "$JOURNAL"
fi

if [ "$NEXT_ALARM_BEFORE" != "null" ] || [ -n "$PENDING_BEFORE" ]; then
    log ""
    log "⛔ ABORT : une alarme est armée (next_alarm_formatted=[$NEXT_ALARM_BEFORE] et/ou PendingIntentRecord présent) — on ne redémarre pas."
    exit 2
fi

TCP_PORT_BEFORE="$(adb_usb_shell getprop service.adb.tcp.port | trim)"
QS_TILES_BEFORE="$(adb_usb_shell settings get secure sysui_qs_tiles | trim)"
ADB_WIFI_BEFORE="$(adb_usb_shell settings get global adb_wifi_enabled | trim)"
PKG_DUMP_BEFORE="$(adb_usb_shell dumpsys package "$PKG" | grep -E 'versionCode|signatures|lastUpdateTime|User 0')"
WHITELIST_BEFORE="$(adb_usb_shell dumpsys deviceidle whitelist | grep deltawaken)"
UPTIME_BEFORE="$(adb_usb_shell uptime | trim)"
PHONE_EPOCH_BEFORE="$(adb_usb_shell date +%s | trim)"
HOST_EPOCH_BEFORE="$(date +%s)"
SKEW_BEFORE=0
case "$PHONE_EPOCH_BEFORE" in
    ''|*[!0-9]*) SKEW_BEFORE="?" ;;
    *) SKEW_BEFORE=$(( PHONE_EPOCH_BEFORE - HOST_EPOCH_BEFORE )) ;;
esac

{
    echo "service.adb.tcp.port=$TCP_PORT_BEFORE"
    echo "sysui_qs_tiles=$QS_TILES_BEFORE"
    echo "adb_wifi_enabled=$ADB_WIFI_BEFORE"
    echo "--- dumpsys package (extrait) ---"
    echo "$PKG_DUMP_BEFORE"
    echo "--- deviceidle whitelist ---"
    echo "$WHITELIST_BEFORE"
    echo "uptime=$UPTIME_BEFORE"
    echo "phone_epoch=$PHONE_EPOCH_BEFORE host_epoch=$HOST_EPOCH_BEFORE skew=${SKEW_BEFORE}s"
} > "$DUMPDIR/state-before.txt"

log "État AVANT : port=$TCP_PORT_BEFORE tiles=$QS_TILES_BEFORE adb_wifi=$ADB_WIFI_BEFORE uptime=[$UPTIME_BEFORE] skew=${SKEW_BEFORE}s"

adb_usb_shell cat "$RLOG" > "$DUMPDIR/restore.log.before" 2>/dev/null
if [ -s "$DUMPDIR/restore.log.before" ]; then
    N0="$(wc -l < "$DUMPDIR/restore.log.before" | tr -d ' ')"
else
    N0=0
    : > "$DUMPDIR/restore.log.before"
fi
log "restore.log AVANT : $N0 ligne(s) (copié dans $DUMPDIR/restore.log.before)"

adb_usb_shell logcat -d -b events -v time | grep -E 'am_proc_start.*adbtiles' | tail -3 > "$DUMPDIR/logcat-events-before.txt" 2>/dev/null
adb_usb_shell logcat -d -v time -s AdbTiles | tail -5 > "$DUMPDIR/logcat-main-before.txt" 2>/dev/null
log "(logcat AVANT gardé pour mémoire seulement — tampon main 256 Kio, une absence n'y prouve rien)"

# ============================================================
# 2. Reboot
# ============================================================
log ""
log "--- 2. Reboot ---"
T0="$(date +%s)"
log "adb -s $SERIAL reboot — T0=$T0 ($(date '+%Y-%m-%d %H:%M:%S'))"
timeout "$ADB_TIMEOUT" "$ADB" -s "$SERIAL" reboot

# ============================================================
# 3+4. Attente du retour (USB + tcpip, même boucle) puis lecture
# ============================================================
log ""
log "--- 3+4. Attente du retour (USB / tcpip) et lecture de restore.log ---"

timeout "$ADB_TIMEOUT" "$ADB" disconnect "$TCPIP_ADDR" >/dev/null 2>&1

USB_BACK_AT=""
TCPIP_BACK_AT=""
PRINTED_LINES="$N0"
STOP_REASON=""

cp "$DUMPDIR/restore.log.before" "$DUMPDIR/restore.log.live" 2>/dev/null || : > "$DUMPDIR/restore.log.live"
: > "$DUMPDIR/logcat-events-seen.txt"

while :; do
    NOW="$(date +%s)"
    ELAPSED=$(( NOW - T0 ))

    if [ -z "$USB_BACK_AT" ]; then
        ST="$(timeout "$ADB_TIMEOUT" "$ADB" -s "$SERIAL" get-state 2>/dev/null | trim)"
        if [ "$ST" = "device" ]; then
            USB_BACK_AT="$ELAPSED"
            log "+${ELAPSED}s USB de retour (get-state=device)"
        fi
    fi

    if [ -z "$TCPIP_BACK_AT" ]; then
        timeout "$ADB_TIMEOUT" "$ADB" connect "$TCPIP_ADDR" >/dev/null 2>&1
        ST="$(timeout "$ADB_TIMEOUT" "$ADB" -s "$TCPIP_ADDR" get-state 2>/dev/null | trim)"
        if [ "$ST" = "device" ]; then
            TCPIP_BACK_AT="$ELAPSED"
            log "+${ELAPSED}s tcpip de retour (get-state=device)"
        fi
    fi

    ACTIVE=""
    if [ -n "$USB_BACK_AT" ]; then
        ACTIVE="$SERIAL"
    elif [ -n "$TCPIP_BACK_AT" ]; then
        ACTIVE="$TCPIP_ADDR"
    fi

    if [ -n "$ACTIVE" ]; then
        adb_shell_via "$ACTIVE" cat "$RLOG" > "$DUMPDIR/restore.log.live.tmp" 2>/dev/null
        if [ -s "$DUMPDIR/restore.log.live.tmp" ]; then
            mv "$DUMPDIR/restore.log.live.tmp" "$DUMPDIR/restore.log.live"
            TOTAL_LINES="$(wc -l < "$DUMPDIR/restore.log.live" | tr -d ' ')"
            if [ "$TOTAL_LINES" -gt "$PRINTED_LINES" ]; then
                sed -n "$((PRINTED_LINES + 1)),\$p" "$DUMPDIR/restore.log.live" | while IFS= read -r NEWLINE; do
                    log "+${ELAPSED}s [restore.log via $ACTIVE] $NEWLINE"
                done
                PRINTED_LINES="$TOTAL_LINES"
            fi
        else
            rm -f "$DUMPDIR/restore.log.live.tmp"
        fi

        UP_NOW="$(adb_shell_via "$ACTIVE" uptime | trim)"
        PORT_NOW="$(adb_shell_via "$ACTIVE" getprop service.adb.tcp.port | trim)"
        log "+${ELAPSED}s [état via $ACTIVE] uptime=[$UP_NOW] port=$PORT_NOW"

        adb_shell_via "$ACTIVE" logcat -d -b events -v time 2>/dev/null \
            | grep -E 'am_proc_start.*adbtiles|am_proc_died.*adbtiles' \
            > "$DUMPDIR/logcat-events-now.txt" 2>/dev/null
        if [ -s "$DUMPDIR/logcat-events-now.txt" ]; then
            grep -Fxv -f "$DUMPDIR/logcat-events-seen.txt" "$DUMPDIR/logcat-events-now.txt" 2>/dev/null > "$DUMPDIR/logcat-events-new.txt" || : > "$DUMPDIR/logcat-events-new.txt"
            if [ -s "$DUMPDIR/logcat-events-new.txt" ]; then
                while IFS= read -r EVLINE; do
                    log "+${ELAPSED}s [events via $ACTIVE] $EVLINE"
                done < "$DUMPDIR/logcat-events-new.txt"
                cat "$DUMPDIR/logcat-events-now.txt" > "$DUMPDIR/logcat-events-seen.txt"
            fi
        fi
    fi

    if [ "$ELAPSED" -ge "$MAXWAIT" ]; then
        STOP_REASON="max-wait atteint (${MAXWAIT}s)"
        break
    fi

    if [ -n "$ACTIVE" ] && tail -n +"$((N0 + 1))" "$DUMPDIR/restore.log.live" 2>/dev/null | grep -q 'PORT 5555 RESTAURÉ' && [ "$ELAPSED" -gt "$MIN_RESTORED_WAIT" ]; then
        STOP_REASON="PORT 5555 RESTAURÉ + plus de ${MIN_RESTORED_WAIT}s écoulées"
        break
    fi

    sleep "$POLL_INTERVAL"
done

log ""
log "Fin de boucle : $STOP_REASON"

if [ -z "$USB_BACK_AT" ] && [ -z "$TCPIP_BACK_AT" ]; then
    log ""
    log "⛔ Appareil injoignable pendant ${MAXWAIT}s (ni USB ni tcpip)."
    log ""
    log "VERDICT: injoignable pendant ${MAXWAIT}s · USB jamais · tcpip jamais"
    exit 3
fi

# ============================================================
# 5. Après le retour : témoins, hygiène, dumps complets
# ============================================================
log ""
log "--- 5. Après le retour ---"

if [ -n "$USB_BACK_AT" ]; then
    FINAL_ACTIVE="$SERIAL"
else
    FINAL_ACTIVE="$TCPIP_ADDR"
fi
log "Voie retenue pour la relecture finale : $FINAL_ACTIVE"

FP_AFTER="$(adb_shell_via "$FINAL_ACTIVE" getprop ro.build.fingerprint | trim)"
INCR_AFTER="$(adb_shell_via "$FINAL_ACTIVE" getprop ro.build.version.incremental | trim)"
SLOT_AFTER="$(adb_shell_via "$FINAL_ACTIVE" getprop ro.boot.slot_suffix | trim)"
{
    echo "fingerprint=$FP_AFTER"
    echo "incremental=$INCR_AFTER"
    echo "slot_suffix=$SLOT_AFTER"
} > "$DUMPDIR/ota-witnesses-after.txt"

OTA_FLAG="aucune"
if [ "$FP_AFTER" != "$FP_BEFORE" ] || [ "$INCR_AFTER" != "$INCR_BEFORE" ] || [ "$SLOT_AFTER" != "$SLOT_BEFORE" ]; then
    OTA_FLAG="⛔ APPAREIL PERDU : mise à jour appliquée"
    log "$OTA_FLAG (avant: fp=$FP_BEFORE incr=$INCR_BEFORE slot=$SLOT_BEFORE / après: fp=$FP_AFTER incr=$INCR_AFTER slot=$SLOT_AFTER)"
else
    log "Témoins OTA inchangés (fingerprint/incremental/slot identiques)"
fi

NEXT_ALARM_AFTER="$(adb_shell_via "$FINAL_ACTIVE" settings get global next_alarm_formatted | trim)"
PENDING_AFTER="$(adb_shell_via "$FINAL_ACTIVE" dumpsys alarm | grep -oE 'PendingIntentRecord\{[^ ]+ com\.deltawaken\.[a-z.]+' | sort -u)"
log "Hygiène APRÈS : next_alarm_formatted=[$NEXT_ALARM_AFTER]"
if [ -n "$PENDING_AFTER" ]; then
    log "Hygiène APRÈS : PendingIntentRecord deltawaken trouvés :"
    printf '%s\n' "$PENDING_AFTER" | tee -a "$JOURNAL"
fi

UPTIME_AFTER="$(adb_shell_via "$FINAL_ACTIVE" uptime | trim)"
TCP_PORT_AFTER="$(adb_shell_via "$FINAL_ACTIVE" getprop service.adb.tcp.port | trim)"
QS_TILES_AFTER="$(adb_shell_via "$FINAL_ACTIVE" settings get secure sysui_qs_tiles | trim)"
log "État APRÈS (via $FINAL_ACTIVE) : uptime=[$UPTIME_AFTER] port=$TCP_PORT_AFTER tiles=$QS_TILES_AFTER"

adb_shell_via "$FINAL_ACTIVE" cat "$RLOG" > "$DUMPDIR/restore.log.after" 2>/dev/null
if [ -s "$DUMPDIR/restore.log.after" ]; then
    cp "$DUMPDIR/restore.log.after" "$DUMPDIR/restore.log.live" 2>/dev/null
fi

adb_shell_via "$FINAL_ACTIVE" logcat -d -b events -v time > "$DUMPDIR/logcat-events-full.txt" 2>/dev/null
adb_shell_via "$FINAL_ACTIVE" logcat -d -v time -s AdbTiles > "$DUMPDIR/logcat-main-full.txt" 2>/dev/null

# ============================================================
# 6. Verdict — construit uniquement depuis restore.log
# ============================================================
log ""
log "--- 6. Verdict ---"

RLOG_FILE="$DUMPDIR/restore.log.after"
if [ ! -s "$RLOG_FILE" ]; then
    RLOG_FILE="$DUMPDIR/restore.log.live"
fi
# ⚠️ Le fichier est cumulatif (append) : ne juger QUE les lignes nouvelles depuis N0, sinon le
# verdict relit l'expérience précédente (constaté sur E2, 2026-09-25).
tail -n +"$((N0 + 1))" "$RLOG_FILE" > "$DUMPDIR/restore.log.new" 2>/dev/null || : > "$DUMPDIR/restore.log.new"
RLOG_FILE="$DUMPDIR/restore.log.new"

extract_up() {
    printf '%s\n' "$1" | sed -n 's/.*up=\([0-9]*\)s\{0,1\}.*/\1/p'
}

ONCREATE_LINE="$(grep -m1 -E 'TUILE:.*onCreate' "$RLOG_FILE" 2>/dev/null)"
if [ -n "$ONCREATE_LINE" ]; then
    ONCREATE_UP="$(extract_up "$ONCREATE_LINE")"
    ONCREATE_TXT="tuile onCreate up=${ONCREATE_UP}s"
else
    ONCREATE_TXT="tuile onCreate absent"
fi

BOOT_LINE="$(grep -m1 'BOOT_COMPLETED : diffusion reçue' "$RLOG_FILE" 2>/dev/null)"
if [ -n "$BOOT_LINE" ]; then
    BOOT_UP="$(extract_up "$BOOT_LINE")"
    BOOT_TXT="BOOT_COMPLETED up=${BOOT_UP}s"
else
    BOOT_TXT="BOOT_COMPLETED absent"
fi

RESTORED_LINE="$(grep -m1 'PORT 5555 RESTAURÉ' "$RLOG_FILE" 2>/dev/null)"
if [ -n "$RESTORED_LINE" ]; then
    RESTORED_UP="$(extract_up "$RESTORED_LINE")"
    RESTORED_ORIGIN="$(printf '%s\n' "$RESTORED_LINE" | sed -n 's/.*Restore\[\([^]]*\)\].*/\1/p')"
    RESTORED_TXT="PORT RESTAURÉ par ${RESTORED_ORIGIN} up=${RESTORED_UP}s"
else
    grep -E 'Restore\[[^]]*\]' "$RLOG_FILE" 2>/dev/null \
        | awk -F'Restore\\[' '{split($2,a,"]"); origin=a[1]; last[origin]=$0} END {for (o in last) print last[o]}' \
        > "$DUMPDIR/restore-last-per-origin.txt"
    if [ -s "$DUMPDIR/restore-last-per-origin.txt" ]; then
        log "port NON restauré — dernière ligne Restore[…] par origine :"
        while IFS= read -r ORIGLINE; do
            log "  $ORIGLINE"
        done < "$DUMPDIR/restore-last-per-origin.txt"
        RESTORED_TXT="port NON restauré (voir $DUMPDIR/restore-last-per-origin.txt)"
    else
        RESTORED_TXT="port NON restauré ; aucune ligne Restore[…] dans restore.log"
    fi
fi

if [ -n "$USB_BACK_AT" ]; then
    USB_TXT="USB +${USB_BACK_AT}s"
else
    USB_TXT="USB jamais"
fi
if [ -n "$TCPIP_BACK_AT" ]; then
    TCPIP_TXT="tcpip +${TCPIP_BACK_AT}s"
else
    TCPIP_TXT="tcpip jamais"
fi

if [ "$OTA_FLAG" = "aucune" ]; then
    OTA_SUFFIX="OTA: aucune"
else
    OTA_SUFFIX="OTA: $OTA_FLAG"
fi

VERDICT="VERDICT: ${ONCREATE_TXT} · ${BOOT_TXT} · ${RESTORED_TXT} · ${USB_TXT} · ${TCPIP_TXT} · ${OTA_SUFFIX}"
if [ "$OTA_FLAG" != "aucune" ]; then
    VERDICT="⛔ APPAREIL PERDU : mise à jour appliquée — ${VERDICT}"
fi

log ""
log "$VERDICT"

exit 0

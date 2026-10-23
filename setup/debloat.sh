#!/usr/bin/env bash
# Switches off the apps in a list (bloatware, trackers, ads) over ADB. It only ever DISABLES
# (`pm disable-user`), never uninstalls, so restore.sh can turn everything back on.
#
#   TV_HOST=192.168.1.50 ./setup/debloat.sh setup/lists/tcl-c6k-google-tv.txt
#   TV_HOST=192.168.1.50 DRY_RUN=1 ./setup/debloat.sh setup/lists/tcl-c6k-google-tv.txt
#
# Before changing anything it saves the TV's package lists to setup/backup/. It refuses lists
# that name Android's core packages (the TV may not boot without them), and leaves alone any
# Google TV home / setup wizard line until Glass Home is your Home (install.sh first).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LIST="${1:?give a list, for example setup/lists/tcl-c6k-google-tv.txt}"
[ -f "$LIST" ] || { echo "No such list: $LIST" >&2; exit 1; }
TV="${TV_HOST:?set TV_HOST to the TV IP address}:${TV_PORT:-5555}"
adb connect "$TV" >/dev/null || true
adb -s "$TV" get-state >/dev/null 2>&1 || { echo "Can't reach $TV. Is debugging on, and did you press Allow?" >&2; exit 1; }
tv() { adb -s "$TV" shell "$@"; }

PKGS=$(grep -v '^\s*#' "$LIST" | awk '{print $1}' | grep . || true)
# Never these: without them Android may not boot, install apps or show a keyboard.
PROTECTED='^(android|com\.android\.(systemui|shell|phone|settings|tv\.settings|permissioncontroller|packageinstaller|vending|inputmethod\..*|providers\.(settings|media|downloads|telephony|tv)|externalstorage|extservices|modulemetadata|networkstack.*|wifi.*|bluetooth|nfc)|com\.google\.android\.(gms|gsf|permissioncontroller|packageinstaller|ext\..*|modulemetadata|overlay\.modules.*|tvrecommendations|leanbackime|inputmethod\..*|tv\.remote\.service|networkstack.*)|app\.glasshome\..*)$'
BAD=$(echo "$PKGS" | grep -E "$PROTECTED" || true)
if [ -n "$BAD" ]; then
    echo "Refusing: this list switches off packages the TV needs:" >&2
    echo "$BAD" | sed 's/^/  /' >&2
    exit 1
fi
HOME_NOW=$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r')
SKIP='^(com\.google\.android\.apps\.tv\.launcherx|com\.google\.android\.tungsten\.setupwraith|com\.google\.android\.tvlauncher)$'
if [ "${HOME_NOW%%/*}" != app.glasshome.launcher ]; then
    echo "Glass Home isn't Home yet, so the stock home and setup wizard stay on (run install.sh first)."
    PKGS=$(echo "$PKGS" | grep -vE "$SKIP" || true)
fi

mkdir -p "$HERE/backup"
STAMP=$(date +%Y%m%d-%H%M%S)
tv pm list packages -d | tr -d '\r' | sort > "$HERE/backup/$STAMP-disabled-before.txt"
tv pm list packages -e | tr -d '\r' | sort > "$HERE/backup/$STAMP-enabled-before.txt"
cp "$LIST" "$HERE/backup/$STAMP-list.txt"
echo "Backed up the package lists to setup/backup/$STAMP-*"

n=0; skipped=0
for pkg in $PKGS; do
    if ! tv pm path "$pkg" >/dev/null 2>&1; then
        skipped=$((skipped + 1)); continue  # not on this TV
    fi
    if [ "${DRY_RUN:-0}" = 1 ]; then echo "would disable $pkg"; continue; fi
    # One package per shell call: chained pm commands can stop after the first on some firmware.
    if tv pm disable-user --user 0 "$pkg" | grep -q "disabled"; then
        echo "disabled $pkg"; n=$((n + 1))
    else
        echo "couldn't disable $pkg (left as it was)"
    fi
done
echo "Done: $n disabled, $skipped not on this TV. Undo any time: ./setup/restore.sh $LIST"

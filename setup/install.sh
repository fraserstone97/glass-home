#!/usr/bin/env bash
# Installs Glass Home and Glass Aerials on an Android TV / Google TV over ADB and sets them up:
# Home, permissions, the remote-buttons helper, voice search and the screen saver.
#
#   TV_HOST=192.168.1.50 ./setup/install.sh              # latest release from GitHub
#   TV_HOST=192.168.1.50 BUILD=1 ./setup/install.sh      # build from this checkout instead
#
# Options (environment variables):
#   TV_PORT=5555            ADB port (Wireless debugging on Android 11+ shows its own port)
#   BUILD=1                 build both apps from source (needs JDK 17 and the Android SDK)
#   APP_NAME="My Home"      with BUILD=1: the name shown for the home screen (default Glass Home)
#   TMDB_KEY=xxxxxxxx       a free TMDB API key: sharper carousel artwork (see the README)
#   AD_BLOCK_DNS=1          Private DNS through AdGuard (blocks most in-app ads and trackers)
#
# Safe to run again: it reinstalls over the top and keeps your settings.
# The Home guard: the stock home is only touched after Glass Home is installed and running, and
# if Home doesn't end up on Glass Home, the stock home is put back.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
REPO="${GLASS_HOME_REPO:-fraserstone97/glass-home}"
TV="${TV_HOST:?set TV_HOST to the TV IP address, for example TV_HOST=192.168.1.50 ./setup/install.sh}:${TV_PORT:-5555}"
HOME_PKG=app.glasshome.launcher
AERIALS_PKG=app.glasshome.aerials
HOME_ACT="$HOME_PKG/.MainActivity"

command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first (see the README)." >&2; exit 1; }
adb connect "$TV" >/dev/null || true
adb -s "$TV" get-state >/dev/null 2>&1 || {
    echo "Can't reach $TV. Is USB debugging (or Wireless debugging) on, and did you press Allow on the TV?" >&2
    exit 1
}
tv() { adb -s "$TV" shell "$@"; }
SDK=$(tv getprop ro.build.version.sdk | tr -d '\r')
[ "$SDK" -ge 24 ] || { echo "This TV runs Android SDK $SDK; Glass Home needs Android 7 (SDK 24) or newer." >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if [ "${BUILD:-0}" = 1 ]; then
    echo "Building from source"
    (cd "$ROOT/launcher" && ./build.sh >/dev/null)
    (cd "$ROOT/aerials" && ./build.sh >/dev/null)
    cp "$ROOT/launcher/build/glass-home.apk" "$ROOT/aerials/build/glass-aerials.apk" "$WORK/"
else
    echo "Downloading the latest release"
    for f in glass-home.apk glass-aerials.apk; do
        curl -fsSL -o "$WORK/$f" "https://github.com/$REPO/releases/latest/download/$f" \
            || { echo "Couldn't download $f. Check your connection, or build it yourself with BUILD=1." >&2; exit 1; }
    done
fi

echo "Installing"
# Push and pm install: adb install can wait forever for an on-screen confirmation on Android 14.
for f in glass-home.apk glass-aerials.apk; do
    adb -s "$TV" push "$WORK/$f" "/data/local/tmp/$f" >/dev/null
    tv pm install -r "/data/local/tmp/$f" | grep -q Success || { echo "Installing $f failed; nothing else was changed." >&2; exit 1; }
    tv rm -f "/data/local/tmp/$f"
done
tv cmd package compile -m speed -f "$HOME_PKG" >/dev/null 2>&1 || true

echo "Permissions"
tv pm grant "$HOME_PKG" android.permission.RECORD_AUDIO 2>/dev/null || true          # voice search
tv pm grant "$HOME_PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true  # Wi-Fi name in Control Centre
tv pm grant "$HOME_PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true # screen saver settings, helper self-repair
tv dpm set-active-admin --user 0 "$HOME_PKG/.SleepReceiver\\\$Admin" >/dev/null 2>&1 || true   # Sleep Timer
# TCL (and some other brands) block apps from starting their own services unless allowed.
tv appops set "$HOME_PKG" AUTO_START allow 2>/dev/null || true
tv appops set "$AERIALS_PKG" AUTO_START allow 2>/dev/null || true

home() { tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r'; }
echo "Home screen"
tv am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$HOME_ACT" | grep -q 'Status: ok' \
    || { echo "Glass Home didn't start; the stock home was left alone." >&2; exit 1; }
STOCK=$(home)
if [ "$STOCK" != "$HOME_ACT" ]; then
    tv cmd package set-home-activity --user 0 "$HOME_ACT" >/dev/null 2>&1 || true
fi
if [ "$(home)" != "$HOME_ACT" ]; then
    STOCK_PKG="${STOCK%%/*}"
    case "$STOCK_PKG" in
        com.google.android.apps.tv.launcherx|com.google.android.tvlauncher|com.google.android.leanbacklauncher)
            # Google TV: its home wins whatever the setting says, so it has to be switched off
            # (disabled, not uninstalled; restore.sh turns it back on).
            echo "Google's home keeps taking over Home; switching it off ($STOCK_PKG)"
            tv pm disable-user --user 0 "$STOCK_PKG" >/dev/null
            tv cmd package set-home-activity --user 0 "$HOME_ACT" >/dev/null 2>&1 || true ;;
    esac
fi
if [ "$(home)" != "$HOME_ACT" ]; then
    echo "Home is $(home), not Glass Home: putting the stock home back." >&2
    [ -n "${STOCK_PKG:-}" ] && tv pm enable --user 0 "$STOCK_PKG" >/dev/null 2>&1 || true
    echo "On Google TV the setup wizard can claim Home; see the README (Troubleshooting)." >&2
    exit 1
fi

echo "Remote buttons, voice search and screen saver"
# Add the remote-buttons helper to any accessibility services already on (don't replace them).
KEYS="$HOME_PKG/$HOME_PKG.KeysService"
CUR=$(tv settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in
    *"$KEYS"*) ;;
    ""|null) tv settings put secure enabled_accessibility_services "$KEYS" ;;
    *) tv settings put secure enabled_accessibility_services "$CUR:$KEYS" ;;
esac
tv settings put secure accessibility_enabled 1
tv cmd role add-role-holder android.app.role.ASSISTANT "$HOME_PKG" 0 >/dev/null 2>&1 || true
tv settings put secure screensaver_enabled 1
tv settings put secure screensaver_components "$AERIALS_PKG/$AERIALS_PKG.AerialDream"
tv settings put secure screensaver_default_component "$AERIALS_PKG/$AERIALS_PKG.AerialDream"

if [ -n "${TMDB_KEY:-}" ]; then
    echo "TMDB artwork"
    tv am start -n "$HOME_PKG/.SettingsActivity" --es tmdb_token "$TMDB_KEY" >/dev/null
    sleep 2
    tv input keyevent BACK
fi
if [ "${AD_BLOCK_DNS:-0}" = 1 ]; then
    echo "Ad-blocking DNS (AdGuard)"
    tv settings put global private_dns_mode hostname
    tv settings put global private_dns_specifier dns.adguard-dns.com
fi

tv input keyevent HOME
echo "Done. Home is Glass Home. Run the first-time setup on the TV, then see the README for the rest."

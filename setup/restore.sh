#!/usr/bin/env bash
# Puts a TV back the way it was: every app in the debloat list switched on again, the stock home
# as Home, Google's assistant and screen saver back, the remote-buttons helper off and normal DNS.
# Glass Home and Glass Aerials stay installed but unused (UNINSTALL=1 removes them too).
#
#   TV_HOST=192.168.1.50 ./setup/restore.sh setup/lists/tcl-c6k-google-tv.txt
#   TV_HOST=192.168.1.50 UNINSTALL=1 ./setup/restore.sh setup/lists/tcl-c6k-google-tv.txt
set -u
LIST="${1:-}"
TV="${TV_HOST:?set TV_HOST to the TV IP address}:${TV_PORT:-5555}"
adb connect "$TV" >/dev/null || true
adb -s "$TV" get-state >/dev/null 2>&1 || { echo "Can't reach $TV. Is debugging on, and did you press Allow?" >&2; exit 1; }
tv() { adb -s "$TV" shell "$@"; }
HOME_PKG=app.glasshome.launcher
AERIALS_PKG=app.glasshome.aerials

if [ -n "$LIST" ]; then
    echo "Turning the disabled apps back on"
    for pkg in $(grep -v '^\s*#' "$LIST" | awk '{print $1}' | grep .); do
        tv pm enable --user 0 "$pkg" >/dev/null 2>&1 && echo "enabled $pkg"
    done
fi
# Google's home, whichever this TV has.
for stock in com.google.android.apps.tv.launcherx/.home.HomeActivity com.google.android.tvlauncher/.MainActivity \
             com.google.android.leanbacklauncher/.MainActivity; do
    pkg="${stock%%/*}"
    if tv pm path "$pkg" >/dev/null 2>&1; then
        tv pm enable --user 0 "$pkg" >/dev/null 2>&1
        tv cmd package set-home-activity --user 0 "$stock" >/dev/null 2>&1 && echo "Home is $pkg again" && break
    fi
done

echo "Google's assistant and screen saver"
tv cmd role remove-role-holder android.app.role.ASSISTANT "$HOME_PKG" 0 >/dev/null 2>&1
if tv pm path com.google.android.katniss >/dev/null 2>&1; then
    tv cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss 0 >/dev/null 2>&1
fi
if tv pm path com.google.android.apps.tv.dreamx >/dev/null 2>&1; then
    tv settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
    tv settings put secure screensaver_default_component com.google.android.apps.tv.dreamx/.service.Backdrop
else
    tv settings delete secure screensaver_components >/dev/null 2>&1
fi

echo "Remote-buttons helper off"
CUR=$(tv settings get secure enabled_accessibility_services | tr -d '\r')
LEFT=$(echo "$CUR" | tr ':' '\n' | grep -v "^$HOME_PKG/" | grep -v '^null$' | paste -sd: -)
if [ -n "$LEFT" ]; then tv settings put secure enabled_accessibility_services "$LEFT"
else tv settings put secure enabled_accessibility_services "''"; tv settings put secure accessibility_enabled 0; fi

echo "Normal DNS"
tv settings put global private_dns_mode opportunistic
tv settings delete global private_dns_specifier >/dev/null 2>&1

if [ "${UNINSTALL:-0}" = 1 ]; then
    echo "Removing Glass Home and Glass Aerials"
    # Glass Home holds device admin for the sleep timer; Android won't uninstall it until that's off.
    tv dpm remove-active-admin "$HOME_PKG/.SleepReceiver\\\$Admin" >/dev/null 2>&1 || true
    tv pm uninstall "$AERIALS_PKG"
    tv pm uninstall "$HOME_PKG" || echo "If it won't uninstall: Settings > Privacy > Device admin apps > Glass Home sleep > Deactivate, then run this again."
fi

echo "Restarting the TV"
tv reboot
echo "Done. After the restart, press Home: the TV's own home should open."

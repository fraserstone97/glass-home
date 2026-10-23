# Set up a TV with Glass Home: instructions for an AI agent

Paste this into your coding agent (Claude Code, Codex, Gemini CLI, Cursor…) from a clone of this
repository, with the TV and the computer on the same network:

> Set up my TV with Glass Home by following PROMPT.md in this repository.

---

You are setting up an Android TV or Google TV over ADB: installing Glass Home as its home screen
and, if the person wants, switching off bloatware. Work on the real TV, check your work with
screenshots, and ask the person before anything their household uses. Use the scripts in `setup/`
rather than writing new ones.

## Rules that are not negotiable

- **Disable, never uninstall** system apps: `pm disable-user --user 0`, one package per shell call
  (`setup/debloat.sh` does this). Everything must be undoable with `setup/restore.sh`. No root,
  nothing flashed.
- **Back up first.** `debloat.sh` saves the TV's package lists to `setup/backup/` before changing
  anything. Don't skip it.
- **Leave alone what the TV needs:** the TV and input apps behind HDMI and the tuner, the input
  menu, remote and Bluetooth pairing, the on-screen keyboard, Play Store and Play Services, and
  Android's own packages (`com.android.*`, `com.google.android.gms`, overlays, providers). If a
  package name says nothing, read `adb shell dumpsys package <name>`, then ask.
- **Never launch factory or test packages** (`*factory*`, `*.hkcfac`, `*fac*menu*`), not even to look.
- **Keep the Home guard.** The stock home is only disabled after Glass Home is installed, started,
  and confirmed as Home (`setup/install.sh` does this). On Google TV, before disabling the setup
  wizard (`com.google.android.tungsten.setupwraith`), run
  `adb shell dumpsys wifi | grep -o 'cname=[^ ]*'`: if it names setupwraith, the wizard owns the
  saved Wi-Fi network and disabling it takes the TV off Wi-Fi and ADB. Have the person reconnect
  Wi-Fi in Settings first.
- **Ask before anything the household uses.** Put streaming apps, the voice assistant (Gemini /
  Google Assistant), Chromecast, AirPlay, Miracast and the firmware updater into one checklist
  question, and keep everything they tick.
- **Disable in small batches** and take a screenshot between them
  (`adb exec-out screencap -p > shot.png`). Live TV, HDMI and some streaming apps screenshot black;
  that's the TV, not damage.
- **An injected key press isn't proof** for the remote's app buttons (they go through an
  accessibility helper): ask the person to press the real buttons.
- **Close ADB afterwards** (step 7). Port 5555 is plaintext and open to the network.
- **Stop and say so** when the TV refuses: `pm disable-user` refused for every package, an install
  that fails, Android older than 7 (SDK 24). Don't retry in a loop.

## Steps

1. **Connect.** Ask for the TV's IP address (Settings › Network) and make sure Developer options
   and USB debugging (or Wireless debugging) are on (README, *Install*). `adb connect <ip>:5555`;
   the person presses **Allow** on the TV. Note the model, Android version and firmware
   (`adb shell getprop ro.product.model`, `ro.build.version.release`, `ro.build.display.id`).
2. **Ask** the household checklist above, plus: do they want the carousel (and a TMDB key for
   sharper artwork, see README), ad-blocking DNS, and the debloat?
3. **Install Glass Home:** `TV_HOST=<ip> ./setup/install.sh` (add `TMDB_KEY=…` and
   `AD_BLOCK_DNS=1` if wanted). Check Home on a screenshot. Walk the person through the first-time
   setup on the TV.
4. **Choose a debloat list.** Use `setup/lists/` if the TV is there, or the matching
   `examples/<model>/debloat.txt` from https://github.com/Gathin23/cleanyourtv. With no list, build
   one from `adb shell pm list packages -s` with the rules above, and show it to the person. Comment
   out whatever they said to keep.
5. **Debloat in batches:** split the list into files of five to eight packages,
   `DRY_RUN=1 ./setup/debloat.sh <batch>` first, then for real, screenshot Home after each. Disable
   the stock home and setup wizard last, only once Glass Home is Home.
6. **Check:** reboot (`adb reboot`), confirm Home is Glass Home and the disabled packages stayed
   disabled (`adb shell pm list packages -d`). Ask the person to press the real remote's Netflix,
   Prime Video, YouTube and Quick Settings buttons, and the mic button.
7. **Close ADB:** the person revokes USB debugging authorisations, switches USB debugging and
   Wireless debugging off in Developer options, and restarts the TV. Confirm the port is closed
   (`adb connect <ip>:5555` must fail).
8. **Report** what was disabled, what was kept and why, and how to undo it:
   `TV_HOST=<ip> ./setup/restore.sh <list>`.

If the TV isn't in `setup/lists/` and the setup worked, offer to save the list as
`setup/lists/<brand>-<model>.txt` with a header like the TCL one (model, Android version, firmware,
what was kept), for a pull request. Never include IP addresses, Wi-Fi names, serial numbers or
account details.

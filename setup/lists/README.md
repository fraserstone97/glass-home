# Debloat lists

A list is one package name per line, with `#` comments. `../debloat.sh` disables every package
in it (never uninstalls) after backing up the TV's package lists; `../restore.sh` turns them all
back on.

| List | TV |
|---|---|
| [`tcl-c6k-google-tv.txt`](tcl-c6k-google-tv.txt) | TCL C6K series, Google TV, Android 14 |
| [`benq-qs01-android-tv.txt`](benq-qs01-android-tv.txt) | BenQ QS01 dongle (Amlogic S905Y2), Android TV 12 |

## Another TV?

[cleanyourtv](https://github.com/Gathin23/cleanyourtv/tree/main/examples) has tested lists for
dozens of TVs (Sony, Hisense, Xiaomi, Philips, TCL and more) in the same format: copy the
`debloat.txt` for your model here and run `debloat.sh` with it.

No list for your TV? Make one:

1. See what's installed: `adb shell pm list packages -f` (and `-s` for system apps only).
2. Look anything unfamiliar up, or read `adb shell dumpsys package <name>`.
3. Leave alone what the TV needs: the TV and input apps (HDMI, tuner), the keyboard, remote and
   Bluetooth pairing, Play Store and Play Services, and Android's own packages
   (`com.android.*`, `com.google.android.gms`). `debloat.sh` refuses the core ones anyway.
4. Disable a few at a time, use the TV for a bit, then add more.

Please share a list that works by opening a pull request.

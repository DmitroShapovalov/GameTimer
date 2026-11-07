# GameTimer Child — spike C0

A throw-away test app for Phase C (Android agent), step C0. It is **not** the agent: no pairing, no parent app,
English only. It answers four questions on a real phone:

1. How long until a chosen game is covered by the block page: polling usage events vs the accessibility service?
2. What can a child switch off, with and without strict mode, and does the app notice (every change is logged)?
3. Which screens strict mode closes on this phone, and does it close too much?
4. Does the background service survive the vendor's battery manager for a day (service starts, last poll, battery)?

No Device Owner and no factory reset: the owner decided the three levels below are enough.

| Level | What it adds |
|---|---|
| Normal | Usage access + display over other apps: a block page about a second after a chosen app opens |
| + device admin | The app can't be uninstalled without first switching the admin off in Settings |
| + accessibility service | The block page as soon as the app opens (polling stays as a backstop, e.g. for a budget running out mid-game) |
| + strict mode (PIN) | The child is sent home from any screen of Settings, the installer, the permission screens, the vendor's security and battery apps (and other system apps without an icon) that shows this app's name |

## Install

1. Download `gametimer-child-spike-apk` from the PR's "Android app" CI run (a zip: unzip it) and install the APK.
2. In the app, try to grant Usage access and Display over other apps. Android 13+ refuses the first time for an app
   installed from a file; then Settings → Apps → GameTimer Child → ⋮ → **Allow restricted settings**, and grant again.
3. Grant No battery optimisation too.
4. Pick a game or two, choose "Block now" or a small daily budget, and open the game.

## Strict mode (section 4)

1. Activate **Device admin** and switch on the **accessibility service** (Settings → Accessibility → GameTimer Child;
   the "restricted settings" step may come first). Strict mode can't be turned on before both are in place.
2. Set a parent PIN (4+ digits, entered twice) and **Turn strict mode on**.

While it is on, the app's own settings are locked, and any full-screen page of those apps that shows "GameTimer Child"
sends the child to the home screen (a browser or mail app showing the name is left alone): App info (force stop, uninstall, clear data), the accessibility and device-admin pages, the
uninstall dialog, the permission lists, the vendor's battery and security apps. **Pause 10 min** (ends at a reboot
too) or **Turn off** with the PIN; the Grant buttons only work while paused or off.

Expect over-blocking, and note it in the report: every list that shows our app is closed (Apps → recently opened,
Battery usage, Notifications → recently sent, the Accessibility main page, Settings search).

Things to try as "the child", the log shows what got through:

- force stop / uninstall from App info, from a long press on the icon, from Recents → App info
- Quick Settings → "Active apps" → Stop (Android 13+)
- switch off the accessibility service or the device admin, revoke usage access
- split screen or picture-in-picture with a game
- change the date or time (resets today's budget; logged, not guarded)
- reboot into safe mode (third-party apps don't run there)

Android 12+ also shows a periodic reminder that an app can see the screen: note whether it appears.

## Ways back (forgotten PIN, a new build)

- In the app: the PIN → **Turn off**.
- Safe mode: the service doesn't run there → Settings → Device admin apps → switch it off → uninstall.
- From a computer (USB debugging):
  `adb shell am force-stop com.gametimer.child` (also switches the accessibility service off), or read the PIN:
  `adb shell run-as com.gametimer.child cat shared_prefs/spike.xml` (works on this debug build).
- Each CI run signs with its own debug key, so a newer build doesn't install over an older one: strict mode off,
  device admin off, uninstall, then install the new one.

## Report

Section 5 → **Copy report** copies the measured delays (poll and accessibility separately), service starts, phone
model, permission changes and the event log (battery level every 30 minutes), to paste back into the PR or chat. A
delay is measured when the block page is on screen, from the later of "the game came to the front" and "blocking
turned on".

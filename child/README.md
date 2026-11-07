# GameTimer Child — spike C0

A throw-away test app for Phase C (Android agent), step C0. It is **not** the agent: no pairing, no parent app,
English only. It answers four questions on a real phone:

1. How long until a chosen game is covered by the block page: polling usage events vs the accessibility service?
2. What can a child switch off, with and without strict mode, and does the app notice (every change is logged)?
3. Which settings screens strict mode closes on this phone, and does it close too much?
4. Does the background service survive the vendor's battery manager for a day (service starts, last poll, battery)?

No Device Owner and no factory reset: the owner decided the three levels below are enough.

| Level | What it adds |
|---|---|
| Normal | Usage access + display over other apps: a block page about a second after a chosen app opens |
| + device admin | The app can't be uninstalled without first switching the admin off in Settings |
| + accessibility service (strict mode) | Instant block page; closes every settings / installer / permission screen that shows this app's name |

## Install

1. Download `gametimer-child-spike-apk` from the PR's "Android app" CI run (a zip: unzip it) and install the APK.
2. In the app, try to grant Usage access and Display over other apps. Android 13+ refuses the first time for an app
   installed from a file; then Settings → Apps → GameTimer Child → ⋮ → **Allow restricted settings**, and grant again.
3. Grant No battery optimisation too.
4. Pick a game or two, choose "Block now" or a small daily budget, and open the game.

## Strict mode (section 4)

1. Activate **Device admin**.
2. Switch on the **accessibility service** (Settings → Accessibility → GameTimer Child; the same "restricted settings"
   step may come first).
3. Set a parent PIN (4+ digits) and **Turn strict mode on**.

While it is on, the app's own settings are locked, and any settings, installer or permission screen that shows
"GameTimer Child" is closed at once: App info (force stop, uninstall, clear data), the accessibility and device-admin
pages, the uninstall dialog, the permission lists. That includes the full app list in Settings while our app is
visible in it — tell me if that gets in the way. **Pause 10 min** or **Turn off** with the PIN.

Things to try as "the child": force stop, uninstall from the home screen, switch off the accessibility service or the
device admin, revoke usage access, change the clock, reboot into safe mode. The report shows what was closed and what
got through.

## Report

Section 5 → **Copy report** copies the measured delays (poll and accessibility separately), service starts, phone
model, permission changes and the event log (battery level every 30 minutes), to paste back into the PR or chat. A
delay is measured when the block page is on screen, from the later of "the game came to the front" and "blocking
turned on".

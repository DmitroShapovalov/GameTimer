# GameTimer Child — spike C0

A throw-away test app for Phase C (Android agent), step C0. It is **not** the agent: no pairing, no parent app,
English only. It answers four questions on a real phone:

1. How long until a chosen game is covered by the block page, without Device Owner (usage access + overlay)?
2. What can a child switch off, and does the app notice (the log shows every change of permissions)?
3. Does the background service survive the vendor's battery manager for a day (service starts, last poll)?
4. What does Device Owner add (apps suspended by the system, uninstall / safe mode / reset blocked)?

## Install (normal mode)

1. Download `gametimer-child-spike-apk` from the PR's "Android app" CI run (a zip: unzip it) and install the APK.
2. In the app, try to grant Usage access and Display over other apps. Android 13+ refuses the first time for an app
   installed from a file; then Settings → Apps → GameTimer Child → ⋮ → **Allow restricted settings**, and grant again.
3. Grant No battery optimisation too.
4. Pick a game or two, choose "Block now" or a small daily budget, and open the game.

## Device Owner (optional, on a test phone only)

Use `child-deviceOwner.apk` from the same zip (a test-only build). It needs a phone that was just reset, with
**no accounts of any kind** (Google, Samsung, Xiaomi…) and no extra users, and USB debugging on:

```
adb install -t child-deviceOwner.apk
adb shell dpm set-device-owner com.gametimer.child/.AdminReceiver
```

The app then suspends the chosen apps instead of covering them, and offers to block uninstalling, safe mode,
factory reset (in Settings) and new users. Settings, the home screen, Play Store, the dialer and the SMS app can't be
chosen, and developer options are never blocked.

Ways back, in order:
1. **Remove Device Owner** in the app: lifts every suspension it made (and of every launchable app), clears those
   blocks and gives up Device Owner.
2. From a computer: `adb shell dpm remove-active-admin com.gametimer.child/.AdminReceiver` (works because the build is
   test-only), then `adb uninstall com.gametimer.child`. Suspended apps may then need the app reinstalled and step 1.
3. Last resort: a factory reset from recovery mode.

Each CI run signs with its own debug key, so a newer build doesn't install over an older one: remove the old one
first (step 1 or 2).

## Report

Section 5 → **Copy report** copies the measured delays, service starts, phone model and the event log (with the battery
level every 30 minutes), to paste back into the PR or chat. The delay is measured when the block page is on screen,
from the later of "the game came to the front" and "blocking turned on".

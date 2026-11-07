# GameTimer Child — spike C0

A throw-away test app for Phase C (Android agent), step C0. It is **not** the agent: no pairing, no parent app,
English only. It answers four questions on a real phone:

1. How long until a chosen game is covered by the block page, without Device Owner (usage access + overlay)?
2. What can a child switch off, and does the app notice (the log shows every change of permissions)?
3. Does the background service survive the vendor's battery manager for a day (service starts, last poll)?
4. What does Device Owner add (apps suspended by the system, uninstall / safe mode / reset blocked)?

## Install (normal mode)

1. Download `gametimer-child-spike-apk` from the PR's "Android app" CI run and install it.
2. Android 13+: Settings → Apps → GameTimer Child → ⋮ → **Allow restricted settings**.
3. In the app: grant Usage access, Display over other apps and No battery optimisation.
4. Pick a game or two, choose "Block now" or a small daily budget, and open the game.

## Device Owner (optional, on a test phone only)

Needs a phone that was just reset, with **no Google account added yet**, and USB debugging on:

```
adb install child-debug.apk
adb shell dpm set-device-owner com.gametimer.child/.AdminReceiver
```

The app then suspends the chosen apps instead of covering them, and offers to block uninstalling, safe mode,
factory reset, developer options and new users. **Remove Device Owner** in the app undoes all of it.
Don't turn on "Block developer options" before you know how to remove Device Owner from the app.

## Report

Section 5 → **Copy report** copies the measured delays, service starts, phone model and the event log, to paste
back into the PR or chat.

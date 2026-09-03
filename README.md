# Pikmin Mushroom Helper — Prototype

Android Studio prototype for a local Accessibility-based helper.

## What is included

- Master ON/OFF switch
- Android Quick Settings tile
- ECO / WATCH / RACE modes
- Battery guard
- Restrict Accessibility service to `com.nianticlabs.pikmin`
- Screenshot hook using Android Accessibility API
- Lightweight template-matching utility for small screen regions
- Automation state-machine scaffold
- Paid-ticket protection preference

## Default scan intervals

- ECO: 60 seconds while Pikmin Bloom is already in foreground
- WATCH: 3 seconds
- RACE: 350 ms

The prototype intentionally does **not** run RACE mode all day.

## Important Android limitation

This prototype does not bypass the Android lock screen and does not try to circumvent background-launch restrictions.

For reliable RACE mode:
1. Keep the phone unlocked.
2. Keep Pikmin Bloom in foreground.
3. Open the screen containing nearby mushrooms.
4. Enable RACE shortly before the expected refresh.

This removes game-launch/loading latency.

## First setup

1. Open this project in Android Studio.
2. Let Gradle sync.
3. Build/install on an Android 11+ device.
4. Open Mushroom Helper.
5. Tap `開啟無障礙服務設定`.
6. Enable Mushroom Helper Accessibility service.
7. Add the `蘑菇助手` tile to Android Quick Settings if desired.

## Template setup

Different phones, DPI settings, languages and game versions can place buttons differently.
Do not hard-code absolute coordinates.

Capture small images from your own device for:
- a mushroom card/icon
- Join button
- Auto-select button
- GO/Start button
- any paid-ticket confirmation screen you want to treat as an immediate safety stop

Then load those bitmaps at service startup and feed them to `TemplateMatcher.match()`.

For RACE, only search small ROIs and use a small step size.

## Weekend target priority

On Saturday and Sunday the helper uses this fixed target order:

1. `GIANT`
2. If no giant mushroom is currently detected, `EVENT`
3. Ignore every other mushroom type

The policy lives in `MushroomPolicy.kt`, so target detection and target priority stay separate.

## Recommended state machine

`LOOKING_FOR_MUSHROOM`
→ `OPENING_MUSHROOM`
→ `JOINING`
→ `SELECTING_PIKMIN`
→ `CONFIRMING`
→ `FIGHTING`

At every state:
- Require a positive visual match before tapping.
- If the expected screen is not found, do nothing.
- Never guess a coordinate.
- If a paid-ticket confirmation is detected and paid-ticket blocking is enabled, enter `SAFETY_STOP`.

## Performance notes

The included matcher is pure Kotlin and intended only as a working prototype.
For sub-second race scanning, crop to a small ROI.

If profiling later shows the matcher itself is the bottleneck, replace only `TemplateMatcher` with a native/OpenCV implementation rather than changing the rest of the architecture.

## Account / game-policy note

This is an unofficial automation prototype. Automated game interaction can violate a game's terms or anti-cheat rules and can put an account at risk. It does not include server-protocol reverse engineering, authentication bypass, anti-cheat bypass, packet manipulation, or GPS spoofing.

Build trigger initialized.

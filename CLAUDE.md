# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read [agent.md](agent.md) first: it holds the binding versioning and APK export rules. UI text, comments and docs are in Dutch; match that.

## Commands

Needs JDK 17 (`JAVA_HOME`) and the Android SDK (`ANDROID_HOME`, platform 36). Single-module Gradle project (`app`), minSdk 34.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug   # validation
./gradlew testDebugUnitTest --tests org.esos.app.MorseSosTest   # one test class
./gradlew exportDebugApk      # dist/eSOS-v<versionName>.apk, debug-signed
./gradlew exportReleaseApk    # same name, signed with the e-SOS release key
```

- Before every APK build, bump `versionName` (MAJOR.MINOR.PATCH, no suffix) and `versionCode` in `app/build.gradle.kts`. The About screen shows `versionName`.
- Both export tasks finish with `syncDebugApk`, which copies the APK to `~/Sync/apps/Apks` if that folder exists.
- The release keystore and passwords live outside the repo in `~/.android-toolchain/esos-release.jks` and `esos-signing.properties`; without that properties file the release APK is unsigned and `exportReleaseApk` fails. Never commit them. `dist/` and `app/dist/` are git-ignored (GitHub rejects files over 100 MB, and the Vosk model is already ~40 MB in the tree).
- `connectedDebugAndroidTest` only on a throwaway emulator: Gradle may uninstall the app afterwards, wiping its private settings. Only `VoskDetectorInstrumentedTest` and `SosOverlayInstrumentedTest` exist.

## Architecture

Offline emergency app (no network, backend or Play Services). Everything is in `app/src/main/java/org/esos/app`; the single `MainActivity` is a Compose UI with five tabs (Start, Bewaking, Alarm, Contacten, Instellingen; the Start tab is the simple senior-friendly view, larger type comes from `seniorTypography()`). All persistent state is in `AppSettings` (SharedPreferences, one property per setting).

**Triggers → `AlertRuntime` → actions.** Detectors start an alert with an `AlertReason`: `ListeningService` (Vosk speech), `FallDetectionService`/`FallDetector`, `InactivityMonitor` (unlock timeout) and `CheckInScheduler` (missed check-in), plus the manual SOS button. `AlertRuntime` is the process-wide singleton that owns the single active `AlertCoordinator`.

- `AlertCoordinator` is the pure state machine (`IDLE → COUNTDOWN → SENT/CANCELLED/ERROR`) with GPS-fix handling; it talks to an `AlertSender` and `IncidentLocation`, so it is unit-testable.
- The countdown deadline is persisted (`pending_alarm` prefs) plus an `AlarmManager` fallback (`AlarmDeadlineReceiver`) so an alert survives process death; `AlertRuntime.recover` re-fires it.
- When the countdown ends, `SmsThenCallAlertSender` runs the enabled actions: SMS (`SmsAlertSender`), then the contact call round. `onAlertStarted` → `enterSosPhase` sets `sosModeActive`, shows the ongoing notification, the red border (`SosOverlay`/`SosOverlayService`), the spoken announcement and `SosSignals.start`.
- Calls: `SosCallScheduler` persists the contact list and rings contact N every 15 min via exact alarms (`TimedCallRound`); `PhoneCallAction`/`PhoneCallService`/`PhoneCallActivity` place and manage the call. e-SOS is not the default phone app, so hang-up and audio routing cannot be enforced.
- Cancelling (`AlertRuntime.cancel`, also reachable from the notification, the UI and spoken cancel phrases) must undo everything: scheduler, overlay, signals, pending alarm, notifications.

**Manual SOS and countdown.** `startManual` is a real SOS with no countdown; there is deliberately no test mode (only a full real run counts as a test). Triggers with a countdown open `CountdownActivity` ("Gaat het goed?") over the lock screen, directly and as the notification's full-screen intent; its "Nee, haal hulp" calls `AlertRuntime.sendNow`. The home-screen widgets (`SosWidget.kt`: small/medium/large providers, layouts `widget_sos_*`) open the same activity with a 10 s SOS countdown (`AlertRuntime.startFromWidget`, screen text "SOS versturen?"); `SosWidgets.update` refreshes their status on SOS start/cancel and when `MainActivity` pauses. During SOS the Start tab shows `AlertRuntime.smsStatus` and `SosCallScheduler.status` in plain Dutch. Anything added to the SOS phase has to be wired for the cancel path too.

**Signals (`SosSignals.kt`).** Alarm sound (looping `AudioTrack` PCM, `HIGH_TONE` or `MORSE_SOS`, forces alarm volume to max and restores it) and morse vibration (`MorseSos` builds the timing pattern). The one-shot vibration fires at countdown start (`announce`); alarm sound and continuous vibration start only when SOS is really triggered. `restoreActiveSosNotification` restarts them after process death.

**Spoken feedback.** All mp3 announcements go through `AlertAudioPlayer`, gated by `spokenFeedbackEnabled`; `enqueue` still invokes its `onComplete` callback when muted, because the call round is chained on it. Alarm sounds bypass this gate. The in-call instruction audio in `PhoneCallService` is intentionally not gated (it is for the contact). Clips live in `app/src/main/res/raw` (originals in `audio/`).

**Speech detection.** `ListeningService` records 16 kHz audio and feeds `VoskDetector` (Dutch model unpacked from the bundled ZIP by `ModelAssets`). The Vosk grammar is limited to the alarm phrases plus cancel phrases (`WakePhrases`), and `PhraseMatcher` requires an exact full-phrase match. `acceptPcm16` returns a `DetectionEvent` (`NONE/ALARM/CANCEL`); phrase changes only apply after restarting the service.

## Notes

- `README.md` is partly outdated (e.g. 10 s countdown and English model; `AlertReason.countdownMillis` is 60 s and only the Dutch model is bundled); trust the code. It still documents the safety caveats and release blockers: this is a prototype, not validated as an emergency device.
- `SEND_SMS` is a restricted permission for sideloaded APKs; the Settings flow and README explain the workaround.

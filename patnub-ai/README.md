# patnub.ai (Android)

Native Android app for the Alaga Health build plan
([`../ALAGA_HEALTH_ITERATIVE_BUILD_PHASES.md`](../ALAGA_HEALTH_ITERATIVE_BUILD_PHASES.md)).
It is separate from the web mockup in `../alaga-health/` and from the engine work in `../ravin/`.

## Build

Open this folder (`patnub-ai/`) in Android Studio and let it sync, or from a terminal:

```sh
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug         # install on a connected phone
```

Requires JDK 17+ (Android Studio's bundled JDK works) and Android SDK platform 36.

| Component | Version |
| --- | --- |
| Gradle wrapper | 9.8.0 (checksum-pinned) |
| Android Gradle plugin | 9.5.1 (built-in Kotlin, no `kotlin-android` plugin) |
| Kotlin / Compose compiler plugin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 36 / 36 |
| Package / application ID | `ai.patnub` |

## Layout

```
app/src/main/java/ai/patnub/
  MainActivity.kt              entry point, edge-to-edge, sets the Compose theme
  ui/PatnubApp.kt              root Scaffold: top bar with Patient/Doctor switch, bottom tabs
  ui/patient/PatientScreens.kt the 5 Patient tabs (Assistant, Records, Scan, Medicines, Visits)
  ui/doctor/DoctorScreens.kt   the 3 Doctor steps (Write note, Review AI draft, Issue QR)
  ui/components/               shared composables
  ui/theme/                    colors and Material 3 theme
app/src/main/res/              strings, vector icons, adaptive launcher icon, backup rules
```

## Current state

App shell only. Every screen is a placeholder. Next Phase 1 steps: the local SQLite
schema and seed data, then the `libmsr_engine` bridge and the live telemetry pill in the top bar.

## Privacy defaults

- No `INTERNET` permission. The app is meant to run fully offline.
- `allowBackup="false"` plus `data_extraction_rules.xml` keep app data out of cloud
  backups and device-to-device transfers, since the app will store patient records.

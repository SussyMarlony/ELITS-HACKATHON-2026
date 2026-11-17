# Running Alaga Health locally

Two pieces talk to each other: the clinic app (`doctor-web`, a web app that runs in a browser)
and the patient app (`patnub-ai`, an Android app). Pairing happens through QR codes. The
patient's phone shows a check-in code, the clinician scans it, and later the clinician's
approved record comes back to the phone as a second QR code.

## What you need

- Node 20 or newer (24 recommended, the contract scripts use type stripping)
- JDK 17 or newer (Android Studio's bundled JDK works)
- Android Studio with SDK platform 37, NDK 30.0.16248370, and CMake 4.1.2
- About 4 GB of free disk for the app plus its AI model
- For the emulator path: a system image, e.g. API 36 arm64 or x86_64

## 1. Run the clinic web app

```sh
cd doctor-web
npm install
npm run dev
```

Open http://localhost:5173. For the offline/PWA behaviour, build and preview instead:

```sh
npm run build
npm run preview
```

The preview serves `dist/` with the service worker, which is what you want when testing
with no internet. In dev mode there is no service worker.

## 2. Create the clinic license (once)

The clinic app will not activate without a license file signed by the demo root key.

```sh
cd contract
node scripts/issue-license.ts rhu-san-isidro "RHU San Isidro" 365 > ../doctor-web/license.json
```

`365` is the validity in days. In the web app, go to **Activate**, pick the file, then
create the first clinician (name, profession, PRC number, passcode). That person becomes
the clinic admin.

If you ever need to rotate the demo root, `node scripts/demo-root.ts --force` makes a new
one, but every existing license stops working and the Android app needs a rebuild.

## 3. Run the patient app

### Option A: emulator

Create an AVD in Android Studio's Device Manager (a Pixel with a recent API level works),
or from the command line:

```sh
avdmanager create avd -n pixel_api_36 -k "system-images;android-36;google_apis;arm64-v8a"
emulator -avd pixel_api_36
```

Wait for it to finish booting, then in another terminal:

```sh
cd patnub-ai
./gradlew :app:installDebug
```

The first build is slow. It fetches Gradle dependencies, compiles the Kotlin/Compose code,
and builds the native llama.cpp bridge in `app/src/main/cpp`. If the native build fails,
run `scripts/prepare-native.sh` first (see `patnub-ai/README.md`).

### Option B: a real phone

Enable Developer options and USB debugging on the phone, plug it in, and accept the RSA
fingerprint prompt. Wireless debugging also works: pair once in Developer options, then
`adb devices` shows the phone. The same `./gradlew :app:installDebug` installs onto it.

Check either way that the device is visible:

```sh
adb devices
```

## 4. Pair the two devices

1. On the phone or emulator, open the app and finish onboarding: name, birth year, sex,
   and a few health details. This creates the patient's key pair.
2. Open the **Scan** tab. It shows a check-in code as a QR code.
3. On the laptop, open the clinic app and sign in as the clinician. Go to **Check in
   patient**, allow camera access, and point it at the phone screen.
   - If you are testing without a camera, open the app with `http://localhost:5173/?debug`
     and paste the code shown under the QR on the phone.
4. **Add patient**, then **New consultation**, paste a note, **Draft fields**, review, and
   **Approve and create QR**.
5. Back on the phone, **Scan** › **Scan a record from your clinic**, point the camera at
   the laptop screen, then **Save to my records**. Scanning the same QR on a different
   phone gives the "made for a different phone" error, which is the check working.

## 5. The AI model (optional for pairing, needed for real answers)

The Assistant works without a model, answering only from saved reference passages and
saying so. For real model answers, tap the engine status line in the top bar, pick a model
for the device's RAM tier, and download it (2.5 to 3.4 GB, best on Wi-Fi) or import the
GGUF files over USB. On an emulator expect the streamed configuration and slow token
speeds; `patnub-ai/README.md` has measured numbers for real phones.

## Troubleshooting

- **Activation fails:** the file must be the JSON output of `issue-license.ts`, not the
  script itself.
- **Gradle build fails on `patnub_llm.cpp`:** run `patnub-ai/scripts/prepare-native.sh`,
  then retry. Confirm the NDK version under `~/Android/Sdk/ndk` (or wherever
  `local.properties` points `sdk.dir`).
- **`emulator` or `adb` not found:** add `~/Android/Sdk/emulator` and
  `~/Android/Sdk/platform-tools` to your PATH, or use the copies inside Android Studio.
- **Device not listed:** `adb kill-server && adb start-server`, or re-check USB debugging
  and the authorization prompt on the phone.
- **Camera permission denied in the web app:** allow it in the browser site settings, or
  use the `?debug` paste box.
- **QR scan fails:** the screen must be bright and the code fully in frame. Reduce window
  scaling if the QR is small.

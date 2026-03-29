# MyTTS - Build & Test Guide

## Prerequisites

- **macOS** (tested on Apple Silicon M4)
- **Android Studio** (Ladybug or newer) with:
  - Android SDK 35
  - Build-tools 34.0.0 (auto-installed by Gradle)
  - NDK (if prompted)
- **Java 21** (bundled with Android Studio)

### Environment Variables

Add to your shell profile (`~/.zshrc` or `~/.bashrc`):

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Reload: `source ~/.zshrc`

---

## Download Model Assets

The ONNX model and voice files are too large for git. Download them before building:

### 1. Kokoro int8 quantized model (~92 MB)

```bash
mkdir -p app/src/main/assets
curl -L "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_quantized.onnx" \
  -o app/src/main/assets/model_quantized.onnx
```

### 2. Voice style files (.bin)

Kokoro v1.0 ONNX voices are raw float32 `.bin` files (522 KB each, no header).

```bash
mkdir -p app/src/main/assets/voices

# Download all English voices from Kokoro v1.0
for voice in af_heart af_alloy af_aoede af_bella af_jessica af_kore af_nicole af_nova af_river af_sarah af_sky am_adam am_echo am_eric am_liam am_michael am_onyx am_puck am_santa bf_alice bf_emma bf_lily bm_daniel bm_fable bm_george bm_lewis; do
  echo "Downloading ${voice}.bin ..."
  curl -sL "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/${voice}.bin" \
    -o "app/src/main/assets/voices/${voice}.bin"
done
```

Or download only a few voices to reduce APK size (each is ~512 KB):

```bash
# Minimal set - one female, one male
for voice in af_heart am_adam; do
  curl -sL "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/${voice}.bin" \
    -o "app/src/main/assets/voices/${voice}.bin"
done
```

### 3. CMU Pronouncing Dictionary (IPA format)

This dictionary is used for text-to-phoneme conversion. You can get it from the
puff-dayo/Kokoro-82M-Android reference project (it's stored as a raw resource there):

```bash
curl -sL "https://raw.githubusercontent.com/puff-dayo/Kokoro-82M-Android/latest/app/src/main/res/raw/cmudict_ipa.dict" \
  -o app/src/main/assets/cmudict_ipa.txt
```

### Verify assets

```bash
ls -lh app/src/main/assets/
# Should show:
#   model_quantized.onnx  (~92 MB)
#   cmudict_ipa.txt       (~2.8 MB)
#   voices/               (directory)

ls app/src/main/assets/voices/
# Should show .bin files (522240 bytes each)
```

---

## Build

### Debug APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Signed Release APK

The project includes a `release.keystore` for convenience (password: `mytts123`).
For production distribution, generate your own keystore.

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Verify release signing

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Should show `Verified using v2 scheme (APK Signature Scheme v2): true`.

### Clean build

```bash
./gradlew clean assembleRelease
```

---

## Emulator Testing

> **Important**: The Kokoro model runs inference on CPU. On an emulator the inference
> will be significantly slower than on a real arm64 device. Expect ~10-30 seconds per
> chunk on emulator vs ~1-2 seconds on a Snapdragon 8 Elite.

### Create an Emulator (if you don't have one)

Option A - **Android Studio**: Tools > Device Manager > Create Virtual Device >
select a phone (e.g. Pixel 9 Pro), choose API 35 arm64-v8a system image.

Option B - **Command line**:

```bash
# List available system images
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --list | grep "system-images.*arm64"

# Install an image (if needed)
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "system-images;android-35;google_apis_playstore;arm64-v8a"

# Create AVD
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  -n MyTTS_Test \
  -k "system-images;android-35;google_apis_playstore;arm64-v8a" \
  -d "pixel_6"
```

### Launch Emulator

```bash
# List available AVDs
emulator -list-avds

# Launch (example)
emulator -avd MyTTS_Test &
```

Or launch from Android Studio Device Manager.

### Install and Run

```bash
# Install debug APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Or install release APK
adb install app/build/outputs/apk/release/app-release.apk

# Launch the app
adb shell am start -n com.example.mytts/.MainActivity

# View logs
adb logcat -s KokoroEngine:* AudioPlayer:* AudioProducer:* PlaybackController:* PhonemeConverter:*
```

### Uninstall

```bash
adb uninstall com.example.mytts
```

---

## Install on Physical Device (Redmi K90 Pro Max)

### Enable Developer Options

1. Go to **Settings > About phone**
2. Tap **MIUI version** 7 times to enable Developer Options
3. Go to **Settings > Additional settings > Developer options**
4. Enable **USB debugging**
5. (Optional) Enable **Install via USB**

### Connect and Install

```bash
# Verify device is connected
adb devices

# Install release APK
adb install app/build/outputs/apk/release/app-release.apk

# Launch
adb shell am start -n com.example.mytts/.MainActivity
```

### Wireless debugging (optional)

```bash
# On the phone: Developer options > Wireless debugging > Pair device
adb pair <ip>:<port>    # enter pairing code
adb connect <ip>:<port>
adb install app/build/outputs/apk/release/app-release.apk
```

---

## Project Structure

```
mytts/
├── BUILD.md                          # This file
├── plan.txt                          # Original requirements
├── release.keystore                  # Release signing key
├── build.gradle.kts                  # Root build config
├── settings.gradle.kts               # Project settings
├── gradle.properties                 # JVM and Android config
├── gradlew                           # Gradle wrapper script
├── .gitignore
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties     # Gradle 8.9
└── app/
    ├── build.gradle.kts              # App dependencies & signing
    ├── proguard-rules.pro            # R8 keep rules
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/                   # (not in git - download manually)
        │   ├── model_quantized.onnx  # Kokoro int8 ONNX model
        │   ├── cmudict_ipa.txt       # CMU pronunciation dictionary
        │   └── voices/               # Voice style .bin files
        ├── java/com/example/mytts/
        │   ├── MainActivity.kt       # Activity host, service binding
        │   ├── MyTTSApplication.kt   # App-level init, temp cleanup
        │   ├── audio/
        │   │   ├── AudioPlayer.kt    # AudioTrack consumer, crossfade
        │   │   ├── AudioProducer.kt  # Background inference thread
        │   │   ├── PlaybackController.kt  # State machine orchestrator
        │   │   └── TextChunker.kt    # Sentence-boundary text splitting
        │   ├── engine/
        │   │   ├── KokoroEngine.kt   # ONNX session, synthesize()
        │   │   ├── PhonemeConverter.kt  # Text -> IPA phonemes
        │   │   ├── Tokenizer.kt      # IPA chars -> token IDs
        │   │   └── VoiceStyleLoader.kt  # .bin/.npy voice parser
        │   ├── service/
        │   │   └── PlaybackService.kt  # Foreground service
        │   ├── ui/
        │   │   ├── MainScreen.kt     # Compose UI
        │   │   └── theme/
        │   │       ├── Color.kt
        │   │       └── Theme.kt
        │   └── util/
        │       └── PreferencesManager.kt  # SharedPreferences
        └── res/
            ├── mipmap-hdpi/ic_launcher.xml
            └── values/
                ├── strings.xml
                └── themes.xml
```

---

## Troubleshooting

### Build fails with "Could not resolve com.github.medavox:IPA-Transcribers:v0.2"
JitPack may be temporarily unavailable. Retry, or check https://jitpack.io status.
If JitPack requires auth, you may need to add a token to `gradle.properties`:
```
authToken=YOUR_JITPACK_TOKEN
```

### App crashes on launch with "model_quantized.onnx not found"
You haven't downloaded the model assets. See [Download Model Assets](#download-model-assets).

### Very slow inference on emulator
This is expected. Kokoro inference is CPU-intensive and emulators are much slower
than real hardware. Test on a physical device for realistic performance.

### "FOREGROUND_SERVICE" permission denied on Android 14+
Grant the notification permission when prompted. The app requests `POST_NOTIFICATIONS`
at startup on Android 13+.

### "Error: Not a valid .npy file" or voice loading fails
Kokoro v1.0 voices are `.bin` files (raw float32), not `.npy`. Make sure you
downloaded from the correct URLs (see [Voice style files](#2-voice-style-files-bin)).
Each `.bin` file should be exactly 522,240 bytes. If files are only 15 bytes, they
contain a "Entry not found" error from HuggingFace — re-download with the correct URL.

### ORT session creation fails with "SME instruction" error
You're using an ONNX Runtime version newer than 1.20.0. The project pins to 1.20.0
specifically because newer versions emit ARM SME instructions unsupported on many
devices. Check `app/build.gradle.kts` has `onnxruntime-android:1.20.0`.

---

## Generating Your Own Signing Key

For production releases, replace the included keystore:

```bash
keytool -genkeypair -v \
  -keystore my-release-key.keystore \
  -alias my-key-alias \
  -keyalg RSA -keysize 2048 \
  -validity 10000

# Then update app/build.gradle.kts signingConfigs.release with your values
```

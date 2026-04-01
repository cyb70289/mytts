# MyTTS

An Android app that converts text to speech on-device using the [Kokoro-82M](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) TTS model (int8 ONNX). No cloud services, no Android TTS API -- just paste text and hit play.

Tested on Snapdragon devices with ONNX Runtime 1.20.0 CPU inference. English only. 26 built-in voices.

## Architecture

```
app/src/main/
├── cpp/                     # Native code (NDK)
│   ├── CMakeLists.txt       # Fetches & builds eSpeak-ng, links JNI bridge
│   └── espeak_jni.cpp       # JNI bridge: init, textToPhonemes, terminate
├── java/com/example/mytts/
│   ├── engine/              # Kokoro ONNX inference + text frontend
│   │   ├── KokoroEngine     # ONNX session, speak() → float[] PCM
│   │   ├── PhonemeConverter  # Text → IPA phonemes (eSpeak-ng + misaki E2M mapping)
│   │   ├── EspeakBridge     # JNI wrapper for eSpeak-ng, asset extraction
│   │   ├── Tokenizer        # IPA → token IDs for model input
│   │   └── VoiceStyleLoader # Loads .bin voice style matrices [510×256]
│   ├── audio/               # Playback pipeline
│   │   ├── TextChunker      # ICU4J BreakIterator sentence splitting
│   │   ├── TextProcessor    # Chunking + normalization at paste time
│   │   ├── AudioProducer    # Background thread: chunks → inference → PCM queue
│   │   ├── AudioPlayer      # Consumer thread: PCM queue → AudioTrack with crossfade
│   │   └── PlaybackController # State machine orchestrating producer/player
│   ├── ui/
│   │   └── MainScreen       # Jetpack Compose UI
│   ├── service/
│   │   └── PlaybackService  # Foreground service for screen-off playback
│   ├── util/
│   │   └── PreferencesManager # SharedPreferences persistence
│   └── MainActivity         # Activity host
└── assets/                  # (not in git)
    ├── model_quantized.onnx # Kokoro int8 ONNX model (~92 MB)
    ├── espeak-ng-data/      # eSpeak-ng runtime data (~800 KB, English only)
    └── voices/              # Voice style .bin files (26 × 512 KB)
```

### Text Frontend Pipeline

```
Raw text
  → ICU4J BreakIterator     — robust sentence splitting (handles abbreviations, decimals, etc.)
  → normalizeText()         — numbers, currencies, ordinals, time, symbols → English words
  → eSpeak-ng               — English words → IPA phonemes (via NDK/JNI)
  → misaki E2M mapping      — eSpeak IPA → Kokoro-compatible phoneme format
  → Tokenizer               — IPA chars → integer token IDs
  → KokoroEngine            — token IDs + voice style → PCM audio via ONNX
```

**Phonemizer:** [eSpeak-ng](https://github.com/espeak-ng/espeak-ng) compiled as a static NDK library for text-to-phoneme conversion only (no audio synthesis). The output is post-processed using the [misaki](https://github.com/hexgrad/misaki) E2M mapping to match the phoneme format the Kokoro model was trained with. This replaces the earlier CMU dict + medavox approach.

**Sentence splitting:** Android's built-in [ICU4J BreakIterator](https://developer.android.com/reference/android/icu/text/BreakIterator) handles abbreviation-dot disambiguation, decimal numbers, ellipses, and other edge cases automatically. Long sentences are further split at clause boundaries, then word boundaries, to stay within the model's ~400 token limit.

**Audio pipeline:** Producer-consumer pattern. `AudioProducer` runs inference on a background thread, enqueues PCM chunks (max 4 ahead). `AudioPlayer` consumes from the queue on an urgent-audio-priority thread, with crossfade at chunk boundaries to avoid pops. Silence between chunks is trimmed and replaced with a consistent 300ms gap.

## Build

- See [BUILD.md](BUILD.md) for setup and build instructions.
- See [opencode-session.md](opencode-session.md) for full opencode session logs.

## Credits

- [Kokoro-82M v1.0 ONNX](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) -- the TTS model
- [eSpeak-ng](https://github.com/espeak-ng/espeak-ng) -- text-to-phoneme engine (via [csukuangfj fork](https://github.com/csukuangfj/espeak-ng))
- [misaki](https://github.com/hexgrad/misaki) -- eSpeak-to-Kokoro phoneme mapping reference
- [Kokoro-82M-Android](https://github.com/puff-dayo/Kokoro-82M-Android) -- reference project for running Kokoro on Android
- **Claude Opus 4.6 + OpenCode** -- all the coding and documentation

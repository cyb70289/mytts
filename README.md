# MyTTS

An Android app that converts text to speech on-device using the [Kokoro-82M](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) TTS model (int8 ONNX). No cloud services, no Android TTS API -- just paste text and hit play.

Tested on Snapdragon devices with ONNX Runtime 1.20.0 CPU inference. English only. 26 built-in voices.

## Architecture

```
app/src/main/java/com/example/mytts/
├── engine/              # Kokoro ONNX inference
│   ├── KokoroEngine     # ONNX session, speak() → float[] PCM
│   ├── PhonemeConverter  # Text → IPA phonemes (CMU dict + medavox fallback)
│   ├── Tokenizer         # IPA → token IDs for model input
│   └── VoiceStyleLoader  # Loads .bin voice style matrices [510×256]
├── audio/               # Playback pipeline
│   ├── TextChunker       # Sentence-boundary splitting (abbreviation-aware)
│   ├── AudioProducer     # Background thread: chunks → inference → PCM queue
│   ├── AudioPlayer       # Consumer thread: PCM queue → AudioTrack with crossfade
│   └── PlaybackController# State machine orchestrating producer/player
├── ui/
│   └── MainScreen        # Jetpack Compose UI
├── service/
│   └── PlaybackService   # Foreground service for screen-off playback
├── util/
│   └── PreferencesManager# SharedPreferences persistence
└── MainActivity          # Activity host
```

**Audio pipeline:** Producer-consumer pattern. `AudioProducer` runs inference on a background thread, enqueues PCM chunks (max 4 ahead). `AudioPlayer` consumes from the queue on an urgent-audio-priority thread, with crossfade at chunk boundaries to avoid pops. Silence between chunks is trimmed and replaced with a consistent 300ms gap.

**Phonemizer:** CMU Pronouncing Dictionary (`cmudict_ipa.txt`) for word lookup, with [medavox IPA-Transcribers](https://github.com/nicemediocre/IPA-Transcribers) as fallback for unknown words. Includes overrides for common short words and number-to-words conversion.

## Build

See [BUILD.md](BUILD.md) for setup and build instructions.

## Credits

- [Kokoro-82M v1.0 ONNX](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) -- the TTS model
- [Kokoro-82M-Android](https://github.com/puff-dayo/Kokoro-82M-Android) -- reference project for running Kokoro on Android with a pure Kotlin phonemizer
- Claude Opus 4.6 + OpenCode -- all the coding and documentation

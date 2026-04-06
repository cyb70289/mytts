# AGENTS.md

Android TTS app using Kokoro-82M int8 ONNX model for on-device text-to-speech. English only.

See [README.md](README.md) for project overview and [BUILD.md](BUILD.md) for full build/test instructions and project structure.

## Quick Reference

- **Language**: Kotlin + Jetpack Compose, JNI/C++ for eSpeak-ng phonemization
- **Min SDK**: 26, Target SDK: 35
- **Build**: `./gradlew assembleDebug` or `./gradlew assembleRelease`
- **Package**: `com.example.mytts`
- **Assets**: Model, voices, and eSpeak-ng data are not in git -- see BUILD.md "Download Model Assets"

## Architecture

```
MainActivity -> PlaybackService (foreground service)
                  -> PlaybackController (state machine)
                       -> TextChunker (sentence splitting)
                       -> AudioProducer (background inference thread, queue size=4)
                            -> PhonemeConverter (eSpeak-ng JNI) -> Tokenizer -> KokoroEngine (ONNX)
                       -> AudioPlayer (AudioTrack consumer, crossfade)
```

## Key Constraints

- **ONNX Runtime pinned to 1.20.0** -- newer versions use ARM SME instructions unsupported on target device (Snapdragon 8 Elite). Do not upgrade.
- **eSpeak-ng is phonemization only** -- no audio synthesis. Built as static NDK lib via CMake FetchContent from csukuangfj fork (commit `f6fed6c`).
- Audio playback must be gapless: producer pre-fills a queue of PCM chunks to avoid underruns.

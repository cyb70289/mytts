# MyTTS

An Android app that converts text to speech on-device using the [Kokoro-82M](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) TTS model (int8 ONNX). No cloud services, no Android TTS API -- just paste text and hit play.

Tested on Snapdragon devices with ONNX Runtime 1.20.0 CPU inference. English only. 26 built-in voices.

## [Release](https://github.com/cyb70289/mytts/releases/)

## Build

- See [BUILD.md](BUILD.md) for setup and build instructions.
- See [opencode-session.md](opencode-session.md) for full opencode session logs.

## Credits

- [Kokoro-82M v1.0 ONNX](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) -- the TTS model
- [eSpeak-ng](https://github.com/espeak-ng/espeak-ng) -- text-to-phoneme engine (via [csukuangfj fork](https://github.com/csukuangfj/espeak-ng))
- [Kokoro-82M-Android](https://github.com/puff-dayo/Kokoro-82M-Android) -- reference project for running Kokoro on Android
- **Claude Opus 4.6 + OpenCode** -- all the coding and documentation

## Usage

- Select speaker (recommend `af_sarah`, prefix: <ins>a</ins>merican, <ins>b</ins>ritish, <ins>f</ins>emale, <ins>m</ins>ale)
- Paste text
- Play/Pause/Resume/Stop
- Double tap text block to start playing there (only in stopped status)

## Demo
**Make sure to unmute the video**

https://github.com/user-attachments/assets/dc986f61-66c8-434f-8302-cced1b48c647

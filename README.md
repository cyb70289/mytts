# MyTTS

An Android app that converts text to speech on-device using the [Kokoro-82M](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) TTS model (int8 ONNX). No cloud services, no Android TTS API -- just paste text and hit play.

Tested on Snapdragon devices with ONNX Runtime 1.20.0 CPU inference. English only. 26 built-in voices.

## Build

- See [BUILD.md](BUILD.md) for setup and build instructions.
- See [opencode-session.md](opencode-session.md) for full opencode session logs.

## Credits

- [Kokoro-82M v1.0 ONNX](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX) -- the TTS model
- [eSpeak-ng](https://github.com/espeak-ng/espeak-ng) -- text-to-phoneme engine (via [csukuangfj fork](https://github.com/csukuangfj/espeak-ng))
- [misaki](https://github.com/hexgrad/misaki) -- eSpeak-to-Kokoro phoneme mapping reference
- [Kokoro-82M-Android](https://github.com/puff-dayo/Kokoro-82M-Android) -- reference project for running Kokoro on Android
- **Claude Opus 4.6 + OpenCode** -- all the coding and documentation

# Demo
https://github.com/user-attachments/assets/eb6358aa-a0ad-4660-8d62-83a4bd1e9eb0

#include <jni.h>
#include <string>
#include <cstring>
#include <android/log.h>
#include <espeak-ng/speak_lib.h>

#define TAG "EspeakJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_initialized = false;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_mytts_engine_EspeakBridge_nativeInit(
        JNIEnv *env, jobject /* this */, jstring dataPath) {
    if (g_initialized) {
        LOGI("eSpeak already initialized, skipping");
        return 0;
    }

    const char *path = env->GetStringUTFChars(dataPath, nullptr);
    if (!path) {
        LOGE("Failed to get data path string");
        return -1;
    }

    LOGI("Initializing eSpeak with data path: %s", path);

    // AUDIO_OUTPUT_SYNCHRONOUS = no audio, just text processing
    // buflength = 0 (default, not used for synchronous)
    // options = 0 (no phoneme events needed)
    int sampleRate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, 0);

    env->ReleaseStringUTFChars(dataPath, path);

    if (sampleRate == -1) {
        LOGE("espeak_Initialize failed");
        return -1;
    }

    LOGI("eSpeak initialized, sample rate: %d", sampleRate);

    // Set American English voice
    espeak_ERROR err = espeak_SetVoiceByName("en-us");
    if (err != EE_OK) {
        LOGE("espeak_SetVoiceByName(en-us) failed: %d", err);
        return -2;
    }

    g_initialized = true;
    LOGI("eSpeak ready (en-us)");
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_example_mytts_engine_EspeakBridge_nativeTextToPhonemes(
        JNIEnv *env, jobject /* this */, jstring text) {
    if (!g_initialized) {
        LOGE("eSpeak not initialized");
        return env->NewStringUTF("");
    }

    const char *inputText = env->GetStringUTFChars(text, nullptr);
    if (!inputText) {
        return env->NewStringUTF("");
    }

    // phonememode bits:
    //   bit 1 (0x02): IPA output (UTF-8)
    //   bit 7 (0x80): use bits 8-23 as tie character for multi-letter phonemes
    //   bits 8-23: tie character ('^' = 0x5E)
    // Result: 0x02 | 0x80 | (0x5E << 8) = 0x5E82
    int phonememode = 0x02 | 0x80 | ('^' << 8);

    std::string input(inputText);
    env->ReleaseStringUTFChars(text, inputText);

    // Phonemize a text fragment via eSpeak's clause-by-clause processing.
    auto phonemizeFragment = [phonememode](const char *frag) -> std::string {
        std::string fragResult;
        const void *ptr = frag;
        while (ptr != nullptr) {
            const char *ph = espeak_TextToPhonemes(&ptr, espeakCHARS_UTF8, phonememode);
            if (ph && *ph) {
                if (!fragResult.empty()) fragResult += ' ';
                fragResult += ph;
            }
        }
        return fragResult;
    };

    // Split at clause-boundary punctuation (, ; :) followed by whitespace.
    // Phonemize each fragment separately and rejoin with the punctuation,
    // so the Kokoro model sees commas/semicolons/colons and produces
    // natural pauses at clause boundaries.
    //
    // We cannot rely on eSpeak's internal clause splitting to preserve
    // these characters — it consumes them as delimiters and they never
    // appear in the phoneme output.
    std::string result;
    size_t start = 0;

    for (size_t i = 0; i < input.size(); i++) {
        char c = input[i];
        if ((c == ',' || c == ';' || c == ':') &&
            i + 1 < input.size() && input[i + 1] == ' ') {
            // Phonemize the fragment before this delimiter
            if (i > start) {
                std::string frag = input.substr(start, i - start);
                std::string ph = phonemizeFragment(frag.c_str());
                if (!ph.empty()) {
                    if (!result.empty()) result += ' ';
                    result += ph;
                }
            }
            // Insert the clause-boundary punctuation
            result += c;
            // Advance past the delimiter and trailing whitespace
            i++; // skip past the punctuation (now on the space)
            while (i + 1 < input.size() && input[i + 1] == ' ') {
                i++;
            }
            start = i + 1;
        }
    }

    // Phonemize any remaining text after the last delimiter
    if (start < input.size()) {
        std::string frag = input.substr(start);
        std::string ph = phonemizeFragment(frag.c_str());
        if (!ph.empty()) {
            if (!result.empty()) result += ' ';
            result += ph;
        }
    }

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_mytts_engine_EspeakBridge_nativeTerminate(
        JNIEnv * /* env */, jobject /* this */) {
    if (g_initialized) {
        espeak_Terminate();
        g_initialized = false;
        LOGI("eSpeak terminated");
    }
}

} // extern "C"

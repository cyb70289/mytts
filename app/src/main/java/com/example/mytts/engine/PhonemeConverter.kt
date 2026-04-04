package com.example.mytts.engine

import android.content.Context
import android.util.Log

/**
 * Converts English text to IPA phonemes for Kokoro TTS.
 *
 * Uses eSpeak-ng (via NDK/JNI) for grapheme-to-phoneme conversion, with
 * minimal IPA normalization to produce phonemes compatible with the Kokoro
 * model (raw IPA diphthongs, no E2M compact tokens).
 */
class PhonemeConverter(context: Context) {
    companion object {
        private const val TAG = "PhonemeConverter"

        // Characters that exist in the Kokoro VOCAB
        val VALID_CHARS: Set<Char> = run {
            val pad = '$'
            val punctuation = ";:,.!?\u00A1\u00BF\u2014\u2026\"\u00AB\u00BB\u201C\u201D "
            val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            val lettersIpa =
                "\u0251\u0250\u0252\u00E6\u0253\u0299\u03B2\u0254\u0255\u00E7\u0257\u0256\u00F0\u02A4\u0259\u0258\u025A\u025B\u025C\u025D\u025E\u025F\u0284\u0261\u0260\u0262\u029B\u0266\u0267\u0127\u0265\u029C\u0268\u026A\u029D\u026D\u026C\u026B\u026E\u029F\u0271\u026F\u0270\u014B\u0273\u0272\u0274\u00F8\u0275\u0278\u03B8\u0153\u0276\u0298\u0279\u027A\u027E\u027B\u0280\u0281\u027D\u0282\u0283\u0288\u02A7\u0289\u028A\u028B\u2C71\u028C\u0263\u0264\u028D\u03C7\u028E\u028F\u0291\u0290\u0292\u0294\u02A1\u0295\u02A2\u01C0\u01C1\u01C2\u01C3\u02C8\u02CC\u02D0\u02D1\u02BC\u02B4\u02B0\u02B1\u02B2\u02B7\u02E0\u02E4\u02DE\u2193\u2191\u2192\u2197\u2198\u2019\u0329\u2018\u1D7B"
            (listOf(pad) + punctuation.toList() + letters.toList() + lettersIpa.toList()).toSet()
        }
    }

    private val espeakBridge = EspeakBridge(context)

    init {
        val success = espeakBridge.initialize()
        if (!success) {
            Log.e(TAG, "Failed to initialize eSpeak-ng!")
        }
    }

    /**
     * Convert text to IPA phoneme string suitable for Kokoro tokenizer.
     */
    fun phonemize(text: String): String {
        val normalized = normalizeText(text)
        return phonemizeNormalized(normalized)
    }

    /**
     * Convert pre-normalized text to IPA phonemes.
     * Skips normalizeText() since the caller already did it.
     */
    fun phonemizeNormalized(normalizedText: String): String {
        val rawIpa = espeakBridge.textToPhonemes(normalizedText)
        if (rawIpa.isEmpty()) {
            Log.w(TAG, "eSpeak returned empty phonemes for: $normalizedText")
            return ""
        }
        return postProcess(rawIpa)
    }

    /**
     * Release eSpeak-ng resources.
     */
    fun release() {
        espeakBridge.terminate()
    }

    /**
     * Apply post-processing to convert eSpeak IPA output to Kokoro-compatible
     * phoneme format. Only normalizes eSpeak-specific symbols; keeps raw IPA
     * diphthongs (eɪ, aɪ, oʊ, etc.) intact — the model expects them as
     * individual IPA characters, not compressed single-letter tokens.
     */
    private fun postProcess(phonemes: String): String {
        // Strip tie characters (eSpeak may or may not produce them)
        var result = phonemes.replace("^", "")

        // Basic IPA normalizations
        result = result.replace("r", "\u0279")           // r → ɹ
        result = result.replace("x", "k")                // x → k
        result = result.replace("\u00E7", "k")            // ç → k
        result = result.replace("\u026C", "l")            // ɬ → l
        result = result.replace("\u02B2", "j")            // ʲ → j
        result = result.replace("\u025A", "\u0259\u0279") // ɚ → əɹ
        result = result.replace("\u0250", "\u0259")       // ɐ → ə
        result = result.replace("\u0303", "")             // strip combining tilde

        // Strip syllabic consonant marks (combining char below)
        result = result.replace("\u0329", "")

        // Strip $ pad token — must not leak into phoneme stream
        result = result.replace("\$", "")

        // Filter to only valid VOCAB characters
        result = result.filter { ch ->
            ch in VALID_CHARS || ch.isWhitespace()
        }

        return result.trim()
    }

    /**
     * Normalize text before phonemization.
     *
     * Only handles cases that eSpeak-ng cannot process correctly:
     * - Full-width CJK punctuation (eSpeak's English voice ignores these)
     * - Time expressions (colon is a clause separator in eSpeak)
     * - Currency symbols (eSpeak reads "$500" as "dollar five hundred")
     * - Currency magnitude suffixes like $50B (eSpeak doesn't know K/M/B/T)
     * - Number ranges with hyphens (eSpeak may read "5-10" as "five minus ten")
     *
     * Everything else (numbers, ordinals, abbreviations, %, #, &, @, etc.)
     * is handled natively by eSpeak-ng's English text normalization.
     */
    fun normalizeText(text: String): String {
        var t = text
            .lines().joinToString("\n") { it.trim() }
            // Full-width CJK punctuation → ASCII equivalents
            .replace("\u3001", ", ")
            .replace("\u3002", ". ")
            .replace("\uFF01", "! ")
            .replace("\uFF0C", ", ")
            .replace("\uFF1A", ": ")
            .replace("\uFF1B", "; ")
            .replace("\uFF1F", "? ")

        // --- Time expressions (colon is clause separator in eSpeak) ---
        // 3:30pm → 3 30 p m, 12:00 → 12 o'clock, 3:05 → 3 oh 5
        t = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?").replace(t) { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val min = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val ampm = match.groupValues[3]
            if (hour > 24 || min > 59) return@replace match.value
            val minPart = when {
                min == 0 -> "o'clock"
                min < 10 -> "oh $min"
                else -> "$min"
            }
            val suffix = when (ampm.lowercase()) {
                "am" -> " a m"
                "pm" -> " p m"
                else -> ""
            }
            "$hour $minPart$suffix"
        }

        // --- Currency (eSpeak reads symbols in wrong order) ---

        // Currency with cents: $3.50 → 3 dollars and 50 cents
        t = Regex("([\\$\u20AC\u00A3])\\s*(\\d+)\\.(\\d{2})\\b").replace(t) { match ->
            val dollars = match.groupValues[2].toLongOrNull() ?: return@replace match.value
            val cents = match.groupValues[3].toIntOrNull() ?: return@replace match.value
            val currency = when (match.groupValues[1]) {
                "\$" -> "dollar" to "cent"
                "\u20AC" -> "euro" to "cent"
                "\u00A3" -> "pound" to "penny"
                else -> "dollar" to "cent"
            }
            val dollarUnit = if (dollars == 1L) currency.first else "${currency.first}s"
            if (cents == 0) {
                "$dollars $dollarUnit"
            } else {
                val centUnit = if (cents == 1) {
                    currency.second
                } else if (currency.second == "penny") {
                    "pence"
                } else {
                    "${currency.second}s"
                }
                "$dollars $dollarUnit and $cents $centUnit"
            }
        }

        // Currency with optional magnitude suffix: $50B, €3.5M, £100K, ¥1000
        t = Regex("([\\$\u20AC\u00A3\u00A5])\\s*(\\d+(?:\\.\\d+)?)\\s*([BMKTbmkt])?\\b")
            .replace(t) { match ->
                val number = match.groupValues[2]
                val mag = match.groupValues[3].uppercase()
                val currency = when (match.groupValues[1]) {
                    "\$" -> "dollars"; "\u20AC" -> "euros"
                    "\u00A3" -> "pounds"; "\u00A5" -> "yen"
                    else -> "dollars"
                }
                val magnitude = when (mag) {
                    "T" -> " trillion"; "B" -> " billion"
                    "M" -> " million"; "K" -> " thousand"
                    else -> ""
                }
                "$number$magnitude $currency"
            }

        // Clean up stray currency symbols not matched by patterns above
        t = t.replace(Regex("[\\$\u20AC\u00A3\u00A5]"), " ")

        // Number ranges: 5-10 → 5 to 10 (eSpeak may read hyphen as "minus")
        t = t.replace(Regex("(?<=\\d)-(?=\\d)"), " to ")

        return t.trim()
    }
}

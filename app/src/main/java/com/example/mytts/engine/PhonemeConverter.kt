package com.example.mytts.engine

import android.content.Context
import android.util.Log

/**
 * Converts English text to IPA phonemes for Kokoro TTS.
 *
 * Uses eSpeak-ng (via NDK/JNI) for grapheme-to-phoneme conversion, with
 * post-processing based on the misaki library's E2M mapping to produce
 * phonemes compatible with how the Kokoro model was trained.
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

        // Number words for text-to-speech
        private val ONES = arrayOf(
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
        )
        private val TENS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
        )

        // Misaki E2M (eSpeak-to-Misaki) replacements for American English.
        // Applied longest-first to eSpeak IPA output with '^' tie characters.
        // Source: hexgrad/misaki espeak.py
        private val E2M_REPLACEMENTS = listOf(
            // Multi-char sequences first (longest match wins)
            "\u0294\u02CCn\u0329" to "\u0294n",   // ʔˌn̩ → ʔn
            "\u0294n\u0329" to "\u0294n",           // ʔn̩ → ʔn
            "a^\u026A" to "I",                      // a^ɪ → I (PRICE)
            "a^\u028A" to "W",                      // a^ʊ → W (MOUTH)
            "d^\u0292" to "\u02A4",                 // d^ʒ → ʤ (JUDGE)
            "t^\u0283" to "\u02A7",                 // t^ʃ → ʧ (CHURCH)
            "e^\u026A" to "A",                      // e^ɪ → A (FACE)
            "\u0254^\u026A" to "Y",                 // ɔ^ɪ → Y (CHOICE)
            "\u0259^l" to "\u1D4Al",                // ə^l → ᵊl (syllabic l)
            "\u02B2o" to "jo",                      // ʲo → jo
            "\u02B2\u0259" to "j\u0259",            // ʲə → jə
            "\u02B2" to "",                         // ʲ → (delete)
            "\u025A" to "\u0259\u0279",             // ɚ → əɹ (rhotacized schwa)
            "r" to "\u0279",                        // r → ɹ
            "x" to "k",                             // x → k
            "\u00E7" to "k",                        // ç → k
            "\u0250" to "\u0259",                   // ɐ → ə
            "\u026C" to "l",                        // ɬ → l
            "\u0303" to "",                         // combining tilde → delete
        )

        // American English dialect-specific replacements (applied after E2M)
        private val AMERICAN_REPLACEMENTS = listOf(
            "o^\u028A" to "O",                      // o^ʊ → O (GOAT American)
            "\u025C\u02D0\u0279" to "\u025C\u0279", // ɜːɹ → ɜɹ (NURSE)
            "\u025C\u02D0" to "\u025C\u0279",       // ɜː → ɜɹ (NURSE without explicit r)
            "\u026A\u0259" to "i\u0259",            // ɪə → iə (NEAR)
        )
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
        // Get raw IPA from eSpeak-ng (with '^' tie characters)
        val rawIpa = espeakBridge.textToPhonemes(normalizedText)
        if (rawIpa.isEmpty()) {
            Log.w(TAG, "eSpeak returned empty phonemes for: $normalizedText")
            return ""
        }

        // Apply misaki E2M mapping
        return postProcess(rawIpa)
    }

    /**
     * Release eSpeak-ng resources.
     */
    fun release() {
        espeakBridge.terminate()
    }

    /**
     * Apply misaki E2M post-processing to convert eSpeak IPA output
     * to Kokoro-compatible phoneme format.
     */
    private fun postProcess(phonemes: String): String {
        var result = phonemes

        // Apply E2M replacements (longest-first ordering)
        for ((from, to) in E2M_REPLACEMENTS) {
            result = result.replace(from, to)
        }

        // Handle syllabic consonants: combining character below (U+0329)
        // → small schwa (ᵊ) before the consonant
        result = Regex("(\\S)\u0329").replace(result) { match ->
            "\u1D4A${match.groupValues[1]}"
        }
        // Remove any remaining combining marks
        result = result.replace("\u0329", "")

        // American English dialect
        for ((from, to) in AMERICAN_REPLACEMENTS) {
            result = result.replace(from, to)
        }

        // Strip ALL remaining length marks (American English doesn't use them)
        result = result.replace("\u02D0", "")

        // eSpeak compatibility: bare 'e' → A (FACE vowel) — only bare 'e' not part of other sequences
        // Note: at this point, e^ɪ is already mapped to A, but bare 'e' can still appear
        result = result.replace("e", "A")

        // Kokoro model-specific mappings (for v1.0, non-2.0 models)
        result = result.replace("\u027E", "T")    // ɾ (flap) → T
        result = result.replace("\u0294", "t")     // ʔ (glottal stop) → t

        // Remove remaining tie characters
        result = result.replace("^", "")

        // Map bare 'o' → ɔ (eSpeak compatibility for older versions)
        result = result.replace("o", "\u0254")

        // Strip $ pad token — must not leak into phoneme stream
        result = result.replace("\$", "")

        // Filter to only valid VOCAB characters
        result = result.filter { ch ->
            ch in VALID_CHARS || ch.isWhitespace()
        }

        return result.trim()
    }

    fun normalizeText(text: String): String {
        var t = text
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("[\u2018\u2019]"), "'")
            .replace(Regex("[\u201C\u201D\u00AB\u00BB]"), "\"")
            // Full-width punctuation
            .replace("\u3001", ", ")
            .replace("\u3002", ". ")
            .replace("\uFF01", "! ")
            .replace("\uFF0C", ", ")
            .replace("\uFF1A", ": ")
            .replace("\uFF1B", "; ")
            .replace("\uFF1F", "? ")

        // Common abbreviations (eSpeak handles Dr./Mr. itself, but we keep these
        // for consistency with text display)
        t = t.replace(Regex("\\bD[Rr]\\.(?= [A-Z])"), "Doctor")
            .replace(Regex("\\b(?:Mr\\.|MR\\.(?= [A-Z]))"), "Mister")
            .replace(Regex("\\b(?:Ms\\.|MS\\.(?= [A-Z]))"), "Miss")
            .replace(Regex("\\b(?:Mrs\\.|MRS\\.(?= [A-Z]))"), "Missus")
            .replace(Regex("\\betc\\.(?! [A-Z])"), "etcetera")

        // --- Currency, units, symbols (BEFORE number-to-words) ---

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

        // Percentage: 50% → 50 percent
        t = t.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%"), "$1 percent")

        // Hashtag number: #1 → number 1
        t = t.replace(Regex("#\\s*(\\d+)"), "number $1")

        // Common symbols
        t = t.replace("&", " and ")
        t = t.replace("@", " at ")

        // Clean up any stray currency/symbol characters that weren't part of a pattern
        t = t.replace(Regex("[\\$\u20AC\u00A3\u00A5#]"), " ")

        // --- End currency/units/symbols ---

        // Number formatting
        t = t.replace(Regex("(?<=\\d),(?=\\d)"), "")
        t = t.replace(Regex("(?<=\\d)-(?=\\d)"), " to ")

        // Convert numbers to words
        t = t.replace(Regex("\\d+(\\.\\d+)?")) { match ->
            numberToWords(match.value)
        }

        // Collapse multiple spaces
        t = t.replace(Regex("\\s{2,}"), " ")

        return t.trim()
    }

    /**
     * Convert a number string to English words.
     * Handles integers up to 999,999,999 and simple decimals.
     */
    private fun numberToWords(numStr: String): String {
        // Handle decimals
        if ('.' in numStr) {
            val parts = numStr.split(".", limit = 2)
            val intPart = integerToWords(parts[0].toLongOrNull() ?: 0)
            val decPart = parts[1]
            val decWords = decPart.map { digitToWord(it) }.joinToString(" ")
            return "$intPart point $decWords"
        }
        val n = numStr.toLongOrNull() ?: return numStr
        return integerToWords(n)
    }

    private fun digitToWord(ch: Char): String = when (ch) {
        '0' -> "zero"; '1' -> "one"; '2' -> "two"; '3' -> "three"; '4' -> "four"
        '5' -> "five"; '6' -> "six"; '7' -> "seven"; '8' -> "eight"; '9' -> "nine"
        else -> ch.toString()
    }

    private fun integerToWords(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "negative ${integerToWords(-n)}"

        val parts = mutableListOf<String>()
        var remaining = n

        if (remaining >= 1_000_000_000) {
            parts.add("${integerToWords(remaining / 1_000_000_000)} billion")
            remaining %= 1_000_000_000
        }
        if (remaining >= 1_000_000) {
            parts.add("${integerToWords(remaining / 1_000_000)} million")
            remaining %= 1_000_000
        }
        if (remaining >= 1000) {
            parts.add("${integerToWords(remaining / 1000)} thousand")
            remaining %= 1000
        }
        if (remaining >= 100) {
            parts.add("${ONES[remaining.toInt() / 100]} hundred")
            remaining %= 100
        }
        if (remaining > 0) {
            if (parts.isNotEmpty()) parts.add("and")
            if (remaining < 20) {
                parts.add(ONES[remaining.toInt()])
            } else {
                val t = TENS[remaining.toInt() / 10]
                val o = ONES[remaining.toInt() % 10]
                parts.add(if (o.isEmpty()) t else "$t $o")
            }
        }
        return parts.joinToString(" ")
    }
}

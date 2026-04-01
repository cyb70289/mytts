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

        // Number words for text-to-speech
        private val ONES = arrayOf(
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
        )
        private val TENS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
        )
        private val ORDINAL_ONES = arrayOf(
            "", "first", "second", "third", "fourth", "fifth", "sixth", "seventh",
            "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth",
            "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth"
        )
        private val ORDINAL_TENS = arrayOf(
            "", "", "twentieth", "thirtieth", "fortieth", "fiftieth",
            "sixtieth", "seventieth", "eightieth", "ninetieth"
        )

        // IPA normalizations for eSpeak-ng output.
        // These only normalize eSpeak-specific symbols to standard IPA that
        // the Kokoro model was trained on. NO diphthong/affricate compression
        // (the model expects raw IPA like eɪ, aɪ, oʊ — not E2M compact tokens
        // like A, I, O which are uppercase letters with different meaning).
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

        // Common abbreviations (eSpeak handles Dr./Mr. itself, but we expand
        // for consistency with what the user sees in the text display)
        t = t.replace(Regex("\\bD[Rr]\\.(?= [A-Z])"), "Doctor")
            .replace(Regex("\\b(?:Mr\\.|MR\\.(?= [A-Z]))"), "Mister")
            .replace(Regex("\\b(?:Ms\\.|MS\\.(?= [A-Z]))"), "Miss")
            .replace(Regex("\\b(?:Mrs\\.|MRS\\.(?= [A-Z]))"), "Missus")
            .replace(Regex("\\betc\\.(?! [A-Z])"), "etcetera")

        // --- Time expressions (BEFORE number-to-words) ---
        // 3:30pm → three thirty p m, 12:00 → twelve o'clock
        t = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?").replace(t) { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val min = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val ampm = match.groupValues[3]
            if (hour > 24 || min > 59) return@replace match.value
            val hourWord = integerToWords(hour.toLong())
            val minWord = when {
                min == 0 -> "o'clock"
                min < 10 -> "oh ${integerToWords(min.toLong())}"
                else -> integerToWords(min.toLong())
            }
            val suffix = when (ampm.lowercase()) {
                "am" -> " a m"
                "pm" -> " p m"
                else -> ""
            }
            "$hourWord $minWord$suffix"
        }

        // --- Currency, units, symbols (BEFORE number-to-words) ---

        // Currency with cents: $3.50 → three dollars and fifty cents
        t = Regex("([\\$\u20AC\u00A3])\\s*(\\d+)\\.(\\d{2})\\b").replace(t) { match ->
            val dollars = match.groupValues[2].toLongOrNull() ?: return@replace match.value
            val cents = match.groupValues[3].toIntOrNull() ?: return@replace match.value
            val currency = when (match.groupValues[1]) {
                "\$" -> "dollar" to "cent"
                "\u20AC" -> "euro" to "cent"
                "\u00A3" -> "pound" to "penny"
                else -> "dollar" to "cent"
            }
            val dollarWord = integerToWords(dollars)
            val dollarUnit = if (dollars == 1L) currency.first else "${currency.first}s"
            if (cents == 0) {
                "$dollarWord $dollarUnit"
            } else {
                val centWord = integerToWords(cents.toLong())
                val centUnit = if (cents == 1) {
                    currency.second
                } else if (currency.second == "penny") {
                    "pence"
                } else {
                    "${currency.second}s"
                }
                "$dollarWord $dollarUnit and $centWord $centUnit"
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

        // --- Ordinals: 1st, 2nd, 3rd, 21st, etc. (BEFORE general number conversion) ---
        t = Regex("(\\d+)(?:st|nd|rd|th)\\b").replace(t) { match ->
            val n = match.groupValues[1].toLongOrNull() ?: return@replace match.value
            ordinalToWords(n)
        }

        // --- Year-like numbers: 4-digit numbers 1000-2099 in likely year contexts ---
        // Standalone years or years preceded by "in", "of", "from", "since", "by", etc.
        t = Regex("(?<=\\b(?:in|of|from|since|by|year|circa|around|before|after|during)\\s)(\\d{4})\\b")
            .replace(t) { match ->
                val n = match.groupValues[1].toLongOrNull() ?: return@replace match.value
                yearToWords(n)
            }

        // Number formatting
        t = t.replace(Regex("(?<=\\d),(?=\\d)"), "")
        t = t.replace(Regex("(?<=\\d)-(?=\\d)"), " to ")

        // Convert remaining numbers to words
        t = t.replace(Regex("\\d+(\\.\\d+)?")) { match ->
            numberToWords(match.value)
        }

        // Collapse multiple spaces
        t = t.replace(Regex("\\s{2,}"), " ")

        return t.trim()
    }

    /**
     * Convert a number string to English words.
     * Handles integers up to 999 trillion and simple decimals.
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

    /**
     * Convert a year number to natural speech.
     * 2024 → "twenty twenty four", 1999 → "nineteen ninety nine",
     * 2000 → "two thousand", 2001 → "two thousand one".
     */
    private fun yearToWords(n: Long): String {
        if (n < 1000 || n > 2099) return integerToWords(n)

        // 2000-2009: "two thousand [N]"
        if (n in 2000..2009) {
            return if (n == 2000L) "two thousand"
            else "two thousand ${ONES[n.toInt() - 2000]}"
        }

        // Split into century + remainder: 2024 → "twenty" + "twenty four"
        val century = (n / 100).toInt()
        val remainder = (n % 100).toInt()
        val centuryWord = integerToWords(century.toLong())
        return if (remainder == 0) {
            "${centuryWord} hundred"
        } else if (remainder < 10) {
            "$centuryWord oh ${ONES[remainder]}"
        } else {
            "$centuryWord ${integerToWords(remainder.toLong())}"
        }
    }

    /**
     * Convert a number to its ordinal form.
     * 1 → "first", 21 → "twenty first", 100 → "one hundredth".
     */
    private fun ordinalToWords(n: Long): String {
        if (n <= 0) return integerToWords(n)

        // Simple cases: 1-19
        if (n < 20) return ORDINAL_ONES[n.toInt()]

        // 20, 30, ..., 90
        if (n < 100 && n % 10 == 0L) return ORDINAL_TENS[(n / 10).toInt()]

        // 21-99: "twenty first", "thirty second", etc.
        if (n < 100) {
            val tens = TENS[(n / 10).toInt()]
            val ones = ORDINAL_ONES[(n % 10).toInt()]
            return "$tens $ones"
        }

        // For larger numbers, use cardinal for the leading part + ordinal suffix
        // 100th → "one hundredth", 101st → "one hundred and first"
        val remainder = n % 100
        if (remainder == 0L) {
            return "${integerToWords(n / 100)} hundredth"
        }
        val leading = integerToWords(n - remainder)
        return "$leading and ${ordinalToWords(remainder)}"
    }

    private fun integerToWords(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "negative ${integerToWords(-n)}"

        val parts = mutableListOf<String>()
        var remaining = n

        if (remaining >= 1_000_000_000_000) {
            parts.add("${integerToWords(remaining / 1_000_000_000_000)} trillion")
            remaining %= 1_000_000_000_000
        }
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

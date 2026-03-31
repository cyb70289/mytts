package com.example.mytts.engine

import android.content.Context
import android.util.Log
import com.github.medavox.ipa_transcribers.Language

/**
 * Converts English text to IPA phonemes for Kokoro TTS.
 *
 * Uses a CMU Pronouncing Dictionary (IPA format) for known words,
 * with fallback to medavox rule-based transcriber for unknown words.
 */
class PhonemeConverter(context: Context) {
    companion object {
        private const val TAG = "PhonemeConverter"
        private const val DICT_FILE = "cmudict_ipa.txt"

        // Characters that exist in the Kokoro VOCAB
        val VALID_CHARS: Set<Char> = run {
            val pad = '$'
            val punctuation = ";:,.!?\u00A1\u00BF\u2014\u2026\"\u00AB\u00BB\u201C\u201D "
            val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            val lettersIpa =
                "\u0251\u0250\u0252\u00E6\u0253\u0299\u03B2\u0254\u0255\u00E7\u0257\u0256\u00F0\u02A4\u0259\u0258\u025A\u025B\u025C\u025D\u025E\u025F\u0284\u0261\u0260\u0262\u029B\u0266\u0267\u0127\u0265\u029C\u0268\u026A\u029D\u026D\u026C\u026B\u026E\u029F\u0271\u026F\u0270\u014B\u0273\u0272\u0274\u00F8\u0275\u0278\u03B8\u0153\u0276\u0298\u0279\u027A\u027E\u027B\u0280\u0281\u027D\u0282\u0283\u0288\u02A7\u0289\u028A\u028B\u2C71\u028C\u0263\u0264\u028D\u03C7\u028E\u028F\u0291\u0290\u0292\u0294\u02A1\u0295\u02A2\u01C0\u01C1\u01C2\u01C3\u02C8\u02CC\u02D0\u02D1\u02BC\u02B4\u02B0\u02B1\u02B2\u02B7\u02E0\u02E4\u02DE\u2193\u2191\u2192\u2197\u2198\u2019\u0329\u2018\u1D7B"
            (listOf(pad) + punctuation.toList() + letters.toList() + lettersIpa.toList()).toSet()
        }

        // Override CMU dict entries that are wrong for common usage
        // (CMU dict treats these as acronyms/letter names, not common words)
        private val WORD_OVERRIDES = mapOf(
            "A" to "\u0259",             // ə (article, not letter name)
            "AN" to "\u0259n",           // ən
            "IT" to "\u026At",           // ɪt
            "IT'S" to "\u026Ats",        // ɪts
            "ITS" to "\u026Ats",         // ɪts
            "I" to "a\u026A",            // aɪ
            "AM" to "\u00E6m",           // æm
            "AS" to "\u00E6z",           // æz
            "AT" to "\u00E6t",           // æt
            "IF" to "\u026Af",           // ɪf
            "IN" to "\u026An",           // ɪn
            "IS" to "\u026Az",           // ɪz
            "OF" to "\u0259v",           // əv
            "ON" to "\u0252n",           // ɒn
            "OR" to "\u0254\u0279",      // ɔɹ
            "TO" to "tu\u02D0",          // tuː
            "UP" to "\u028Ap",           // ʊp
            "US" to "\u028As",           // ʊs
        )

        // Number words for text-to-speech
        private val ONES = arrayOf(
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
        )
        private val TENS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
        )
    }

    private val phonemeMap = mutableMapOf<String, String>()
    private val englishTranscriber by lazy { Language.ENGLISH.transcriber }

    init {
        loadDictionary(context)
    }

    private fun loadDictionary(context: Context) {
        try {
            context.assets.open(DICT_FILE).bufferedReader().useLines { lines ->
                lines.filter { !it.startsWith(";;;") && it.isNotBlank() }.forEach { line ->
                    val parts = line.split("\t", limit = 2)
                    if (parts.size == 2) {
                        phonemeMap[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
            Log.i(TAG, "Dictionary loaded: ${phonemeMap.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dictionary", e)
        }
    }

    /**
     * Convert text to IPA phoneme string suitable for Kokoro tokenizer.
     */
    fun phonemize(text: String): String {
        val normalized = normalizeText(text)
        // Tokenize keeping contractions/possessives intact (e.g. "isn't" as one token).
        // Pattern: letters optionally followed by apostrophe+letters (contractions),
        // OR any non-letter sequence (punctuation, spaces).
        val tokens = Regex("[a-zA-Z]+(?:'[a-zA-Z]+)*|[^a-zA-Z]+")
            .findAll(normalized).map { it.value }.filter { it.isNotBlank() }.toList()

        val result = StringBuilder()
        for ((index, token) in tokens.withIndex()) {
            if (token.matches(Regex("[^a-zA-Z']+"))) {
                // Punctuation/whitespace - keep as-is
                result.append(token)
            } else {
                if (index > 0 && result.isNotEmpty() && !result.last().isWhitespace()) {
                    result.append(" ")
                }
                val ipa = convertWord(token)
                result.append(ipa)
            }
        }

        return postProcess(result.toString())
    }

    private fun convertWord(word: String): String {
        if (word.matches(Regex("[^a-zA-Z']+"))) return word

        val clean = word.replace(Regex("[^a-zA-Z']"), "").uppercase()

        // Check overrides first (fixes CMU dict treating common words as acronyms)
        val override = WORD_OVERRIDES[clean]
        if (override != null) return override

        val lookup = phonemeMap[clean]

        val raw = if (lookup != null) {
            lookup.split(",").first().trim()
        } else {
            // Fallback to rule-based transcription
            try {
                englishTranscriber.transcribe(word)
            } catch (e: Exception) {
                Log.w(TAG, "Transcription failed for '$word'", e)
                word.lowercase()
            }
        }

        val cleaned = raw.replace(" ", "").replace("\u02CC", "")
        return adjustStressMarkers(cleaned)
    }

    /**
     * Move stress markers to just before the stressed vowel.
     */
    private fun adjustStressMarkers(input: String): String {
        val vowels = setOf(
            'a', 'e', 'i', 'o', 'u',
            '\u0251', '\u0250', '\u0252', '\u00E6', '\u0254', '\u0259', '\u0258', '\u025A',
            '\u025B', '\u025C', '\u025D', '\u025E',
            '\u026A', '\u0268', '\u00F8', '\u0275', '\u0153', '\u0276', '\u0289', '\u028A', '\u028C',
            '\u02D0', '\u02D1'
        )

        val builder = StringBuilder(input)
        var i = 0
        while (i < builder.length) {
            if (builder[i] == '\u02C8') {
                val stressIndex = i
                for (j in stressIndex + 1 until builder.length) {
                    if (builder[j] in vowels) {
                        builder.deleteCharAt(stressIndex)
                        builder.insert(j - 1, '\u02C8')
                        i = j
                        break
                    }
                }
            }
            i++
        }
        return builder.toString()
    }

    private fun normalizeText(text: String): String {
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

        // Common abbreviations
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

    private fun postProcess(phonemes: String): String {
        var result = phonemes
            .replace("r", "\u0279")
            .replace("x", "k")
            .replace("\u02B2", "j")
            .replace("\u026C", "l")

        // Kokoro pronunciation fix
        result = result.replace("k\u0259k\u02C8o\u02D0\u0279o\u028A", "k\u02C8o\u028Ak\u0259\u0279o\u028A")
            .replace("k\u0259k\u02C8\u0254\u02D0\u0279\u0259\u028A", "k\u02C8\u0259\u028Ak\u0259\u0279\u0259\u028A")

        // American English adjustments
        result = result.replace("ti", "di")

        // Strip $ BEFORE the VALID_CHARS filter — $ is the pad token (token ID 0)
        // in Kokoro's vocabulary. Any stray $ that leaks through text normalization
        // would be tokenized as pad and corrupt the model input.
        result = result.replace("\$", "")

        // Filter to only valid VOCAB characters + punctuation
        result = result.filter { ch ->
            ch in VALID_CHARS || ch.isWhitespace()
        }

        return result.trim()
    }
}

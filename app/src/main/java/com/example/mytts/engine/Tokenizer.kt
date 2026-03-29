package com.example.mytts.engine

/**
 * Character-level tokenizer for Kokoro TTS.
 * Maps IPA phoneme characters to integer token IDs using the model's vocabulary.
 */
object Tokenizer {
    private val VOCAB: Map<Char, Int> by lazy {
        val pad = '$'
        val punctuation = ";:,.!?¡¿—…\"«»\"\" "
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val lettersIpa =
            "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"

        val symbols = listOf(pad) + punctuation.toList() + letters.toList() + lettersIpa.toList()
        symbols.withIndex().associate { (index, char) -> char to index }
    }

    /**
     * Convert a phoneme string to a list of token IDs.
     * Unknown characters are silently skipped.
     */
    fun tokenize(phonemes: String): LongArray {
        return phonemes.mapNotNull { ch -> VOCAB[ch]?.toLong() }.toLongArray()
    }

    /**
     * Return token IDs padded with 0 at start and end (required by the model).
     */
    fun tokenizeWithPadding(phonemes: String): LongArray {
        val tokens = tokenize(phonemes)
        return longArrayOf(0L) + tokens + longArrayOf(0L)
    }

    /** Maximum tokens per inference call (model limit). */
    const val MAX_PHONEME_LENGTH = 400
}

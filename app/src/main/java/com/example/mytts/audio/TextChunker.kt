package com.example.mytts.audio

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * A chunk of text with its position in the original input.
 */
data class TextChunk(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val isContinuation: Boolean = false
)

/**
 * Splits text into chunks suitable for TTS inference.
 * Each chunk should produce phonemes within the model's token limit (~400).
 * Splits at sentence boundaries first (using ICU4J BreakIterator for robust
 * abbreviation/decimal handling), then word boundaries for oversized sentences.
 * Sub-chunks of a split sentence are marked as continuations so the audio
 * layer can omit the inter-sentence silence gap between them.
 */
object TextChunker {

    // Approximate: 1 word ≈ 5-8 phoneme tokens. 400 tokens ≈ 50-80 words.
    // Use conservative limit of ~50 words per chunk for safety.
    private const val MAX_WORDS_PER_CHUNK = 50

    /**
     * Split text into chunks with offset tracking.
     * Empty text returns an empty list.
     *
     * Sentences within the word limit become one chunk each.
     * Longer sentences are split at word boundaries every ~50 words;
     * sub-chunks after the first are marked [isContinuation]=true
     * so the audio layer can skip the inter-sentence silence gap.
     */
    fun chunk(text: String): List<TextChunk> {
        if (text.isBlank()) return emptyList()

        // First split into sentences using ICU4J BreakIterator
        val sentences = splitIntoSentences(text)
        val chunks = mutableListOf<TextChunk>()

        for (sentence in sentences) {
            if (sentence.text.isBlank()) continue

            val wordCount = sentence.text.trim().split(Regex("\\s+")).size
            if (wordCount <= MAX_WORDS_PER_CHUNK) {
                chunks.add(sentence)
            } else {
                // Sentence too long - split at word boundaries
                chunks.addAll(splitByWords(sentence.text, sentence.startOffset))
            }
        }

        return chunks
    }

    /**
     * Split text into sentences using ICU4J BreakIterator.
     * Handles abbreviations (Dr., Mr., U.S.A.), decimal numbers (3.14),
     * ellipses (...), and other edge cases automatically.
     */
    private fun splitIntoSentences(text: String): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        val bi = BreakIterator.getSentenceInstance(Locale.US)
        bi.setText(text)

        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val segment = text.substring(start, end)
            if (segment.isNotBlank()) {
                result.add(TextChunk(segment, start, end))
            }
            start = end
            end = bi.next()
        }

        // Handle any remaining text (shouldn't happen with BreakIterator, but be safe)
        if (start < text.length) {
            val remaining = text.substring(start)
            if (remaining.isNotBlank()) {
                result.add(TextChunk(remaining, start, text.length))
            }
        }

        if (result.isEmpty() && text.isNotBlank()) {
            result.add(TextChunk(text, 0, text.length))
        }

        return result
    }

    /**
     * Split by word boundaries when a sentence exceeds [MAX_WORDS_PER_CHUNK].
     * Prefers splitting after clause-boundary punctuation (`, ; :`) so that
     * each chunk ends at a natural prosodic break — the model generates
     * appropriate trailing intonation and the gap between chunks sounds
     * intentional rather than artificial. Falls back to a plain word-boundary
     * split when no punctuation is found.
     *
     * The first sub-chunk keeps [isContinuation]=false; subsequent ones are
     * marked true so the audio layer omits the inter-sentence silence gap.
     */
    private fun splitByWords(text: String, baseOffset: Int): List<TextChunk> {
        val words = text.split(Regex("(?<=\\s)"))
        val chunks = mutableListOf<TextChunk>()
        var currentWords = mutableListOf<String>()
        var chunkStart = 0
        var pos = 0

        for ((index, word) in words.withIndex()) {
            currentWords.add(word)
            pos += word.length

            if (currentWords.size >= MAX_WORDS_PER_CHUNK) {
                // Scan backward for the last clause-boundary punctuation.
                // Splitting there gives the model a natural prosodic endpoint.
                var splitAt = currentWords.size  // default: take all
                for (j in currentWords.size - 1 downTo 1) {
                    val w = currentWords[j - 1].trimEnd()
                    if (w.endsWith(',') || w.endsWith(';') || w.endsWith(':')) {
                        splitAt = j
                        break
                    }
                }

                val emitWords = currentWords.subList(0, splitAt)
                val chunkText = emitWords.joinToString("")
                chunks.add(TextChunk(
                    chunkText,
                    baseOffset + chunkStart,
                    baseOffset + chunkStart + chunkText.length,
                    isContinuation = chunks.isNotEmpty()
                ))
                chunkStart += chunkText.length
                currentWords = currentWords.subList(splitAt, currentWords.size).toMutableList()
            }
        }

        if (currentWords.isNotEmpty()) {
            val chunkText = currentWords.joinToString("")
            chunks.add(TextChunk(
                chunkText,
                baseOffset + chunkStart,
                baseOffset + chunkStart + chunkText.length,
                isContinuation = chunks.isNotEmpty()
            ))
        }

        return chunks
    }
}

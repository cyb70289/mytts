package com.example.mytts.audio

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * A chunk of text with its position in the original input.
 */
data class TextChunk(
    val text: String,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * Splits text into chunks suitable for TTS inference.
 * Each chunk should produce phonemes within the model's token limit (~400).
 * Splits at sentence boundaries first (using ICU4J BreakIterator for robust
 * abbreviation/decimal handling), then clause boundaries, then word boundaries.
 */
object TextChunker {

    // Approximate: 1 word ≈ 5-8 phoneme tokens. 400 tokens ≈ 50-80 words.
    // Use conservative limit of ~50 words per chunk for safety.
    private const val MAX_WORDS_PER_CHUNK = 50
    // Minimum chunk size to avoid tiny fragments
    private const val MIN_WORDS_PER_CHUNK = 3

    private val CLAUSE_SEPARATORS = Regex("[,;:\\-–—]+\\s*")

    /**
     * Split text into chunks with offset tracking.
     * Empty text returns an empty list.
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
                // Sentence too long - split at clause boundaries
                val clauses = splitKeepingDelimiters(sentence.text, CLAUSE_SEPARATORS, sentence.startOffset)
                for (clause in clauses) {
                    if (clause.text.isBlank()) continue
                    val clauseWordCount = clause.text.trim().split(Regex("\\s+")).size
                    if (clauseWordCount <= MAX_WORDS_PER_CHUNK) {
                        chunks.add(clause)
                    } else {
                        // Still too long - split at word boundaries
                        chunks.addAll(splitByWords(clause.text, clause.startOffset))
                    }
                }
            }
        }

        // Merge tiny chunks with their neighbors
        return mergeSmallChunks(chunks)
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
     * Split text while keeping delimiters attached to the preceding segment.
     * Tracks offsets relative to the original text.
     */
    private fun splitKeepingDelimiters(
        text: String,
        pattern: Regex,
        baseOffset: Int = 0
    ): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        var lastEnd = 0

        val matches = pattern.findAll(text)
        for (match in matches) {
            val segEnd = match.range.last + 1
            if (segEnd > lastEnd) {
                val segText = text.substring(lastEnd, segEnd)
                if (segText.isNotBlank()) {
                    result.add(TextChunk(segText, baseOffset + lastEnd, baseOffset + segEnd))
                }
                lastEnd = segEnd
            }
        }

        // Remaining text after last delimiter
        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd)
            if (remaining.isNotBlank()) {
                result.add(TextChunk(remaining, baseOffset + lastEnd, baseOffset + text.length))
            }
        }

        if (result.isEmpty() && text.isNotBlank()) {
            result.add(TextChunk(text, baseOffset, baseOffset + text.length))
        }

        return result
    }

    /**
     * Split by word boundaries when clause splitting isn't enough.
     */
    private fun splitByWords(text: String, baseOffset: Int): List<TextChunk> {
        val words = text.split(Regex("(?<=\\s)"))
        val chunks = mutableListOf<TextChunk>()
        var currentWords = mutableListOf<String>()
        var chunkStart = 0
        var pos = 0

        for (word in words) {
            currentWords.add(word)
            pos += word.length
            if (currentWords.size >= MAX_WORDS_PER_CHUNK) {
                val chunkText = currentWords.joinToString("")
                chunks.add(TextChunk(chunkText, baseOffset + chunkStart, baseOffset + pos))
                chunkStart = pos
                currentWords = mutableListOf()
            }
        }

        if (currentWords.isNotEmpty()) {
            val chunkText = currentWords.joinToString("")
            chunks.add(TextChunk(chunkText, baseOffset + chunkStart, baseOffset + pos))
        }

        return chunks
    }

    /**
     * Merge chunks that are too small with their next neighbor.
     */
    private fun mergeSmallChunks(chunks: List<TextChunk>): List<TextChunk> {
        if (chunks.size <= 1) return chunks

        val merged = mutableListOf<TextChunk>()
        var i = 0
        while (i < chunks.size) {
            var current = chunks[i]
            val wordCount = current.text.trim().split(Regex("\\s+")).size

            // If tiny and there's a next chunk, merge with next
            if (wordCount < MIN_WORDS_PER_CHUNK && i + 1 < chunks.size) {
                val next = chunks[i + 1]
                current = TextChunk(
                    current.text + next.text,
                    current.startOffset,
                    next.endOffset
                )
                i += 2
            } else {
                i++
            }
            merged.add(current)
        }
        return merged
    }
}

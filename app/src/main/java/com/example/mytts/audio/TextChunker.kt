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
 *
 * The int8 Kokoro model's duration predictor under-allocates samples per token
 * as token count grows (>~80 phoneme tokens → truncation). Since 1 English word
 * ≈ 5-8 phoneme tokens, we keep chunks to ~12 words (~60-96 tokens) to stay in
 * the safe zone.
 *
 * Split priority:
 * 1. Sentence boundaries (ICU4J BreakIterator — handles abbreviations, decimals)
 * 2. Clause boundaries (commas, semicolons, colons, dashes)
 * 3. Conjunction/preposition boundaries ("and", "but", "or", "that", "which", etc.)
 * 4. Hard word-count split as last resort
 *
 * The 300ms inter-chunk silence gap (applied by AudioProducer) serves as a
 * natural breath pause between chunks.
 */
object TextChunker {

    // Target max words per chunk. 12 words ≈ 60-96 phoneme tokens, safely under
    // the ~80-token threshold where the int8 model starts truncating.
    private const val MAX_WORDS_PER_CHUNK = 12
    // Minimum chunk size to avoid tiny fragments that sound unnatural
    private const val MIN_WORDS_PER_CHUNK = 3

    // Clause-level punctuation: commas, semicolons, colons, dashes
    private val CLAUSE_SEPARATORS = Regex("[,;:\\-–—]+\\s*")

    // Words that serve as natural phrase boundaries. We split BEFORE these words.
    // Includes coordinating conjunctions, subordinating conjunctions, relative
    // pronouns, and common prepositions that start new phrases.
    private val SPLIT_BEFORE_WORDS = setOf(
        "and", "but", "or", "nor", "yet", "so",          // coordinating conjunctions
        "that", "which", "who", "whom", "whose", "where", // relative
        "because", "although", "though", "while", "when",  // subordinating
        "after", "before", "since", "until", "unless",     // subordinating
        "if", "whether", "as", "than",                     // subordinating
        "however", "therefore", "moreover", "furthermore",  // conjunctive adverbs
        "including", "especially", "particularly",          // phrase starters
    )

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

            val wordCount = countWords(sentence.text)
            if (wordCount <= MAX_WORDS_PER_CHUNK) {
                chunks.add(sentence)
            } else {
                // Sentence too long — try clause boundaries first
                chunks.addAll(splitSentence(sentence))
            }
        }

        // Merge tiny chunks with their neighbors
        return mergeSmallChunks(chunks)
    }

    /**
     * Split a single sentence that exceeds [MAX_WORDS_PER_CHUNK].
     * Tries clause boundaries first, then conjunction/preposition boundaries,
     * then falls back to hard word-count splitting.
     */
    private fun splitSentence(sentence: TextChunk): List<TextChunk> {
        // Try splitting at clause boundaries (commas, semicolons, etc.)
        val clauses = splitAtClauseBoundaries(sentence.text, sentence.startOffset)
        if (clauses.size > 1) {
            // Recursively split any clauses that are still too long
            val result = mutableListOf<TextChunk>()
            for (clause in clauses) {
                if (countWords(clause.text) <= MAX_WORDS_PER_CHUNK) {
                    result.add(clause)
                } else {
                    // Clause still too long — try conjunction split
                    result.addAll(splitAtConjunctions(clause))
                }
            }
            return result
        }

        // No clause boundaries — try conjunction/preposition split
        return splitAtConjunctions(sentence)
    }

    /**
     * Split a chunk at conjunction/preposition boundaries.
     * Falls back to hard word-count split if no suitable boundary found.
     */
    private fun splitAtConjunctions(chunk: TextChunk): List<TextChunk> {
        val text = chunk.text
        val words = splitIntoWordSpans(text)
        if (words.size <= MAX_WORDS_PER_CHUNK) return listOf(chunk)

        val result = mutableListOf<TextChunk>()
        var segStart = 0  // char offset within text
        var wordsSinceSplit = 0

        for ((idx, span) in words.withIndex()) {
            val word = text.substring(span.first, span.second).trim().lowercase()
            wordsSinceSplit++

            // Consider splitting BEFORE this word if:
            // 1. It's a known split word
            // 2. We have enough words accumulated (at least MIN_WORDS_PER_CHUNK)
            // 3. The segment would exceed MAX_WORDS_PER_CHUNK without splitting
            val needsSplit = wordsSinceSplit > MAX_WORDS_PER_CHUNK
            val canSplitHere = word in SPLIT_BEFORE_WORDS && wordsSinceSplit >= MIN_WORDS_PER_CHUNK

            if (canSplitHere || needsSplit) {
                val splitCharPos = if (canSplitHere || needsSplit) {
                    // Split before this word
                    span.first
                } else {
                    continue
                }
                val segText = text.substring(segStart, splitCharPos)
                if (segText.isNotBlank()) {
                    result.add(TextChunk(
                        segText,
                        chunk.startOffset + segStart,
                        chunk.startOffset + splitCharPos
                    ))
                }
                segStart = splitCharPos
                wordsSinceSplit = 1  // current word starts new segment
            }
        }

        // Remaining text
        if (segStart < text.length) {
            val remaining = text.substring(segStart)
            if (remaining.isNotBlank()) {
                result.add(TextChunk(
                    remaining,
                    chunk.startOffset + segStart,
                    chunk.startOffset + text.length
                ))
            }
        }

        return if (result.isEmpty()) listOf(chunk) else result
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

        // Handle any remaining text
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
     * Split text at clause-level punctuation, keeping delimiters attached to
     * the preceding segment.
     */
    private fun splitAtClauseBoundaries(
        text: String,
        baseOffset: Int
    ): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        var lastEnd = 0

        val matches = CLAUSE_SEPARATORS.findAll(text)
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
     * Get word spans (start, end char offsets) within text.
     * Each span includes trailing whitespace so concatenation reproduces the original.
     */
    private fun splitIntoWordSpans(text: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        val matcher = Regex("\\S+\\s*")
        for (match in matcher.findAll(text)) {
            spans.add(Pair(match.range.first, match.range.last + 1))
        }
        return spans
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
            val wordCount = countWords(current.text)

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

    private fun countWords(text: String): Int {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    }
}

package com.example.mytts.audio

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
 * Splits at sentence boundaries first, then clause boundaries, then word boundaries.
 */
object TextChunker {

    // Approximate: 1 word ≈ 5-8 phoneme tokens. 400 tokens ≈ 50-80 words.
    // Use conservative limit of ~50 words per chunk for safety.
    private const val MAX_WORDS_PER_CHUNK = 50
    // Minimum chunk size to avoid tiny fragments
    private const val MIN_WORDS_PER_CHUNK = 3

    // Sentence-ending punctuation followed by whitespace (or end of string).
    // But NOT periods that are part of abbreviations.
    // We use a custom split function instead of a simple regex.
    private val CLAUSE_SEPARATORS = Regex("[,;:\\-–—]+\\s*")

    // Common abbreviations that end with a dot but aren't sentence endings.
    // These are checked case-insensitively.
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "ave", "blvd",
        "dept", "est", "approx", "govt", "inc", "corp", "ltd", "co",
        "vs", "etc", "vol", "no", "jan", "feb", "mar", "apr", "jun",
        "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "al",  // et al.
        "gen", "gov", "sgt", "cpl", "pvt", "capt", "lt", "col", "cmdr",
        "ft", "mt", "pt"
    )

    /**
     * Split text into chunks with offset tracking.
     * Empty text returns an empty list.
     */
    fun chunk(text: String): List<TextChunk> {
        if (text.isBlank()) return emptyList()

        // First split into sentences using abbreviation-aware splitter
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
     * Split text into sentences, being careful about abbreviations.
     * A period is considered a sentence end only if:
     * - It is NOT preceded by a single uppercase letter (e.g., U. S. A.)
     * - The word before the period is NOT a known abbreviation (Mr. Dr. etc.)
     * - It IS followed by whitespace + uppercase letter, or end of text.
     */
    private fun splitIntoSentences(text: String): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        var sentenceStart = 0

        // Match punctuation (.!?) followed by whitespace
        val punctPattern = Regex("[.!?]+\\s+")
        val matches = punctPattern.findAll(text).toList()

        for (match in matches) {
            val punctEnd = match.range.last + 1
            val punctStart = match.range.first

            // Check if this period is actually a sentence end
            if (text[punctStart] == '.' && isAbbreviationDot(text, punctStart)) {
                continue // Skip — this dot is part of an abbreviation
            }

            // This looks like a real sentence boundary
            val segText = text.substring(sentenceStart, punctEnd)
            if (segText.isNotBlank()) {
                result.add(TextChunk(segText, sentenceStart, punctEnd))
            }
            sentenceStart = punctEnd
        }

        // Remaining text after last sentence break
        if (sentenceStart < text.length) {
            val remaining = text.substring(sentenceStart)
            if (remaining.isNotBlank()) {
                result.add(TextChunk(remaining, sentenceStart, text.length))
            }
        }

        if (result.isEmpty() && text.isNotBlank()) {
            result.add(TextChunk(text, 0, text.length))
        }

        return result
    }

    /**
     * Check if the period at position [dotIndex] is part of an abbreviation rather
     * than a sentence terminator.
     *
     * Returns true if:
     * - The dot is preceded by a single uppercase letter (U.S., D.C., etc.)
     * - The word before the dot is a known abbreviation (Mr., Dr., etc.)
     */
    private fun isAbbreviationDot(text: String, dotIndex: Int): Boolean {
        if (dotIndex <= 0) return false

        // Find the word immediately before the dot
        var wordEnd = dotIndex
        // Skip any preceding dots (for chained abbreviations like U.S.)
        var pos = dotIndex - 1
        while (pos >= 0 && (text[pos].isLetter() || text[pos] == '.')) {
            pos--
        }
        val wordStart = pos + 1
        if (wordStart >= wordEnd) return false

        val wordBeforeDot = text.substring(wordStart, wordEnd)

        // Single uppercase letter before dot: U. S. A. I. etc.
        if (wordBeforeDot.length == 1 && wordBeforeDot[0].isUpperCase()) {
            return true
        }

        // Chained abbreviations like "U.S" (the chars between dots are single letters)
        if (wordBeforeDot.contains('.')) {
            val parts = wordBeforeDot.split('.')
            val allSingleLetters = parts.all { it.length <= 1 }
            if (allSingleLetters) return true
        }

        // Known abbreviation (case-insensitive)
        val cleanWord = wordBeforeDot.replace(".", "").lowercase()
        if (cleanWord in ABBREVIATIONS) return true

        return false
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

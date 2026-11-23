package com.edupulse.ingeststatic.chunking;

/**
 * A chunk produced by {@link StructureAwareChunker}.
 *
 * @param index     0-based position of the chunk in the document
 * @param text      chunk text, overlap with the previous chunk included
 * @param pageStart page where the chunk text starts (1-based)
 * @param pageEnd   page where the chunk text ends (1-based, equal to {@code pageStart} for most chunks)
 * @param section   closest heading above the chunk, or {@code null} when none was detected
 */
public record TextChunk(int index, String text, int pageStart, int pageEnd, String section) {
}

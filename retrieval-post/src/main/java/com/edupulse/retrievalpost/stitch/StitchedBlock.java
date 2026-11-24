package com.edupulse.retrievalpost.stitch;

import java.util.List;

/**
 * A contiguous block of one or more adjacent chunks from a single document (same {@code doc_id}
 * and {@code version}), merged in {@code chunk_index} order with overlapping text deduplicated.
 */
public record StitchedBlock(
		String docId,
		Integer version,
		String source,
		Integer startPage,
		Integer endPage,
		Integer startChunkIndex,
		Integer endChunkIndex,
		List<Integer> mergedChunkIndices,
		String content,
		int estimatedTokens,
		Double topScore) {

	public StitchedBlock {
		mergedChunkIndices = mergedChunkIndices == null ? List.of() : List.copyOf(mergedChunkIndices);
	}
}

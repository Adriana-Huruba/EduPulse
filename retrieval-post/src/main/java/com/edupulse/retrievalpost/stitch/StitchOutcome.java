package com.edupulse.retrievalpost.stitch;

import java.util.List;

/**
 * Result of the neighbor stitching stage, including token budget accounting and fallback status.
 */
public record StitchOutcome(
		List<StitchedBlock> blocks,
		int inputChunkCount,
		int outputBlockCount,
		int totalEstimatedTokens,
		int tokenBudget,
		boolean budgetTruncated,
		boolean fallbackUsed,
		String fallbackReason,
		long stitchLatencyMs) {

	public StitchOutcome {
		blocks = blocks == null ? List.of() : List.copyOf(blocks);
	}
}

package com.edupulse.retrievalpost.rerank;

import com.edupulse.retrievalpost.model.RetrievedChunk;
import java.util.List;

/**
 * Result of the retrieve-and-rerank stage, including latency telemetry and fallback status.
 */
public record RerankOutcome(
		List<RetrievedChunk> chunks,
		int retrievedCount,
		int returnedCount,
		boolean reranked,
		boolean fallbackUsed,
		String fallbackReason,
		String model,
		long retrievalLatencyMs,
		long rerankLatencyMs) {
}

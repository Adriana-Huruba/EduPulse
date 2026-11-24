package com.edupulse.retrievalpost.web;

import com.edupulse.retrievalpost.rerank.RerankOutcome;
import com.edupulse.retrievalpost.stitch.StitchOutcome;

/**
 * Combined response for {@code POST /api/v1/retrieval/retrieve-and-stitch}.
 */
public record RetrieveAndStitchResponse(
		RerankOutcome rerank,
		StitchOutcome stitch) {
}

package com.edupulse.retrievalpost.web;

import com.edupulse.retrievalpost.grounding.GroundedAnswerResponse;
import com.edupulse.retrievalpost.rerank.RerankOutcome;
import com.edupulse.retrievalpost.stitch.StitchOutcome;

/**
 * Full end-to-end Story 4 pipeline response combining rerank, stitch, and grounding stages.
 */
public record FullAnswerResponse(
		RerankOutcome rerank,
		StitchOutcome stitch,
		GroundedAnswerResponse grounding) {
}

package com.edupulse.retrievalpost.grounding;

import java.util.List;

/**
 * Result of the grounding and answer-generation stage (Task 3).
 */
public record GroundedAnswerResponse(
		String query,
		String answer,
		boolean refused,
		String refusalReason,
		Double maxScore,
		double similarityThreshold,
		List<Citation> citations,
		String formattedContext,
		String systemPrompt,
		String userPrompt,
		long groundingLatencyMs) {

	public GroundedAnswerResponse {
		citations = citations == null ? List.of() : List.copyOf(citations);
	}
}

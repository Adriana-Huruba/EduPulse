package com.edupulse.retrievalpost.grounding;

import java.util.List;

/**
 * Assembled grounded prompt consisting of strict system instructions, citation-headered
 * context blocks, and the user question.
 */
public record GroundedPrompt(
		String systemPrompt,
		String userPrompt,
		String formattedContext,
		List<Citation> citations) {

	public GroundedPrompt {
		citations = citations == null ? List.of() : List.copyOf(citations);
	}
}

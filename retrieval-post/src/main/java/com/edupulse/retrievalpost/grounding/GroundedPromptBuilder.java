package com.edupulse.retrievalpost.grounding;

import com.edupulse.retrievalpost.config.GroundingProperties;
import com.edupulse.retrievalpost.stitch.StitchedBlock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the grounded system and user prompts instructing the LLM to answer strictly
 * from the provided citation-headered context blocks and to refuse when context is insufficient.
 */
@Component
public class GroundedPromptBuilder {

	private final CitationHeaderFormatter formatter;
	private final GroundingProperties properties;

	public GroundedPromptBuilder(CitationHeaderFormatter formatter, GroundingProperties properties) {
		this.formatter = formatter;
		this.properties = properties;
	}

	public GroundedPrompt build(String query, List<StitchedBlock> blocks) {
		CitationHeaderFormatter.FormattedContext formatted = formatter.format(blocks);
		String refusal = properties.getRefusalMessage();

		String systemPrompt = """
				You are the EduPulse Compliance and Audit Assistant.
				Follow these grounding rules strictly:
				1. Answer ONLY using facts explicitly stated in the provided context blocks below.
				2. Do NOT use outside knowledge, assumptions, or unverified claims.
				3. Cite the supporting source inline for every factual statement using [SOURCE N] (including source, version, and page from the block header).
				4. If the provided context does not contain enough information to answer the question, reply with exactly: "%s"
				""".formatted(refusal).strip();

		String userPrompt = """
				Context:
				%s

				Question: %s

				Answer (grounded strictly in the context above, with [SOURCE N] citations):
				""".formatted(formatted.text(), query == null ? "" : query.strip()).strip();

		return new GroundedPrompt(systemPrompt, userPrompt, formatted.text(), formatted.citations());
	}
}

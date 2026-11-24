package com.edupulse.retrievalpost.grounding;

/**
 * Generates an answer from a grounded system and user prompt.
 */
public interface AnswerGenerator {

	/**
	 * Calls the underlying LLM with the grounded prompt and returns the generated text.
	 *
	 * @throws GroundingException if the LLM is unavailable or fails
	 */
	String generate(String systemPrompt, String userPrompt);
}

package com.edupulse.retrievalpost.eval;

import com.edupulse.retrievalpost.grounding.AnswerGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic extractive answer generator used during offline/CI evaluation runs
 * when no live Ollama LLM is attached. Extracts the primary evidence passage from
 * {@code [SOURCE 1]} and cites it inline as {@code [SOURCE 1]}.
 */
public class ExtractiveCitedAnswerGenerator implements AnswerGenerator {

	private static final Pattern FIRST_SOURCE_BLOCK = Pattern.compile(
			"\\[SOURCE 1 \\|[^\\]]+\\]\\R+(.+?)(?:\\R\\R\\[SOURCE 2|\\R\\RQuestion:|$)",
			Pattern.DOTALL);

	@Override
	public String generate(String systemPrompt, String userPrompt) {
		if (userPrompt == null || userPrompt.isBlank()) {
			return "I don't know.";
		}
		Matcher matcher = FIRST_SOURCE_BLOCK.matcher(userPrompt);
		if (!matcher.find()) {
			return "I don't know.";
		}
		String evidence = matcher.group(1).strip();
		if (evidence.isEmpty()) {
			return "I don't know.";
		}
		return evidence + " [SOURCE 1]";
	}
}

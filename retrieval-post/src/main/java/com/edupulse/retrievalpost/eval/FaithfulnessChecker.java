package com.edupulse.retrievalpost.eval;

import com.edupulse.retrievalpost.grounding.Citation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic faithfulness verifier that checks whether a generated answer:
 * <ol>
 *   <li>Properly refuses unanswerable queries without fabricating citations, or</li>
 *   <li>Uses valid inline {@code [SOURCE N]} citations and grounds its factual claims in the
 *       provided context blocks without hallucinated terms or out-of-range citation indices.</li>
 * </ol>
 */
@Component
public class FaithfulnessChecker {

	private static final Pattern SOURCE_TAG = Pattern.compile("\\[SOURCE\\s+(\\d+)\\]");
	private static final Set<String> STOP_WORDS = Set.of(
			"the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
			"have", "has", "had", "not", "but", "can", "will", "must", "may", "all",
			"any", "per", "under", "within", "into", "their", "they", "them", "its",
			"such", "only", "when", "where", "which", "who", "what", "how", "more",
			"than", "also", "been", "being", "other", "about", "after", "before",
			"according", "source", "sources", "context", "provided", "states");

	public record FaithfulnessResult(
			boolean faithful,
			double score,
			boolean citationValid,
			double lexicalSupportRatio,
			String reason) {
	}

	public FaithfulnessResult evaluate(
			String answer,
			String formattedContext,
			List<Citation> citations,
			boolean refused,
			String refusalMessage,
			double minSupportRatio) {

		if (refused) {
			boolean exactRefusal = answer != null && answer.strip().equalsIgnoreCase(refusalMessage.strip());
			boolean noCitations = citations == null || citations.isEmpty();
			if (exactRefusal && noCitations) {
				return new FaithfulnessResult(true, 1.0, true, 1.0, "Proper refusal below similarity threshold");
			}
			return new FaithfulnessResult(false, 0.0, false, 0.0,
					"Refused response did not match expected refusal message or had unexpected citations");
		}

		if (answer == null || answer.isBlank()) {
			return new FaithfulnessResult(false, 0.0, false, 0.0, "Answer is blank");
		}
		if (formattedContext == null || formattedContext.isBlank() || citations == null || citations.isEmpty()) {
			return new FaithfulnessResult(false, 0.0, false, 0.0, "Answer provided without supporting context/citations");
		}

		// 1. Validate inline [SOURCE N] citations.
		List<Integer> citedIndices = extractCitedIndices(answer);
		if (citedIndices.isEmpty()) {
			return new FaithfulnessResult(false, 0.0, false, 0.0, "Answer is missing inline [SOURCE N] citation");
		}
		int maxValidIndex = citations.size();
		for (int idx : citedIndices) {
			if (idx < 1 || idx > maxValidIndex) {
				return new FaithfulnessResult(false, 0.0, false, 0.0,
						"Hallucinated citation index [SOURCE " + idx + "] (valid range: 1.." + maxValidIndex + ")");
			}
		}

		// 2. Measure lexical grounding of non-stopword answer claims against the provided context.
		String answerWithoutTags = SOURCE_TAG.matcher(answer).replaceAll(" ");
		Set<String> answerTokens = extractContentTokens(answerWithoutTags);
		Set<String> contextTokens = extractContentTokens(formattedContext);

		if (answerTokens.isEmpty()) {
			return new FaithfulnessResult(false, 0.0, true, 0.0, "Answer contains no verifiable content tokens");
		}

		long supported = answerTokens.stream().filter(contextTokens::contains).count();
		double supportRatio = (double) supported / answerTokens.size();
		boolean passed = supportRatio >= minSupportRatio;
		String reason = passed
				? "Grounded with valid citations (support=%.2f)".formatted(supportRatio)
				: "Unsupported claims detected (support=%.2f < %.2f)".formatted(supportRatio, minSupportRatio);

		return new FaithfulnessResult(passed, supportRatio, true, supportRatio, reason);
	}

	private static List<Integer> extractCitedIndices(String answer) {
		List<Integer> indices = new ArrayList<>();
		Matcher matcher = SOURCE_TAG.matcher(answer);
		while (matcher.find()) {
			indices.add(Integer.parseInt(matcher.group(1)));
		}
		return indices;
	}

	private static Set<String> extractContentTokens(String text) {
		Set<String> tokens = new HashSet<>();
		for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (raw.isEmpty()) {
				continue;
			}
			boolean isNumeric = Character.isDigit(raw.charAt(0));
			if (isNumeric || (raw.length() >= 3 && !STOP_WORDS.contains(raw))) {
				tokens.add(raw);
			}
		}
		return tokens;
	}
}

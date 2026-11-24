package com.edupulse.retrievalpost.stitch;

import org.springframework.stereotype.Component;

/**
 * Fast, deterministic token budget estimator for stitched context blocks.
 *
 * <p>Uses a conservative upper bound {@code max(ceil(chars / 4.0), ceil(words * 1.3))} so
 * downstream LLM prompt budgets are never silently exceeded.
 */
@Component
public class TokenEstimator {

	public int estimateTokens(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		String trimmed = text.strip();
		int byChars = (int) Math.ceil(trimmed.length() / 4.0);
		int words = countWords(trimmed);
		int byWords = (int) Math.ceil(words * 1.3);
		return Math.max(1, Math.max(byChars, byWords));
	}

	/**
	 * Truncates {@code text} cleanly on a whitespace boundary when possible so that
	 * {@code estimateTokens(result) <= maxTokens}.
	 */
	public String truncateToBudget(String text, int maxTokens) {
		if (text == null || text.isBlank() || maxTokens <= 0) {
			return "";
		}
		String trimmed = text.strip();
		if (estimateTokens(trimmed) <= maxTokens) {
			return trimmed;
		}

		// Binary search the longest prefix fitting within maxTokens.
		int lo = 0;
		int hi = trimmed.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (estimateTokens(trimmed.substring(0, mid)) <= maxTokens) {
				lo = mid;
			}
			else {
				hi = mid - 1;
			}
		}
		if (lo == 0) {
			return "";
		}

		String candidate = trimmed.substring(0, lo);
		// Prefer breaking on whitespace if we don't lose more than 25% of the prefix.
		int lastSpace = candidate.lastIndexOf(' ');
		if (lastSpace > (lo * 3) / 4) {
			candidate = candidate.substring(0, lastSpace);
		}
		return candidate.stripTrailing();
	}

	private static int countWords(String text) {
		int count = 0;
		boolean inWord = false;
		for (int i = 0; i < text.length(); i++) {
			if (Character.isWhitespace(text.charAt(i))) {
				inWord = false;
			}
			else if (!inWord) {
				count++;
				inWord = true;
			}
		}
		return count;
	}
}

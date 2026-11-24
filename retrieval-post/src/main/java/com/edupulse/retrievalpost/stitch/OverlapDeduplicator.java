package com.edupulse.retrievalpost.stitch;

import java.util.List;

/**
 * Merges consecutive chunk texts in {@code chunk_index} order while removing overlapping
 * suffix/prefix text produced by sliding-window chunkers.
 */
public class OverlapDeduplicator {

	private final int minOverlapChars;
	private final int maxOverlapChars;

	public OverlapDeduplicator(int minOverlapChars, int maxOverlapChars) {
		if (minOverlapChars < 1) {
			throw new IllegalArgumentException("minOverlapChars must be >= 1");
		}
		if (maxOverlapChars < minOverlapChars) {
			throw new IllegalArgumentException("maxOverlapChars must be >= minOverlapChars");
		}
		this.minOverlapChars = minOverlapChars;
		this.maxOverlapChars = maxOverlapChars;
	}

	/**
	 * Merges an ordered list of consecutive chunk texts with overlap deduplication.
	 */
	public String mergeAll(List<String> orderedTexts) {
		if (orderedTexts == null || orderedTexts.isEmpty()) {
			return "";
		}
		String merged = "";
		for (String next : orderedTexts) {
			if (next == null || next.isBlank()) {
				continue;
			}
			merged = merged.isEmpty() ? next.strip() : mergePair(merged, next.strip());
		}
		return merged;
	}

	/**
	 * Merges {@code left} followed by {@code right}, stripping the longest matching overlap
	 * between the suffix of {@code left} and the prefix of {@code right}.
	 */
	public String mergePair(String left, String right) {
		if (left == null || left.isEmpty()) {
			return right == null ? "" : right;
		}
		if (right == null || right.isEmpty()) {
			return left;
		}
		if (left.contains(right)) {
			return left;
		}

		int exactOverlap = findExactOverlapLength(left, right);
		if (exactOverlap >= minOverlapChars) {
			String remainder = right.substring(exactOverlap);
			return appendCleanly(left, remainder, true);
		}

		int normalizedRightCut = findWhitespaceNormalizedOverlapCut(left, right);
		if (normalizedRightCut > 0) {
			String remainder = right.substring(normalizedRightCut);
			return appendCleanly(left, remainder, true);
		}

		return appendCleanly(left, right, false);
	}

	private int findExactOverlapLength(String left, String right) {
		int maxCheck = Math.min(maxOverlapChars, Math.min(left.length(), right.length()));
		for (int len = maxCheck; len >= minOverlapChars; len--) {
			if (left.regionMatches(left.length() - len, right, 0, len)) {
				return len;
			}
		}
		return 0;
	}

	/**
	 * Handles overlaps where line breaks or multiple spaces differ between chunks
	 * (e.g., {@code "\n"} vs {@code " "}), returning the index in {@code right} after the overlap.
	 */
	private int findWhitespaceNormalizedOverlapCut(String left, String right) {
		int leftStart = Math.max(0, left.length() - maxOverlapChars);
		String leftTail = left.substring(leftStart);
		String rightHead = right.substring(0, Math.min(right.length(), maxOverlapChars));

		NormalizedText normLeft = normalizeWhitespace(leftTail);
		NormalizedText normRight = normalizeWhitespace(rightHead);

		int maxCheck = Math.min(normLeft.text().length(), normRight.text().length());
		for (int len = maxCheck; len >= minOverlapChars; len--) {
			if (normLeft.text().regionMatches(normLeft.text().length() - len, normRight.text(), 0, len)) {
				return normRight.originalEndOffset(len);
			}
		}
		return 0;
	}

	private static String appendCleanly(String left, String remainder, boolean hadOverlap) {
		if (remainder.isEmpty()) {
			return left;
		}
		if (hadOverlap) {
			return left + remainder;
		}
		boolean leftEndsSpace = Character.isWhitespace(left.charAt(left.length() - 1));
		boolean rightStartsSpace = Character.isWhitespace(remainder.charAt(0));
		if (leftEndsSpace || rightStartsSpace) {
			return left + remainder;
		}
		return left + "\n" + remainder;
	}

	private static NormalizedText normalizeWhitespace(String input) {
		StringBuilder sb = new StringBuilder(input.length());
		int[] endOffsets = new int[input.length()];
		boolean lastWasSpace = false;
		int outIdx = 0;
		for (int i = 0; i < input.length(); i++) {
			char ch = input.charAt(i);
			if (Character.isWhitespace(ch)) {
				if (!lastWasSpace && outIdx > 0) {
					sb.append(' ');
					endOffsets[outIdx++] = i + 1;
					lastWasSpace = true;
				}
				else if (lastWasSpace && outIdx > 0) {
					endOffsets[outIdx - 1] = i + 1;
				}
			}
			else {
				sb.append(ch);
				endOffsets[outIdx++] = i + 1;
				lastWasSpace = false;
			}
		}
		return new NormalizedText(sb.toString(), endOffsets);
	}

	private record NormalizedText(String text, int[] endOffsets) {
		int originalEndOffset(int normalizedLength) {
			if (normalizedLength <= 0 || normalizedLength > endOffsets.length) {
				return 0;
			}
			return endOffsets[normalizedLength - 1];
		}
	}
}

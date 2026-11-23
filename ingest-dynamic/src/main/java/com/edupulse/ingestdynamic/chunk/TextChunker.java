package com.edupulse.ingestdynamic.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into chunks of roughly {@code chunkSize} characters. Paragraphs are kept
 * together where they fit, over-long paragraphs are split at whitespace, and each chunk
 * starts with up to {@code overlap} characters from the end of the previous one.
 */
public final class TextChunker {

	private static final String SEPARATOR = "\n\n";

	private final int chunkSize;

	private final int overlap;

	/** Longest single piece, leaving room for the overlap and the separator. */
	private final int pieceMax;

	public TextChunker(int chunkSize, int overlap) {
		if (chunkSize < 1 || overlap < 0 || overlap >= chunkSize) {
			throw new IllegalArgumentException(
					"chunkSize must be positive and overlap must be in [0, chunkSize): " + chunkSize + "/" + overlap);
		}
		this.chunkSize = chunkSize;
		this.overlap = overlap;
		this.pieceMax = Math.max(1, chunkSize - overlap - SEPARATOR.length());
	}

	public List<String> chunk(String text) {
		List<String> pieces = new ArrayList<>();
		for (String paragraph : text.split("\\n\\s*\\n")) {
			String stripped = paragraph.strip();
			if (!stripped.isEmpty()) {
				splitLong(stripped, pieces);
			}
		}

		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String piece : pieces) {
			if (current.length() > 0 && current.length() + SEPARATOR.length() + piece.length() > chunkSize) {
				String finished = current.toString();
				chunks.add(finished);
				current = new StringBuilder(overlapTail(finished));
			}
			if (current.length() > 0) {
				current.append(SEPARATOR);
			}
			current.append(piece);
		}
		if (current.length() > 0) {
			chunks.add(current.toString());
		}
		return chunks;
	}

	private void splitLong(String paragraph, List<String> out) {
		String rest = paragraph;
		while (rest.length() > pieceMax) {
			int cut = lastWhitespace(rest, pieceMax);
			if (cut < pieceMax / 2) {
				cut = pieceMax;
			}
			out.add(rest.substring(0, cut).strip());
			rest = rest.substring(cut).strip();
		}
		if (!rest.isEmpty()) {
			out.add(rest);
		}
	}

	/** Index of the last whitespace character at or before {@code limit}, or -1. */
	private static int lastWhitespace(String text, int limit) {
		for (int i = Math.min(limit, text.length() - 1); i > 0; i--) {
			if (Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	/** The last {@code overlap} characters of a chunk, moved forward to a word boundary. */
	private String overlapTail(String chunk) {
		if (overlap == 0) {
			return "";
		}
		if (chunk.length() <= overlap) {
			return chunk;
		}
		int start = chunk.length() - overlap;
		while (start < chunk.length() && !Character.isWhitespace(chunk.charAt(start))) {
			start++;
		}
		return start >= chunk.length() ? "" : chunk.substring(start).strip();
	}

}

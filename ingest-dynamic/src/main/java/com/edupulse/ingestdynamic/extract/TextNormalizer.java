package com.edupulse.ingestdynamic.extract;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Whitespace clean-up shared by the extractors. */
final class TextNormalizer {

	private TextNormalizer() {
	}

	/** Collapses runs of spaces, trims each line and limits blank lines to one. */
	static String normalize(String text) {
		String collapsed = text.replace('\r', '\n').replaceAll("[ \\t\\u00A0\\f]+", " ");
		String trimmedLines = Arrays.stream(collapsed.split("\n", -1))
				.map(String::strip)
				.collect(Collectors.joining("\n"));
		return trimmedLines.replaceAll("\n{3,}", "\n\n").strip();
	}

}

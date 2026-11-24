package com.edupulse.retrievalpost.grounding;

import com.edupulse.retrievalpost.stitch.StitchedBlock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Formats every stitched context block with a standardized citation header containing
 * {@code source}, {@code version}, and {@code page}, and extracts structured {@link Citation} records.
 */
@Component
public class CitationHeaderFormatter {

	private static final int SNIPPET_MAX_CHARS = 180;

	public FormattedContext format(List<StitchedBlock> blocks) {
		if (blocks == null || blocks.isEmpty()) {
			return new FormattedContext("", List.of());
		}

		StringBuilder sb = new StringBuilder();
		List<Citation> citations = new ArrayList<>(blocks.size());

		for (int i = 0; i < blocks.size(); i++) {
			StitchedBlock block = blocks.get(i);
			int index = i + 1;
			String source = safeSource(block.source());
			int version = block.version() != null ? block.version() : 1;
			String page = formatPage(block.startPage(), block.endPage());
			String header = "[SOURCE %d | source: %s | version: %d | page: %s]"
					.formatted(index, source, version, page);
			String content = block.content() == null ? "" : block.content().strip();

			if (i > 0) {
				sb.append("\n\n");
			}
			sb.append(header).append("\n").append(content);

			citations.add(new Citation(
					index,
					block.docId(),
					source,
					version,
					page,
					block.topScore(),
					header,
					createSnippet(content)));
		}

		return new FormattedContext(sb.toString(), citations);
	}

	private static String safeSource(String source) {
		return (source == null || source.isBlank()) ? "unknown" : source.strip();
	}

	private static String formatPage(Integer startPage, Integer endPage) {
		if (startPage == null && endPage == null) {
			return "unknown";
		}
		if (startPage == null) {
			return String.valueOf(endPage);
		}
		if (endPage == null || startPage.equals(endPage)) {
			return String.valueOf(startPage);
		}
		return startPage + "-" + endPage;
	}

	private static String createSnippet(String content) {
		if (content.length() <= SNIPPET_MAX_CHARS) {
			return content;
		}
		return content.substring(0, SNIPPET_MAX_CHARS - 3).stripTrailing() + "...";
	}

	public record FormattedContext(String text, List<Citation> citations) {
	}
}

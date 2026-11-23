package com.edupulse.ingestdynamic.extract;

import java.util.List;

/**
 * Clean text pulled out of a source, split into sections so each chunk can keep its page.
 *
 * @param title the document title, or an empty string if none was found
 * @param sections non-empty text sections in reading order
 */
public record ExtractedContent(String title, List<Section> sections) {

	/**
	 * @param page 1-based page number (always 1 for web pages and plain text)
	 * @param text the section text
	 */
	public record Section(int page, String text) {
	}

	public boolean isEmpty() {
		return sections.isEmpty();
	}

}

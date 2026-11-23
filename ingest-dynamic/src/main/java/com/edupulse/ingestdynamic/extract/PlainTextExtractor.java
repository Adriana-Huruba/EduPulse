package com.edupulse.ingestdynamic.extract;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Component;

/** Handles plain text and markup-light formats such as Markdown, reStructuredText and AsciiDoc. */
@Component
public class PlainTextExtractor {

	public ExtractedContent extract(byte[] body, String fallbackTitle) {
		String text = TextNormalizer.normalize(new String(body, StandardCharsets.UTF_8));
		if (text.isBlank()) {
			return new ExtractedContent(fallbackTitle, List.of());
		}
		return new ExtractedContent(fallbackTitle, List.of(new ExtractedContent.Section(1, text)));
	}

}

package com.edupulse.ingestdynamic.ingest;

import jakarta.validation.constraints.NotBlank;

/**
 * One source to ingest.
 *
 * @param url the page, file or repository URL
 * @param type optional; defaults to {@link SourceType#AUTO}
 */
public record SourceRequest(@NotBlank String url, SourceType type) {

	public SourceType effectiveType() {
		return type == null ? SourceType.AUTO : type;
	}

}

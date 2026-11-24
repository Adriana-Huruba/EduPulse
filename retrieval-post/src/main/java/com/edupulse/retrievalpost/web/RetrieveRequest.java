package com.edupulse.retrievalpost.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for {@code POST /api/v1/retrieval/retrieve}.
 */
public record RetrieveRequest(
		@NotBlank(message = "query must not be blank") String query,
		@Min(1) @Max(200) Integer topN,
		@Min(1) @Max(100) Integer topK,
		String filterExpression,
		Boolean rerank) {
}

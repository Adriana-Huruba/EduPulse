package com.edupulse.retrievalpost.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for {@code POST /api/v1/retrieval/retrieve-and-stitch}
 * (Task 1 reranking followed by Task 2 neighbor stitching).
 */
public record RetrieveAndStitchRequest(
		@NotBlank(message = "query must not be blank") String query,
		@Min(1) @Max(200) Integer topN,
		@Min(1) @Max(100) Integer topK,
		String filterExpression,
		Boolean rerank,
		@Min(0) @Max(5) Integer windowBefore,
		@Min(0) @Max(5) Integer windowAfter,
		@Min(50) @Max(32000) Integer tokenBudget) {
}

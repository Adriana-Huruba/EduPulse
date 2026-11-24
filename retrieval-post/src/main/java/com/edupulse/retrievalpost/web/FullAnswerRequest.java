package com.edupulse.retrievalpost.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for {@code POST /api/v1/retrieval/answer} — runs the full Story 4 pipeline:
 * Task 1 (retrieve top-N + cross-encoder rerank to top-k),
 * Task 2 (neighbor stitching under token budget), and
 * Task 3 (citation headers + similarity-threshold refusal + grounded answer).
 */
public record FullAnswerRequest(
		@NotBlank(message = "query must not be blank") String query,
		@Min(1) @Max(200) Integer topN,
		@Min(1) @Max(100) Integer topK,
		String filterExpression,
		Boolean rerank,
		@Min(0) @Max(5) Integer windowBefore,
		@Min(0) @Max(5) Integer windowAfter,
		@Min(50) @Max(32000) Integer tokenBudget,
		@DecimalMin("0.0") @DecimalMax("1.0") Double similarityThreshold,
		Boolean generate) {
}

package com.edupulse.retrievalpost.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/**
 * Request payload for {@code POST /api/v1/retrieval/rerank} to rerank pre-retrieved candidates.
 */
public record RerankOnlyRequest(
		@NotBlank(message = "query must not be blank") String query,
		@NotEmpty(message = "candidates must not be empty") @Valid List<CandidateInput> candidates,
		@Min(1) @Max(100) Integer topK,
		Boolean rerank) {

	public record CandidateInput(
			String id,
			@NotBlank(message = "candidate content must not be blank") String content,
			String docId,
			Integer version,
			String source,
			Integer page,
			Integer chunkIndex,
			Double vectorScore,
			Map<String, Object> metadata) {
	}
}

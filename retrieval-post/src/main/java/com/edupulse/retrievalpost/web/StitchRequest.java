package com.edupulse.retrievalpost.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request payload for {@code POST /api/v1/retrieval/stitch}.
 *
 * <p>When {@code neighborPool} is omitted, adjacent chunks are fetched from PostgreSQL
 * via {@code JdbcNeighborChunkRepository}. When supplied, neighbor lookup runs against
 * the provided pool (useful for standalone demos and testing).
 */
public record StitchRequest(
		@NotEmpty(message = "anchors must not be empty") @Valid List<RerankOnlyRequest.CandidateInput> anchors,
		@Valid List<RerankOnlyRequest.CandidateInput> neighborPool,
		@Min(0) @Max(5) Integer windowBefore,
		@Min(0) @Max(5) Integer windowAfter,
		@Min(50) @Max(32000) Integer tokenBudget) {
}

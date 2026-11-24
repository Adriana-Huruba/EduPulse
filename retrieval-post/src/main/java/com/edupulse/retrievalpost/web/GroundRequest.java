package com.edupulse.retrievalpost.web;

import com.edupulse.retrievalpost.stitch.StitchedBlock;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Request payload for {@code POST /api/v1/retrieval/ground} to format citation headers,
 * enforce the similarity threshold refusal gate, and build/run the grounded prompt.
 */
public record GroundRequest(
		@NotBlank(message = "query must not be blank") String query,
		List<StitchedBlock> blocks,
		@DecimalMin("0.0") @DecimalMax("1.0") Double similarityThreshold,
		Boolean generate) {
}

package com.edupulse.retrievalpost.stitch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

	private final TokenEstimator estimator = new TokenEstimator();

	@Test
	void returnsZeroForNullOrBlank() {
		assertThat(estimator.estimateTokens(null)).isZero();
		assertThat(estimator.estimateTokens("   \n\t ")).isZero();
	}

	@Test
	void estimatesTokensConservatively() {
		int tokens = estimator.estimateTokens("PostgreSQL data at rest must be encrypted using AES-256-GCM.");
		assertThat(tokens).isBetween(12, 25);
	}

	@Test
	void truncatesLongTextToStayWithinBudget() {
		String text = "Compliance policy requirement ".repeat(60).trim();
		int budget = 30;

		String truncated = estimator.truncateToBudget(text, budget);

		assertThat(truncated).isNotBlank();
		assertThat(estimator.estimateTokens(truncated)).isLessThanOrEqualTo(budget);
		assertThat(text).startsWith(truncated);
	}
}

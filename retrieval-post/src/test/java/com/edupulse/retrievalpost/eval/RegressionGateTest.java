package com.edupulse.retrievalpost.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupulse.retrievalpost.config.EvalProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegressionGateTest {

	private final EvalProperties props = new EvalProperties();

	@Test
	void passesWhenCurrentMatchesBaselineAndExceedsFloors_successPath() {
		EvalReport baseline = sampleReport(36, 12, 1.0, 0.9375, 1.0, 1.0);
		EvalReport current = sampleReport(36, 12, 1.0, 0.9300, 1.0, 1.0);

		RegressionGate.RegressionResult result = RegressionGate.check(current, baseline, props);

		assertThat(result.passed()).isTrue();
		assertThat(result.failures()).isEmpty();
	}

	@Test
	void failsWhenMrrRegressesBeyondAllowedDrop_failurePath() {
		EvalReport baseline = sampleReport(36, 12, 1.0, 0.9375, 1.0, 1.0);
		EvalReport degraded = sampleReport(36, 12, 1.0, 0.8800, 1.0, 1.0);

		RegressionGate.RegressionResult result = RegressionGate.check(degraded, baseline, props);

		assertThat(result.passed()).isFalse();
		assertThat(result.failures()).anyMatch(msg -> msg.contains("MRR@k regressed"));
	}

	@Test
	void failsWhenGoldenSetTooSmallOrRefusalAccuracyDrops_failurePath() {
		EvalReport invalid = sampleReport(20, 0, 0.80, 0.70, 0.75, 0.80);

		RegressionGate.RegressionResult result = RegressionGate.check(invalid, null, props);

		assertThat(result.passed()).isFalse();
		assertThat(result.failures()).hasSizeGreaterThanOrEqualTo(5);
	}

	private static EvalReport sampleReport(
			int total, int unanswerable, double recall, double mrr, double faithfulness, double refusalAcc) {
		return new EvalReport(
				"2026-11-24T00:00:00Z", "pinned-model", 20, 5, 0.5,
				total, total - unanswerable, unanswerable,
				0.9, recall, 0.8, mrr, 0.6, 0.85,
				faithfulness, faithfulness, refusalAcc, 0.0,
				150.0, 150L, 220L, List.of());
	}
}

package com.edupulse.retrievalpost.eval;

import com.edupulse.retrievalpost.config.EvalProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-merge regression gate verifying that an {@link EvalReport} satisfies both
 * absolute quality floors and does not regress beyond {@code maxMetricDrop} relative
 * to the committed baseline report.
 */
public final class RegressionGate {

	private RegressionGate() {
	}

	public record RegressionResult(boolean passed, List<String> failures) {
		public RegressionResult {
			failures = failures == null ? List.of() : List.copyOf(failures);
		}
	}

	public static RegressionResult check(EvalReport current, EvalReport baseline, EvalProperties props) {
		List<String> failures = new ArrayList<>();

		// 1. Absolute quality floors
		if (current.totalQuestions() < 30) {
			failures.add("Golden set must have at least 30 questions (found " + current.totalQuestions() + ")");
		}
		if (current.unanswerableQuestions() < 1) {
			failures.add("Golden set must include unanswerable questions");
		}
		if (current.rerankedRecallAtK() < props.getMinRecallAtK()) {
			failures.add("Reranked Recall@%d %.4f is below minimum floor %.4f"
					.formatted(current.topK(), current.rerankedRecallAtK(), props.getMinRecallAtK()));
		}
		if (current.rerankedMrrAtK() < props.getMinMrrAtK()) {
			failures.add("Reranked MRR@%d %.4f is below minimum floor %.4f"
					.formatted(current.topK(), current.rerankedMrrAtK(), props.getMinMrrAtK()));
		}
		if (current.faithfulnessRate() < props.getMinFaithfulnessRate()) {
			failures.add("Faithfulness rate %.4f is below minimum floor %.4f"
					.formatted(current.faithfulnessRate(), props.getMinFaithfulnessRate()));
		}
		if (current.refusalAccuracy() < props.getMinRefusalAccuracy()) {
			failures.add("Unanswerable refusal accuracy %.4f is below minimum floor %.4f"
					.formatted(current.refusalAccuracy(), props.getMinRefusalAccuracy()));
		}

		// 2. Relative regression checks against committed baseline
		if (baseline != null) {
			double maxDrop = props.getMaxMetricDrop();
			checkNoDrop("Recall@k", current.rerankedRecallAtK(), baseline.rerankedRecallAtK(), maxDrop, failures);
			checkNoDrop("MRR@k", current.rerankedMrrAtK(), baseline.rerankedMrrAtK(), maxDrop, failures);
			checkNoDrop("Faithfulness rate", current.faithfulnessRate(), baseline.faithfulnessRate(), maxDrop, failures);
			checkNoDrop("Refusal accuracy", current.refusalAccuracy(), baseline.refusalAccuracy(), maxDrop, failures);
		}

		return new RegressionResult(failures.isEmpty(), failures);
	}

	private static void checkNoDrop(
			String metricName, double current, double baseline, double maxDrop, List<String> failures) {
		double drop = baseline - current;
		if (drop > maxDrop + 1e-9) {
			failures.add("%s regressed from baseline %.4f to %.4f (drop %.4f > allowed %.4f)"
					.formatted(metricName, baseline, current, drop, maxDrop));
		}
	}
}

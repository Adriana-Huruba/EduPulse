package com.edupulse.retrievalpost.eval;

import java.util.List;

/**
 * Serializable evaluation report produced by {@link EvaluationHarness} and committed
 * as the regression baseline.
 */
public record EvalReport(
		String generatedAt,
		String pinnedModel,
		int topN,
		int topK,
		double similarityThreshold,
		int totalQuestions,
		int answerableQuestions,
		int unanswerableQuestions,
		double baselineRecallAtK,
		double rerankedRecallAtK,
		double baselineMrrAtK,
		double rerankedMrrAtK,
		double baselineRecallAt1,
		double rerankedRecallAt1,
		double faithfulnessRate,
		double meanFaithfulnessScore,
		double refusalAccuracy,
		double falseRefusalRate,
		double meanRerankLatencyMs,
		long p50RerankLatencyMs,
		long p95RerankLatencyMs,
		List<QuestionResult> results) {

	public EvalReport {
		results = results == null ? List.of() : List.copyOf(results);
	}

	public record QuestionResult(
			String id,
			String question,
			boolean answerable,
			List<String> expectedChunkIds,
			List<String> topKChunkIds,
			double baselineRecallAtK,
			double rerankedRecallAtK,
			double baselineMrrAtK,
			double rerankedMrrAtK,
			Double topScore,
			boolean refused,
			boolean faithful,
			double faithfulnessScore,
			String faithfulnessReason,
			long rerankLatencyMs) {
	}
}

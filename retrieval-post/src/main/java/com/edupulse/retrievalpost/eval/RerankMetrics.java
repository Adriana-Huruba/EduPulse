package com.edupulse.retrievalpost.eval;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Standard IR ranking metrics (Recall@k and MRR@k) for comparing retrieval with and without reranking.
 */
public final class RerankMetrics {

	private RerankMetrics() {
	}

	/**
	 * Returns 1.0 / rank of the first relevant chunk within the top-k results, or 0.0 if none appear.
	 */
	public static double reciprocalRank(List<String> rankedChunkIds, Set<String> relevantChunkIds, int k) {
		if (rankedChunkIds == null || relevantChunkIds == null || relevantChunkIds.isEmpty() || k <= 0) {
			return 0.0;
		}
		int limit = Math.min(k, rankedChunkIds.size());
		for (int i = 0; i < limit; i++) {
			if (relevantChunkIds.contains(rankedChunkIds.get(i))) {
				return 1.0 / (i + 1);
			}
		}
		return 0.0;
	}

	/**
	 * Fraction of relevant chunks present in the top-k results.
	 */
	public static double recallAtK(List<String> rankedChunkIds, Set<String> relevantChunkIds, int k) {
		if (rankedChunkIds == null || relevantChunkIds == null || relevantChunkIds.isEmpty() || k <= 0) {
			return 0.0;
		}
		int limit = Math.min(k, rankedChunkIds.size());
		int hits = 0;
		for (int i = 0; i < limit; i++) {
			if (relevantChunkIds.contains(rankedChunkIds.get(i))) {
				hits++;
			}
		}
		return (double) hits / relevantChunkIds.size();
	}

	public static double mean(Collection<Double> values) {
		if (values == null || values.isEmpty()) {
			return 0.0;
		}
		return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
	}

	public static long percentile(List<Long> sortedValues, double percentile) {
		if (sortedValues == null || sortedValues.isEmpty()) {
			return 0L;
		}
		int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
		index = Math.max(0, Math.min(index, sortedValues.size() - 1));
		return sortedValues.get(index);
	}
}

package com.edupulse.retrievalpost.rerank;

import com.edupulse.retrievalpost.config.RerankProperties;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import com.edupulse.retrievalpost.retrieval.CandidateRetriever;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates Story 4, Task 1: retrieves a wider top-N candidate set and reranks down to top-k
 * using a pinned cross-encoder, falling back to the original vector order on any reranker failure.
 */
@Service
public class PostRetrievalRerankService {

	private static final Logger log = LoggerFactory.getLogger(PostRetrievalRerankService.class);

	private final CandidateRetriever candidateRetriever;
	private final Reranker reranker;
	private final RerankProperties properties;

	public PostRetrievalRerankService(
			CandidateRetriever candidateRetriever,
			Reranker reranker,
			RerankProperties properties) {
		this.candidateRetriever = candidateRetriever;
		this.reranker = reranker;
		this.properties = properties;
	}

	public RerankOutcome retrieveAndRerank(
			String query,
			Integer requestedTopN,
			Integer requestedTopK,
			String filterExpression,
			Boolean rerankOverride) {

		int effectiveTopK = requestedTopK != null ? requestedTopK : properties.getTopK();
		int effectiveTopN = requestedTopN != null ? requestedTopN : properties.getTopN();
		if (effectiveTopN < effectiveTopK) {
			effectiveTopN = effectiveTopK;
		}
		boolean shouldRerank = rerankOverride != null ? rerankOverride : properties.isEnabled();

		long retrieveStart = System.nanoTime();
		List<RetrievedChunk> candidates = candidateRetriever.retrieve(query, effectiveTopN, filterExpression);
		long retrievalLatencyMs = (System.nanoTime() - retrieveStart) / 1_000_000L;

		log.info("Retrieved {} candidates (topN={}, topK={}, rerank={}) in {} ms",
				candidates.size(), effectiveTopN, effectiveTopK, shouldRerank, retrievalLatencyMs);

		return rerankCandidates(query, candidates, effectiveTopK, shouldRerank, retrievalLatencyMs);
	}

	public RerankOutcome rerankCandidates(
			String query,
			List<RetrievedChunk> candidates,
			int topK,
			boolean shouldRerank) {
		return rerankCandidates(query, candidates, topK, shouldRerank, 0L);
	}

	private RerankOutcome rerankCandidates(
			String query,
			List<RetrievedChunk> candidates,
			int topK,
			boolean shouldRerank,
			long retrievalLatencyMs) {

		if (candidates == null || candidates.isEmpty()) {
			return new RerankOutcome(
					List.of(), 0, 0, false, false, null,
					reranker.getModelDescriptor(), retrievalLatencyMs, 0L);
		}

		int limit = Math.min(Math.max(topK, 1), candidates.size());
		if (!shouldRerank) {
			List<RetrievedChunk> truncated = List.copyOf(candidates.subList(0, limit));
			log.info("Reranker disabled; returning top {} of {} candidates in original order",
					truncated.size(), candidates.size());
			return new RerankOutcome(
					truncated, candidates.size(), truncated.size(), false, false, null,
					reranker.getModelDescriptor(), retrievalLatencyMs, 0L);
		}

		long rerankStart = System.nanoTime();
		try {
			List<String> passages = candidates.stream()
					.map(c -> c.content() == null ? "" : c.content())
					.toList();
			double[] scores = reranker.score(query, passages);
			long rerankLatencyMs = (System.nanoTime() - rerankStart) / 1_000_000L;

			if (scores == null || scores.length != candidates.size()) {
				throw new RerankerException(
						"Reranker returned " + (scores == null ? "null" : scores.length)
								+ " scores for " + candidates.size() + " candidates");
			}

			List<RetrievedChunk> scored = new ArrayList<>(candidates.size());
			for (int i = 0; i < candidates.size(); i++) {
				double score = scores[i];
				if (Double.isNaN(score) || Double.isInfinite(score)) {
					throw new RerankerException("Reranker produced non-finite score at index " + i);
				}
				scored.add(candidates.get(i).withRerankScore(score));
			}

			scored.sort(Comparator
					.comparingDouble((RetrievedChunk c) -> c.rerankScore() == null ? Double.NEGATIVE_INFINITY : c.rerankScore())
					.reversed()
					.thenComparingInt(RetrievedChunk::originalRank));

			List<RetrievedChunk> topResults = List.copyOf(scored.subList(0, limit));
			double bestScore = topResults.get(0).rerankScore();
			log.info("Reranked {} candidates -> top {} in {} ms (model={}, topScore={})",
					candidates.size(), topResults.size(), rerankLatencyMs,
					reranker.getModelDescriptor(), String.format("%.4f", bestScore));

			return new RerankOutcome(
					topResults, candidates.size(), topResults.size(), true, false, null,
					reranker.getModelDescriptor(), retrievalLatencyMs, rerankLatencyMs);
		}
		catch (RuntimeException ex) {
			long rerankLatencyMs = (System.nanoTime() - rerankStart) / 1_000_000L;
			String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			log.error("Reranker failed after {} ms (model={}); falling back to original vector order: {}",
					rerankLatencyMs, reranker.getModelDescriptor(), reason, ex);

			List<RetrievedChunk> fallback = List.copyOf(candidates.subList(0, limit));
			return new RerankOutcome(
					fallback, candidates.size(), fallback.size(), false, true, reason,
					reranker.getModelDescriptor(), retrievalLatencyMs, rerankLatencyMs);
		}
	}
}

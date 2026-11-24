package com.edupulse.retrievalpost.eval;

import com.edupulse.retrievalpost.model.RetrievedChunk;
import com.edupulse.retrievalpost.rerank.PostRetrievalRerankService;
import com.edupulse.retrievalpost.rerank.RerankOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Benchmarks retrieval quality (MRR@k, Recall@k) and added reranking latency
 * on the classpath golden set, comparing coarse first-stage retrieval (no reranking)
 * against cross-encoder reranking of the wider top-N candidate pool.
 */
public class RerankBenchmark {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public record CorpusChunk(
			String id,
			String docId,
			Integer version,
			String source,
			Integer page,
			Integer chunkIndex,
			String content) {
	}

	public record GoldenQuestion(
			String id,
			String question,
			List<String> expectedChunkIds,
			String expectedSource,
			boolean answerable) {
	}

	public record BenchmarkSummary(
			int questionCount,
			int topN,
			int topK,
			String model,
			double baselineMrrAtK,
			double rerankedMrrAtK,
			double baselineRecallAtK,
			double rerankedRecallAtK,
			double baselineRecallAt1,
			double rerankedRecallAt1,
			double meanLatencyMs,
			long p50LatencyMs,
			long p95LatencyMs,
			long maxLatencyMs) {
	}

	public static List<CorpusChunk> loadCorpus(String resourcePath) throws IOException {
		try (InputStream in = RerankBenchmark.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new IOException("Classpath resource not found: " + resourcePath);
			}
			return MAPPER.readValue(in, new TypeReference<>() {
			});
		}
	}

	public static List<GoldenQuestion> loadGoldenSet(String resourcePath) throws IOException {
		try (InputStream in = RerankBenchmark.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new IOException("Classpath resource not found: " + resourcePath);
			}
			return MAPPER.readValue(in, new TypeReference<>() {
			});
		}
	}

	/**
	 * Runs the full comparison on all answerable questions in {@code goldenSet}.
	 */
	public static BenchmarkSummary run(
			List<CorpusChunk> corpus,
			List<GoldenQuestion> goldenSet,
			PostRetrievalRerankService service,
			int topN,
			int topK) {

		List<GoldenQuestion> answerable = goldenSet.stream()
				.filter(GoldenQuestion::answerable)
				.toList();

		// Warmup once so lazy model initialization does not skew steady-state latency numbers.
		if (!answerable.isEmpty()) {
			List<RetrievedChunk> warmupCandidates = firstStageLexicalRetrieve(answerable.get(0).question(), corpus, topN);
			service.rerankCandidates(answerable.get(0).question(), warmupCandidates, topK, true);
		}

		List<Double> baseMrrs = new ArrayList<>();
		List<Double> rerankMrrs = new ArrayList<>();
		List<Double> baseRecalls = new ArrayList<>();
		List<Double> rerankRecalls = new ArrayList<>();
		List<Double> baseRecall1s = new ArrayList<>();
		List<Double> rerankRecall1s = new ArrayList<>();
		List<Long> latencies = new ArrayList<>();
		String modelDescriptor = "";

		for (GoldenQuestion q : answerable) {
			List<RetrievedChunk> widerCandidates = firstStageLexicalRetrieve(q.question(), corpus, topN);
			Set<String> expected = Set.copyOf(q.expectedChunkIds());

			RerankOutcome baseline = service.rerankCandidates(q.question(), widerCandidates, topK, false);
			RerankOutcome reranked = service.rerankCandidates(q.question(), widerCandidates, topK, true);
			modelDescriptor = reranked.model();

			List<String> baseIds = baseline.chunks().stream().map(RetrievedChunk::id).toList();
			List<String> rerankIds = reranked.chunks().stream().map(RetrievedChunk::id).toList();

			baseMrrs.add(RerankMetrics.reciprocalRank(baseIds, expected, topK));
			rerankMrrs.add(RerankMetrics.reciprocalRank(rerankIds, expected, topK));
			baseRecalls.add(RerankMetrics.recallAtK(baseIds, expected, topK));
			rerankRecalls.add(RerankMetrics.recallAtK(rerankIds, expected, topK));
			baseRecall1s.add(RerankMetrics.recallAtK(baseIds, expected, 1));
			rerankRecall1s.add(RerankMetrics.recallAtK(rerankIds, expected, 1));
			latencies.add(reranked.rerankLatencyMs());
		}

		Collections.sort(latencies);
		double meanLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);

		return new BenchmarkSummary(
				answerable.size(),
				topN,
				topK,
				modelDescriptor,
				RerankMetrics.mean(baseMrrs),
				RerankMetrics.mean(rerankMrrs),
				RerankMetrics.mean(baseRecalls),
				RerankMetrics.mean(rerankRecalls),
				RerankMetrics.mean(baseRecall1s),
				RerankMetrics.mean(rerankRecall1s),
				meanLatency,
				RerankMetrics.percentile(latencies, 0.50),
				RerankMetrics.percentile(latencies, 0.95),
				latencies.isEmpty() ? 0L : latencies.get(latencies.size() - 1));
	}

	/**
	 * Deterministic coarse first-stage retriever (unweighted token overlap) that models
	 * a noisy top-N bi-encoder/keyword candidate pool where relevant chunks appear in top-N
	 * but not always at rank 1.
	 */
	public static List<RetrievedChunk> firstStageLexicalRetrieve(String query, List<CorpusChunk> corpus, int topN) {
		Set<String> queryTerms = tokenizeTerms(query);
		Map<String, Double> scores = new HashMap<>();
		for (CorpusChunk chunk : corpus) {
			Set<String> docTerms = tokenizeTerms(chunk.content());
			long overlap = queryTerms.stream().filter(docTerms::contains).count();
			double jaccard = docTerms.isEmpty() ? 0.0 : (double) overlap / Math.sqrt(docTerms.size());
			scores.put(chunk.id(), jaccard);
		}

		List<CorpusChunk> sorted = new ArrayList<>(corpus);
		sorted.sort(Comparator
				.comparingDouble((CorpusChunk c) -> scores.getOrDefault(c.id(), 0.0))
				.reversed()
				.thenComparing(CorpusChunk::id));

		int limit = Math.min(topN, sorted.size());
		List<RetrievedChunk> result = new ArrayList<>(limit);
		for (int i = 0; i < limit; i++) {
			CorpusChunk c = sorted.get(i);
			result.add(new RetrievedChunk(
					c.id(),
					c.content(),
					c.docId(),
					c.version(),
					c.source(),
					c.page(),
					c.chunkIndex(),
					scores.get(c.id()),
					null,
					i + 1,
					Map.of()));
		}
		return result;
	}

	private static Set<String> tokenizeTerms(String text) {
		if (text == null || text.isBlank()) {
			return Set.of();
		}
		Set<String> terms = new HashSet<>();
		Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
				.filter(t -> t.length() >= 2)
				.forEach(terms::add);
		return terms;
	}
}

package com.edupulse.retrievalpost.eval;

import com.edupulse.retrievalpost.config.EvalProperties;
import com.edupulse.retrievalpost.config.GroundingProperties;
import com.edupulse.retrievalpost.config.RerankProperties;
import com.edupulse.retrievalpost.config.StitchProperties;
import com.edupulse.retrievalpost.grounding.AnswerGenerator;
import com.edupulse.retrievalpost.grounding.CitationHeaderFormatter;
import com.edupulse.retrievalpost.grounding.GroundedAnswerResponse;
import com.edupulse.retrievalpost.grounding.GroundedAnswerService;
import com.edupulse.retrievalpost.grounding.GroundedPromptBuilder;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import com.edupulse.retrievalpost.rerank.PostRetrievalRerankService;
import com.edupulse.retrievalpost.rerank.RerankOutcome;
import com.edupulse.retrievalpost.stitch.NeighborChunkRepository;
import com.edupulse.retrievalpost.stitch.NeighborStitchingService;
import com.edupulse.retrievalpost.stitch.StitchOutcome;
import com.edupulse.retrievalpost.stitch.TokenEstimator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end Story 4 evaluation harness running Task 1 (reranker), Task 2 (neighbor stitching),
 * Task 3 (citation headers + threshold refusal + grounded answer), and Task 4 (faithfulness check)
 * over the golden Q&A set.
 */
public class EvaluationHarness {

	private static final Logger log = LoggerFactory.getLogger(EvaluationHarness.class);
	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.build();

	private final PostRetrievalRerankService rerankService;
	private final NeighborStitchingService stitchingService;
	private final GroundedAnswerService groundingService;
	private final FaithfulnessChecker faithfulnessChecker;
	private final RerankProperties rerankProperties;
	private final GroundingProperties groundingProperties;
	private final EvalProperties evalProperties;

	public EvaluationHarness(
			PostRetrievalRerankService rerankService,
			NeighborStitchingService stitchingService,
			GroundedAnswerService groundingService,
			FaithfulnessChecker faithfulnessChecker,
			RerankProperties rerankProperties,
			GroundingProperties groundingProperties,
			EvalProperties evalProperties) {
		this.rerankService = rerankService;
		this.stitchingService = stitchingService;
		this.groundingService = groundingService;
		this.faithfulnessChecker = faithfulnessChecker;
		this.rerankProperties = rerankProperties;
		this.groundingProperties = groundingProperties;
		this.evalProperties = evalProperties;
	}

	public static EvaluationHarness createOffline(
			PostRetrievalRerankService rerankService,
			RerankProperties rerankProps,
			StitchProperties stitchProps,
			GroundingProperties groundingProps,
			EvalProperties evalProps,
			AnswerGenerator answerGenerator) {
		NeighborStitchingService stitcher = new NeighborStitchingService(
				(docId, version, min, max) -> List.of(), new TokenEstimator(), stitchProps);
		GroundedPromptBuilder promptBuilder = new GroundedPromptBuilder(
				new CitationHeaderFormatter(), groundingProps);
		GroundedAnswerService grounding = new GroundedAnswerService(
				promptBuilder, answerGenerator, groundingProps);
		return new EvaluationHarness(
				rerankService, stitcher, grounding, new FaithfulnessChecker(),
				rerankProps, groundingProps, evalProps);
	}

	public EvalReport run() throws IOException {
		List<RerankBenchmark.CorpusChunk> corpus = RerankBenchmark.loadCorpus(evalProperties.getCorpusPath());
		List<RerankBenchmark.GoldenQuestion> goldenSet = RerankBenchmark.loadGoldenSet(evalProperties.getGoldenSetPath());
		return run(corpus, goldenSet);
	}

	public EvalReport run(
			List<RerankBenchmark.CorpusChunk> corpus,
			List<RerankBenchmark.GoldenQuestion> goldenSet) {

		int topN = rerankProperties.getTopN();
		int topK = rerankProperties.getTopK();
		double threshold = groundingProperties.getSimilarityThreshold();
		NeighborChunkRepository corpusRepo = buildCorpusNeighborRepo(corpus);

		// Warmup ONNX session once so latency reflects steady-state inference.
		if (!goldenSet.isEmpty()) {
			List<RetrievedChunk> warmup = RerankBenchmark.firstStageLexicalRetrieve(
					goldenSet.get(0).question(), corpus, topN);
			rerankService.rerankCandidates(goldenSet.get(0).question(), warmup, topK, true);
		}

		List<Double> baseRecalls = new ArrayList<>();
		List<Double> rerankRecalls = new ArrayList<>();
		List<Double> baseMrrs = new ArrayList<>();
		List<Double> rerankMrrs = new ArrayList<>();
		List<Double> baseRecall1s = new ArrayList<>();
		List<Double> rerankRecall1s = new ArrayList<>();
		List<Double> faithfulnessScores = new ArrayList<>();
		List<Long> latencies = new ArrayList<>();
		List<EvalReport.QuestionResult> questionResults = new ArrayList<>(goldenSet.size());

		int answerableCount = 0;
		int unanswerableCount = 0;
		int faithfulCount = 0;
		int correctRefusals = 0;
		int falseRefusals = 0;
		String pinnedModel = rerankProperties.pinnedDescriptor();

		for (RerankBenchmark.GoldenQuestion q : goldenSet) {
			List<RetrievedChunk> wider = RerankBenchmark.firstStageLexicalRetrieve(q.question(), corpus, topN);
			RerankOutcome baseline = rerankService.rerankCandidates(q.question(), wider, topK, false);
			RerankOutcome reranked = rerankService.rerankCandidates(q.question(), wider, topK, true);
			pinnedModel = reranked.model();
			latencies.add(reranked.rerankLatencyMs());

			List<String> baseIds = baseline.chunks().stream().map(RetrievedChunk::id).toList();
			List<String> rerankIds = reranked.chunks().stream().map(RetrievedChunk::id).toList();

			double baseRecall = 0.0;
			double rerankRecall = 0.0;
			double baseMrr = 0.0;
			double rerankMrr = 0.0;

			if (q.answerable()) {
				answerableCount++;
				Set<String> expected = Set.copyOf(q.expectedChunkIds());
				baseRecall = RerankMetrics.recallAtK(baseIds, expected, topK);
				rerankRecall = RerankMetrics.recallAtK(rerankIds, expected, topK);
				baseMrr = RerankMetrics.reciprocalRank(baseIds, expected, topK);
				rerankMrr = RerankMetrics.reciprocalRank(rerankIds, expected, topK);

				baseRecalls.add(baseRecall);
				rerankRecalls.add(rerankRecall);
				baseMrrs.add(baseMrr);
				rerankMrrs.add(rerankMrr);
				baseRecall1s.add(RerankMetrics.recallAtK(baseIds, expected, 1));
				rerankRecall1s.add(RerankMetrics.recallAtK(rerankIds, expected, 1));
			}
			else {
				unanswerableCount++;
			}

			// Task 2: Neighbor stitching
			StitchOutcome stitched = stitchingService.stitch(reranked.chunks(), null, null, null, corpusRepo);

			// Task 3: Citations, threshold gate, and grounded answer
			GroundedAnswerResponse grounded = groundingService.groundAndAnswer(
					q.question(), stitched.blocks(), threshold, true);

			if (q.answerable() && grounded.refused()) {
				falseRefusals++;
			}
			if (!q.answerable() && grounded.refused()
					&& groundingProperties.getRefusalMessage().equals(grounded.answer())) {
				correctRefusals++;
			}

			// Task 4: Faithfulness check
			FaithfulnessChecker.FaithfulnessResult faith = faithfulnessChecker.evaluate(
					grounded.answer(),
					grounded.formattedContext(),
					grounded.citations(),
					grounded.refused(),
					groundingProperties.getRefusalMessage(),
					evalProperties.getMinSupportRatio());
			if (faith.faithful()) {
				faithfulCount++;
			}
			faithfulnessScores.add(faith.score());

			questionResults.add(new EvalReport.QuestionResult(
					q.id(),
					q.question(),
					q.answerable(),
					q.expectedChunkIds(),
					rerankIds,
					baseRecall,
					rerankRecall,
					baseMrr,
					rerankMrr,
					grounded.maxScore(),
					grounded.refused(),
					faith.faithful(),
					faith.score(),
					faith.reason(),
					reranked.rerankLatencyMs()));
		}

		Collections.sort(latencies);
		double meanLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
		double faithfulnessRate = goldenSet.isEmpty() ? 0.0 : (double) faithfulCount / goldenSet.size();
		double refusalAccuracy = unanswerableCount == 0 ? 1.0 : (double) correctRefusals / unanswerableCount;
		double falseRefusalRate = answerableCount == 0 ? 0.0 : (double) falseRefusals / answerableCount;

		EvalReport report = new EvalReport(
				Instant.now().toString(),
				pinnedModel,
				topN,
				topK,
				threshold,
				goldenSet.size(),
				answerableCount,
				unanswerableCount,
				RerankMetrics.mean(baseRecalls),
				RerankMetrics.mean(rerankRecalls),
				RerankMetrics.mean(baseMrrs),
				RerankMetrics.mean(rerankMrrs),
				RerankMetrics.mean(baseRecall1s),
				RerankMetrics.mean(rerankRecall1s),
				faithfulnessRate,
				RerankMetrics.mean(faithfulnessScores),
				refusalAccuracy,
				falseRefusalRate,
				meanLatency,
				RerankMetrics.percentile(latencies, 0.50),
				RerankMetrics.percentile(latencies, 0.95),
				questionResults);

		log.info("Evaluation complete ({} questions: {} answerable, {} unanswerable): "
						+ "Recall@{}={} (base {}), MRR@{}={} (base {}), Faithfulness={}, RefusalAcc={}, p50={}ms",
				report.totalQuestions(), report.answerableQuestions(), report.unanswerableQuestions(),
				topK, fmt(report.rerankedRecallAtK()), fmt(report.baselineRecallAtK()),
				topK, fmt(report.rerankedMrrAtK()), fmt(report.baselineMrrAtK()),
				fmt(report.faithfulnessRate()), fmt(report.refusalAccuracy()), report.p50RerankLatencyMs());

		return report;
	}

	public static void writeReport(EvalReport report, Path jsonPath) throws IOException {
		if (jsonPath.getParent() != null) {
			Files.createDirectories(jsonPath.getParent());
		}
		MAPPER.writeValue(jsonPath.toFile(), report);
	}

	public static EvalReport readReport(Path jsonPath) throws IOException {
		return MAPPER.readValue(jsonPath.toFile(), EvalReport.class);
	}

	public static void writeMarkdownSummary(EvalReport report, Path mdPath) throws IOException {
		if (mdPath.getParent() != null) {
			Files.createDirectories(mdPath.getParent());
		}
		String md = """
				# Story 4 Evaluation Baseline Report

				- **Generated at:** `%s`
				- **Pinned reranker model:** `%s`
				- **Retrieval config:** `topN=%d`, `topK=%d`, `similarityThreshold=%.2f`
				- **Golden set:** `%d` total (`%d` answerable, `%d` unanswerable)

				## Summary Metrics

				| Metric | Baseline (No Rerank) | Full Pipeline (Rerank + Stitch + Ground) |
				|---|---:|---:|
				| **Recall@%d** | `%.4f` | **`%.4f`** |
				| **MRR@%d** | `%.4f` | **`%.4f`** |
				| **Recall@1** | `%.4f` | **`%.4f`** |
				| **Faithfulness Rate** | — | **`%.4f`** (mean score `%.4f`) |
				| **Unanswerable Refusal Accuracy** | — | **`%.4f`** |
				| **False Refusal Rate** | — | **`%.4f`** |
				| **Added Rerank Latency (CPU)** | `0 ms` | mean **`%.1f ms`**, p50 **`%d ms`**, p95 **`%d ms`** |
				""".formatted(
				report.generatedAt(),
				report.pinnedModel(),
				report.topN(),
				report.topK(),
				report.similarityThreshold(),
				report.totalQuestions(),
				report.answerableQuestions(),
				report.unanswerableQuestions(),
				report.topK(), report.baselineRecallAtK(), report.rerankedRecallAtK(),
				report.topK(), report.baselineMrrAtK(), report.rerankedMrrAtK(),
				report.baselineRecallAt1(), report.rerankedRecallAt1(),
				report.faithfulnessRate(), report.meanFaithfulnessScore(),
				report.refusalAccuracy(),
				report.falseRefusalRate(),
				report.meanRerankLatencyMs(), report.p50RerankLatencyMs(), report.p95RerankLatencyMs());

		Files.writeString(mdPath, md, StandardCharsets.UTF_8);
	}

	private static NeighborChunkRepository buildCorpusNeighborRepo(List<RerankBenchmark.CorpusChunk> corpus) {
		List<RetrievedChunk> chunks = corpus.stream()
				.map(c -> new RetrievedChunk(
						c.id(), c.content(), c.docId(), c.version(), c.source(),
						c.page(), c.chunkIndex(), null, null, 0, Map.of()))
				.toList();
		return (docId, version, minIdx, maxIdx) -> chunks.stream()
				.filter(c -> Objects.equals(c.docId(), docId))
				.filter(c -> version == null || Objects.equals(c.version(), version))
				.filter(c -> c.chunkIndex() != null && c.chunkIndex() >= minIdx && c.chunkIndex() <= maxIdx)
				.sorted(Comparator.comparingInt(RetrievedChunk::chunkIndex))
				.toList();
	}

	private static String fmt(double v) {
		return String.format("%.4f", v);
	}
}

package com.edupulse.retrievalpost.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupulse.retrievalpost.config.RerankProperties;
import com.edupulse.retrievalpost.eval.RerankBenchmark;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end verification of the pinned ONNX cross-encoder ({@code cross-encoder/ms-marco-MiniLM-L6-v2})
 * on the EduPulse compliance golden set, confirming measured MRR / Recall@k improvement over
 * first-stage retrieval without reranking and reporting steady-state added latency.
 */
class OnnxCrossEncoderGoldenSetBenchmarkTest {

	private static final Logger log = LoggerFactory.getLogger(OnnxCrossEncoderGoldenSetBenchmarkTest.class);

	@Test
	void pinnedCrossEncoderImprovesMrrAndRecallOnGoldenSet() throws Exception {
		RerankProperties props = new RerankProperties();
		props.setTopN(20);
		props.setTopK(5);
		props.setModelCacheDir("models/reranker");

		PinnedModelManager modelManager = new PinnedModelManager(props);
		try (OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(props, modelManager)) {
			PostRetrievalRerankService service = new PostRetrievalRerankService(
					(q, n, f) -> List.of(), reranker, props);

			List<RerankBenchmark.CorpusChunk> corpus = RerankBenchmark.loadCorpus("/eval/corpus/compliance-chunks.json");
			List<RerankBenchmark.GoldenQuestion> goldenSet = RerankBenchmark.loadGoldenSet("/eval/golden-set.json");

			RerankBenchmark.BenchmarkSummary summary = RerankBenchmark.run(corpus, goldenSet, service, 20, 5);

			log.info("=== Story 4 Task 1 Golden-Set Benchmark ({} questions, topN={}, topK={}) ===",
					summary.questionCount(), summary.topN(), summary.topK());
			log.info("Pinned model        : {}", summary.model());
			log.info("Baseline  MRR@5     : {}  |  Reranked MRR@5     : {}",
					String.format("%.4f", summary.baselineMrrAtK()),
					String.format("%.4f", summary.rerankedMrrAtK()));
			log.info("Baseline  Recall@5  : {}  |  Reranked Recall@5  : {}",
					String.format("%.4f", summary.baselineRecallAtK()),
					String.format("%.4f", summary.rerankedRecallAtK()));
			log.info("Baseline  Recall@1  : {}  |  Reranked Recall@1  : {}",
					String.format("%.4f", summary.baselineRecallAt1()),
					String.format("%.4f", summary.rerankedRecallAt1()));
			log.info("Added latency (ms)  : mean={}, p50={}, p95={}, max={}",
					String.format("%.1f", summary.meanLatencyMs()),
					summary.p50LatencyMs(),
					summary.p95LatencyMs(),
					summary.maxLatencyMs());

			assertThat(summary.questionCount()).isGreaterThanOrEqualTo(15);
			assertThat(summary.rerankedMrrAtK())
					.as("Cross-encoder reranking must improve MRR@5 over no reranking")
					.isGreaterThan(summary.baselineMrrAtK());
			assertThat(summary.rerankedRecallAtK())
					.as("Cross-encoder Recall@5 must be at least as high as baseline")
					.isGreaterThanOrEqualTo(summary.baselineRecallAtK());
			assertThat(summary.rerankedRecallAt1())
					.as("Cross-encoder Recall@1 must improve over no reranking")
					.isGreaterThan(summary.baselineRecallAt1());
		}
	}
}

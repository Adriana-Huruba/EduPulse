package com.edupulse.retrievalpost.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupulse.retrievalpost.config.GroundingProperties;
import com.edupulse.retrievalpost.config.RerankProperties;
import com.edupulse.retrievalpost.eval.RerankBenchmark;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import com.edupulse.retrievalpost.rerank.OnnxCrossEncoderReranker;
import com.edupulse.retrievalpost.rerank.PinnedModelManager;
import com.edupulse.retrievalpost.rerank.PostRetrievalRerankService;
import com.edupulse.retrievalpost.rerank.RerankOutcome;
import com.edupulse.retrievalpost.stitch.StitchedBlock;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates the similarity threshold ({@code 0.50}) using the pinned ONNX cross-encoder across
 * 15 in-scope compliance questions and 10 out-of-scope questions, justifying the threshold
 * with real score distributions and verifying 10/10 out-of-scope refusals.
 */
class GroundingThresholdEvalTest {

	private static final Logger log = LoggerFactory.getLogger(GroundingThresholdEvalTest.class);

	@Test
	void thresholdJustifiedByEvalDataAndRefusesAllTenOutOfScopeQuestions() throws Exception {
		RerankProperties rerankProps = new RerankProperties();
		rerankProps.setTopN(20);
		rerankProps.setTopK(5);
		rerankProps.setModelCacheDir("models/reranker");

		GroundingProperties groundingProps = new GroundingProperties();
		groundingProps.setSimilarityThreshold(0.5);
		groundingProps.setChatEnabled(false);

		PinnedModelManager modelManager = new PinnedModelManager(rerankProps);
		try (OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(rerankProps, modelManager)) {
			PostRetrievalRerankService rerankService = new PostRetrievalRerankService(
					(q, n, f) -> List.of(), reranker, rerankProps);
			GroundedPromptBuilder promptBuilder = new GroundedPromptBuilder(
					new CitationHeaderFormatter(), groundingProps);
			GroundedAnswerService groundingService = new GroundedAnswerService(
					promptBuilder, (sys, usr) -> "unused", groundingProps);

			List<RerankBenchmark.CorpusChunk> corpus = RerankBenchmark.loadCorpus("/eval/corpus/compliance-chunks.json");
			List<RerankBenchmark.GoldenQuestion> inScope = RerankBenchmark.loadGoldenSet("/eval/golden-set.json")
					.stream().filter(RerankBenchmark.GoldenQuestion::answerable).toList();
			List<RerankBenchmark.GoldenQuestion> outOfScope = RerankBenchmark.loadGoldenSet("/eval/out-of-scope.json");

			assertThat(outOfScope).hasSize(10);

			List<Double> inScopeTopScores = new ArrayList<>();
			List<Double> outOfScopeTopScores = new ArrayList<>();

			for (RerankBenchmark.GoldenQuestion q : inScope) {
				List<StitchedBlock> blocks = retrieveAndConvert(q.question(), corpus, rerankService);
				GroundedAnswerResponse resp = groundingService.groundAndAnswer(q.question(), blocks, 0.5, false);
				inScopeTopScores.add(resp.maxScore());

				assertThat(resp.refused())
						.as("In-scope question %s should be accepted (maxScore=%s)", q.id(), resp.maxScore())
						.isFalse();
				assertThat(resp.citations()).isNotEmpty();
				assertThat(resp.formattedContext()).contains("source:").contains("version:").contains("page:");
			}

			int refusedCount = 0;
			for (RerankBenchmark.GoldenQuestion q : outOfScope) {
				List<StitchedBlock> blocks = retrieveAndConvert(q.question(), corpus, rerankService);
				GroundedAnswerResponse resp = groundingService.groundAndAnswer(q.question(), blocks, 0.5, false);
				outOfScopeTopScores.add(resp.maxScore() == null ? 0.0 : resp.maxScore());

				assertThat(resp.refused())
						.as("Out-of-scope question %s must be refused (maxScore=%s)", q.id(), resp.maxScore())
						.isTrue();
				assertThat(resp.answer()).isEqualTo("I don't know.");
				assertThat(resp.citations()).isEmpty();
				refusedCount++;
			}

			DoubleSummaryStatistics inStats = inScopeTopScores.stream().mapToDouble(Double::doubleValue).summaryStatistics();
			DoubleSummaryStatistics oosStats = outOfScopeTopScores.stream().mapToDouble(Double::doubleValue).summaryStatistics();

			log.info("=== Story 4 Task 3 Threshold Justification Eval (15 in-scope, 10 out-of-scope) ===");
			log.info("In-scope     top-1 scores : min={}, mean={}, max={}",
					String.format("%.4f", inStats.getMin()),
					String.format("%.4f", inStats.getAverage()),
					String.format("%.4f", inStats.getMax()));
			log.info("Out-of-scope top-1 scores : min={}, mean={}, max={}",
					String.format("%.4f", oosStats.getMin()),
					String.format("%.4f", oosStats.getAverage()),
					String.format("%.4f", oosStats.getMax()));
			log.info("Separation margin         : {} (inScopeMin={} vs outOfScopeMax={})",
					String.format("%.4f", inStats.getMin() - oosStats.getMax()),
					String.format("%.4f", inStats.getMin()),
					String.format("%.4f", oosStats.getMax()));
			log.info("At threshold=0.50         : in-scope accepted={}/15, out-of-scope refused={}/10",
					inScope.size(), refusedCount);

			assertThat(refusedCount).isEqualTo(10);
			assertThat(inStats.getMin()).isGreaterThan(0.5);
			assertThat(oosStats.getMax()).isLessThan(0.5);
		}
	}

	private static List<StitchedBlock> retrieveAndConvert(
			String query, List<RerankBenchmark.CorpusChunk> corpus, PostRetrievalRerankService rerankService) {
		List<RetrievedChunk> candidates = RerankBenchmark.firstStageLexicalRetrieve(query, corpus, 20);
		RerankOutcome reranked = rerankService.rerankCandidates(query, candidates, 5, true);
		return reranked.chunks().stream()
				.map(c -> new StitchedBlock(
						c.docId(), c.version(), c.source(), c.page(), c.page(),
						c.chunkIndex(), c.chunkIndex(),
						c.chunkIndex() == null ? List.of() : List.of(c.chunkIndex()),
						c.content(), 40, c.rerankScore()))
				.toList();
	}
}

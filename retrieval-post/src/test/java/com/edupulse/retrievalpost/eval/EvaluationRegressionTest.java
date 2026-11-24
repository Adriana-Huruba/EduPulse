package com.edupulse.retrievalpost.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupulse.retrievalpost.config.EvalProperties;
import com.edupulse.retrievalpost.config.GroundingProperties;
import com.edupulse.retrievalpost.config.RerankProperties;
import com.edupulse.retrievalpost.config.StitchProperties;
import com.edupulse.retrievalpost.rerank.OnnxCrossEncoderReranker;
import com.edupulse.retrievalpost.rerank.PinnedModelManager;
import com.edupulse.retrievalpost.rerank.PostRetrievalRerankService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pre-merge regression test for Story 4, Task 4.
 *
 * <p>Runs the full 36-question evaluation harness (Task 1 reranker -> Task 2 neighbor stitcher ->
 * Task 3 citation headers & threshold refusal -> Task 4 faithfulness checker) and compares
 * Recall@k, MRR@k, faithfulness, and unanswerable refusal accuracy against the committed
 * baseline report at {@code eval/reports/baseline-report.json}.
 */
class EvaluationRegressionTest {

	@Test
	void fullPipelinePassesRegressionGateAgainstCommittedBaseline() throws Exception {
		RerankProperties rerankProps = new RerankProperties();
		StitchProperties stitchProps = new StitchProperties();
		GroundingProperties groundingProps = new GroundingProperties();
		EvalProperties evalProps = new EvalProperties();

		Path baselinePath = Path.of(evalProps.getBaselineReportPath());
		assertThat(Files.exists(baselinePath))
				.as("Committed baseline report must exist at %s", baselinePath.toAbsolutePath())
				.isTrue();

		EvalReport baseline = EvaluationHarness.readReport(baselinePath);
		PinnedModelManager modelManager = new PinnedModelManager(rerankProps);

		try (OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(rerankProps, modelManager)) {
			PostRetrievalRerankService rerankService = new PostRetrievalRerankService(
					(q, n, f) -> List.of(), reranker, rerankProps);

			EvaluationHarness harness = EvaluationHarness.createOffline(
					rerankService, rerankProps, stitchProps, groundingProps, evalProps,
					new ExtractiveCitedAnswerGenerator());

			EvalReport current = harness.run();
			RegressionGate.RegressionResult gate = RegressionGate.check(current, baseline, evalProps);

			assertThat(current.totalQuestions()).isGreaterThanOrEqualTo(30);
			assertThat(current.unanswerableQuestions()).isGreaterThanOrEqualTo(10);
			assertThat(gate.passed())
					.as("Regression gate failures: %s", gate.failures())
					.isTrue();
		}
	}
}

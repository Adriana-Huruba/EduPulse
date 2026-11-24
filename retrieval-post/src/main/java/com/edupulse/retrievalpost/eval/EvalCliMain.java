package com.edupulse.retrievalpost.eval;

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

/**
 * Standalone CLI entry point for running the Story 4 evaluation harness and pre-merge
 * regression gate with a single command.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code --write-baseline <jsonPath>} — runs evaluation and writes the JSON and Markdown baseline reports.</li>
 *   <li>{@code --baseline <jsonPath>} — runs evaluation and fails (exit code 1) on regression vs. baseline.</li>
 * </ul>
 */
public final class EvalCliMain {

	private EvalCliMain() {
	}

	public static void main(String[] args) throws Exception {
		boolean writeBaseline = false;
		Path baselinePath = Path.of("eval/reports/baseline-report.json");

		for (int i = 0; i < args.length; i++) {
			if ("--write-baseline".equals(args[i])) {
				writeBaseline = true;
				if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
					baselinePath = Path.of(args[++i]);
				}
			}
			else if ("--baseline".equals(args[i]) && i + 1 < args.length) {
				baselinePath = Path.of(args[++i]);
			}
		}

		RerankProperties rerankProps = new RerankProperties();
		StitchProperties stitchProps = new StitchProperties();
		GroundingProperties groundingProps = new GroundingProperties();
		EvalProperties evalProps = new EvalProperties();

		PinnedModelManager modelManager = new PinnedModelManager(rerankProps);
		try (OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(rerankProps, modelManager)) {
			PostRetrievalRerankService rerankService = new PostRetrievalRerankService(
					(q, n, f) -> List.of(), reranker, rerankProps);

			EvaluationHarness harness = EvaluationHarness.createOffline(
					rerankService, rerankProps, stitchProps, groundingProps, evalProps,
					new ExtractiveCitedAnswerGenerator());

			EvalReport report = harness.run();
			printSummary(report);

			if (writeBaseline) {
				EvaluationHarness.writeReport(report, baselinePath);
				Path mdPath = baselinePath.resolveSibling("baseline-report.md");
				EvaluationHarness.writeMarkdownSummary(report, mdPath);
				System.out.println("Wrote baseline report to: " + baselinePath.toAbsolutePath());
				System.out.println("Wrote markdown summary to: " + mdPath.toAbsolutePath());
			}

			EvalReport baseline = Files.exists(baselinePath) ? EvaluationHarness.readReport(baselinePath) : null;
			RegressionGate.RegressionResult gate = RegressionGate.check(report, baseline, evalProps);

			if (!gate.passed()) {
				System.err.println("REGRESSION GATE FAILED:");
				for (String failure : gate.failures()) {
					System.err.println("  - " + failure);
				}
				System.exit(1);
			}
			System.out.println("REGRESSION GATE PASSED.");
		}
	}

	private static void printSummary(EvalReport r) {
		System.out.println("============================================================");
		System.out.printf("Story 4 Eval (%d questions: %d answerable, %d unanswerable)%n",
				r.totalQuestions(), r.answerableQuestions(), r.unanswerableQuestions());
		System.out.printf("Pinned model        : %s%n", r.pinnedModel());
		System.out.printf("Recall@%d            : %.4f (baseline no-rerank: %.4f)%n",
				r.topK(), r.rerankedRecallAtK(), r.baselineRecallAtK());
		System.out.printf("MRR@%d               : %.4f (baseline no-rerank: %.4f)%n",
				r.topK(), r.rerankedMrrAtK(), r.baselineMrrAtK());
		System.out.printf("Recall@1            : %.4f (baseline no-rerank: %.4f)%n",
				r.rerankedRecallAt1(), r.baselineRecallAt1());
		System.out.printf("Faithfulness rate   : %.4f (mean score: %.4f)%n",
				r.faithfulnessRate(), r.meanFaithfulnessScore());
		System.out.printf("Refusal accuracy    : %.4f (false refusal rate: %.4f)%n",
				r.refusalAccuracy(), r.falseRefusalRate());
		System.out.printf("Rerank latency (ms) : mean=%.1f, p50=%d, p95=%d%n",
				r.meanRerankLatencyMs(), r.p50RerankLatencyMs(), r.p95RerankLatencyMs());
		System.out.println("============================================================");
	}
}

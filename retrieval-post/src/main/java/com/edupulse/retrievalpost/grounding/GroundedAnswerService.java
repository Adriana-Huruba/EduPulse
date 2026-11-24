package com.edupulse.retrievalpost.grounding;

import com.edupulse.retrievalpost.config.GroundingProperties;
import com.edupulse.retrievalpost.stitch.StitchedBlock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates Story 4, Task 3: enforces the similarity-threshold refusal gate
 * ({@code "I don't know."}), formats citation headers ({@code source}, {@code version}, {@code page}),
 * builds the grounded prompt, and optionally invokes the LLM to generate a cited answer.
 */
@Service
public class GroundedAnswerService {

	private static final Logger log = LoggerFactory.getLogger(GroundedAnswerService.class);

	private final GroundedPromptBuilder promptBuilder;
	private final AnswerGenerator answerGenerator;
	private final GroundingProperties properties;

	public GroundedAnswerService(
			GroundedPromptBuilder promptBuilder,
			AnswerGenerator answerGenerator,
			GroundingProperties properties) {
		this.promptBuilder = promptBuilder;
		this.answerGenerator = answerGenerator;
		this.properties = properties;
	}

	public GroundedAnswerResponse groundAndAnswer(
			String query,
			List<StitchedBlock> blocks,
			Double thresholdOverride,
			Boolean generateOverride) {

		long startNanos = System.nanoTime();
		double threshold = thresholdOverride != null ? thresholdOverride : properties.getSimilarityThreshold();
		boolean shouldGenerate = generateOverride != null ? generateOverride : properties.isChatEnabled();

		if (blocks == null || blocks.isEmpty()) {
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
			log.info("Refusing query (no context blocks retrieved; threshold={})", threshold);
			return refusedResponse(query, null, threshold, "No context blocks retrieved", elapsedMs);
		}

		Double maxScore = blocks.stream()
				.filter(Objects::nonNull)
				.map(StitchedBlock::topScore)
				.filter(Objects::nonNull)
				.max(Double::compareTo)
				.orElse(null);

		if (maxScore == null || maxScore < threshold) {
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
			String reason = maxScore == null
					? "Context blocks carry no relevance score to satisfy similarity threshold"
					: "Top relevance score (%.4f) is below similarity threshold (%.4f)".formatted(maxScore, threshold);
			log.info("Refusing query: {} (blocks={})", reason, blocks.size());
			return refusedResponse(query, maxScore, threshold, reason, elapsedMs);
		}

		// Only include blocks that meet the similarity threshold in the grounded context.
		List<StitchedBlock> qualifyingBlocks = blocks.stream()
				.filter(Objects::nonNull)
				.filter(b -> b.topScore() != null && b.topScore() >= threshold)
				.toList();

		GroundedPrompt prompt = promptBuilder.build(query, qualifyingBlocks);
		String answer = null;
		if (shouldGenerate) {
			answer = answerGenerator.generate(prompt.systemPrompt(), prompt.userPrompt());
		}

		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
		log.info("Grounded query with {} qualifying blocks (maxScore={}, threshold={}, generated={}) in {} ms",
				qualifyingBlocks.size(), String.format("%.4f", maxScore),
				String.format("%.4f", threshold), shouldGenerate, elapsedMs);

		return new GroundedAnswerResponse(
				query,
				answer,
				false,
				null,
				maxScore,
				threshold,
				prompt.citations(),
				prompt.formattedContext(),
				prompt.systemPrompt(),
				prompt.userPrompt(),
				elapsedMs);
	}

	private GroundedAnswerResponse refusedResponse(
			String query, Double maxScore, double threshold, String reason, long elapsedMs) {
		return new GroundedAnswerResponse(
				query,
				properties.getRefusalMessage(),
				true,
				reason,
				maxScore,
				threshold,
				List.of(),
				"",
				null,
				null,
				elapsedMs);
	}
}

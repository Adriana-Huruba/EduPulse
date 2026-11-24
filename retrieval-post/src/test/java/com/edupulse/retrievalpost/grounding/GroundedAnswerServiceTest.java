package com.edupulse.retrievalpost.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edupulse.retrievalpost.config.GroundingProperties;
import com.edupulse.retrievalpost.stitch.StitchedBlock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroundedAnswerServiceTest {

	private GroundingProperties properties;
	private GroundedPromptBuilder promptBuilder;

	@BeforeEach
	void setUp() {
		properties = new GroundingProperties();
		properties.setSimilarityThreshold(0.5);
		properties.setRefusalMessage("I don't know.");
		properties.setChatEnabled(true);
		promptBuilder = new GroundedPromptBuilder(new CitationHeaderFormatter(), properties);
	}

	@Test
	void buildsGroundedPromptWithCitationsAndGeneratesAnswer_successPath() {
		StitchedBlock highScore = new StitchedBlock(
				"doc-iso", 1, "cybersecurity-policy-iso27001.pdf", 4, 4, 3, 3, List.of(3),
				"Confirmed personal data breaches must be escalated within 72 hours.", 14, 0.96);
		StitchedBlock lowScoreNoise = new StitchedBlock(
				"doc-noise", 1, "cafeteria.pdf", 1, 1, 0, 0, List.of(0),
				"Unrelated low-scoring chunk.", 6, 0.12);

		AnswerGenerator stubLlm = (systemPrompt, userPrompt) -> {
			assertThat(systemPrompt).contains("Answer ONLY using facts explicitly stated");
			assertThat(systemPrompt).contains("Cite the supporting source inline");
			assertThat(systemPrompt).contains("I don't know.");
			assertThat(userPrompt).contains("[SOURCE 1 | source: cybersecurity-policy-iso27001.pdf | version: 1 | page: 4]");
			assertThat(userPrompt).doesNotContain("cafeteria.pdf");
			return "Personal data breaches must be escalated within 72 hours [SOURCE 1].";
		};

		GroundedAnswerService service = new GroundedAnswerService(promptBuilder, stubLlm, properties);
		GroundedAnswerResponse response = service.groundAndAnswer(
				"What is the breach escalation window?",
				List.of(highScore, lowScoreNoise),
				0.5,
				true);

		assertThat(response.refused()).isFalse();
		assertThat(response.refusalReason()).isNull();
		assertThat(response.answer()).isEqualTo("Personal data breaches must be escalated within 72 hours [SOURCE 1].");
		assertThat(response.citations()).hasSize(1);
		assertThat(response.citations().get(0).source()).isEqualTo("cybersecurity-policy-iso27001.pdf");
		assertThat(response.citations().get(0).version()).isEqualTo(1);
		assertThat(response.citations().get(0).page()).isEqualTo("4");
	}

	@Test
	void refusesWithIDontKnowBelowSimilarityThresholdWithoutCallingLlm() {
		StitchedBlock weakMatch = new StitchedBlock(
				"doc-iso", 1, "cybersecurity-policy-iso27001.pdf", 1, 1, 0, 0, List.of(0),
				"ISMS Policy overview.", 5, 0.18);

		AtomicBoolean llmCalled = new AtomicBoolean(false);
		AnswerGenerator guardLlm = (sys, usr) -> {
			llmCalled.set(true);
			return "Should never be called";
		};

		GroundedAnswerService service = new GroundedAnswerService(promptBuilder, guardLlm, properties);
		GroundedAnswerResponse response = service.groundAndAnswer(
				"How do I bake sourdough bread?", List.of(weakMatch), 0.5, true);

		assertThat(llmCalled.get()).isFalse();
		assertThat(response.refused()).isTrue();
		assertThat(response.answer()).isEqualTo("I don't know.");
		assertThat(response.refusalReason()).contains("below similarity threshold");
		assertThat(response.citations()).isEmpty();
	}

	@Test
	void refusesWithIDontKnowWhenNoContextBlocksRetrieved() {
		GroundedAnswerService service = new GroundedAnswerService(promptBuilder, (s, u) -> "unused", properties);
		GroundedAnswerResponse response = service.groundAndAnswer("Anything?", List.of(), 0.5, true);

		assertThat(response.refused()).isTrue();
		assertThat(response.answer()).isEqualTo("I don't know.");
		assertThat(response.refusalReason()).contains("No context blocks");
	}

	@Test
	void propagatesGroundingExceptionWhenLlmFails_failurePath() {
		StitchedBlock valid = new StitchedBlock(
				"doc-iso", 1, "cybersecurity-policy-iso27001.pdf", 3, 3, 2, 2, List.of(2),
				"Use AES-256-GCM at rest.", 8, 0.94);
		AnswerGenerator failingLlm = (sys, usr) -> {
			throw new GroundingException("Ollama connection refused");
		};

		GroundedAnswerService service = new GroundedAnswerService(promptBuilder, failingLlm, properties);
		assertThatThrownBy(() -> service.groundAndAnswer("What encryption?", List.of(valid), 0.5, true))
				.isInstanceOf(GroundingException.class)
				.hasMessageContaining("Ollama connection refused");
	}
}

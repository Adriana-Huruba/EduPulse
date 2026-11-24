package com.edupulse.retrievalpost.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupulse.retrievalpost.grounding.Citation;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaithfulnessCheckerTest {

	private final FaithfulnessChecker checker = new FaithfulnessChecker();

	private static final String CONTEXT = """
			[SOURCE 1 | source: cybersecurity-policy-iso27001.pdf | version: 1 | page: 4]
			Confirmed personal data breaches must be reported to the DPO within 4 hours and escalated to the supervisory authority within 72 hours.
			""";

	private static final List<Citation> CITATIONS = List.of(new Citation(
			1, "doc-iso", "cybersecurity-policy-iso27001.pdf", 1, "4", 0.99,
			"[SOURCE 1 | source: cybersecurity-policy-iso27001.pdf | version: 1 | page: 4]",
			"Confirmed personal data breaches..."));

	@Test
	void passesGroundedAnswerWithValidCitation_successPath() {
		FaithfulnessChecker.FaithfulnessResult result = checker.evaluate(
				"Confirmed personal data breaches must be reported to the DPO within 4 hours and escalated within 72 hours [SOURCE 1].",
				CONTEXT, CITATIONS, false, "I don't know.", 0.70);

		assertThat(result.faithful()).isTrue();
		assertThat(result.citationValid()).isTrue();
		assertThat(result.lexicalSupportRatio()).isGreaterThanOrEqualTo(0.90);
	}

	@Test
	void passesExactRefusalWithoutCitations_successPath() {
		FaithfulnessChecker.FaithfulnessResult result = checker.evaluate(
				"I don't know.", "", List.of(), true, "I don't know.", 0.70);

		assertThat(result.faithful()).isTrue();
		assertThat(result.score()).isEqualTo(1.0);
	}

	@Test
	void failsWhenInlineCitationIsMissing_failurePath() {
		FaithfulnessChecker.FaithfulnessResult result = checker.evaluate(
				"Personal data breaches must be reported within 72 hours.",
				CONTEXT, CITATIONS, false, "I don't know.", 0.70);

		assertThat(result.faithful()).isFalse();
		assertThat(result.citationValid()).isFalse();
		assertThat(result.reason()).contains("missing inline [SOURCE N]");
	}

	@Test
	void failsWhenCitationIndexIsHallucinated_failurePath() {
		FaithfulnessChecker.FaithfulnessResult result = checker.evaluate(
				"Personal data breaches must be reported within 72 hours [SOURCE 9].",
				CONTEXT, CITATIONS, false, "I don't know.", 0.70);

		assertThat(result.faithful()).isFalse();
		assertThat(result.citationValid()).isFalse();
		assertThat(result.reason()).contains("Hallucinated citation index");
	}

	@Test
	void failsWhenAnswerContainsUnsupportedHallucinatedFacts_failurePath() {
		FaithfulnessChecker.FaithfulnessResult result = checker.evaluate(
				"Quantum satellites transmit encrypted bacon telemetry across Neptune orbit every Tuesday [SOURCE 1].",
				CONTEXT, CITATIONS, false, "I don't know.", 0.70);

		assertThat(result.faithful()).isFalse();
		assertThat(result.citationValid()).isTrue();
		assertThat(result.lexicalSupportRatio()).isLessThan(0.20);
	}
}

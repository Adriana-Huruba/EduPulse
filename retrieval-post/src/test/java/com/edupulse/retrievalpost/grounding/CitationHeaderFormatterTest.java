package com.edupulse.retrievalpost.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupulse.retrievalpost.stitch.StitchedBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationHeaderFormatterTest {

	private final CitationHeaderFormatter formatter = new CitationHeaderFormatter();

	@Test
	void formatsEveryBlockWithSourceVersionAndPageHeader() {
		StitchedBlock singlePage = new StitchedBlock(
				"doc-iso", 2, "cybersecurity-policy-iso27001.pdf", 4, 4, 3, 3, List.of(3),
				"Personal data breaches must be reported within 72 hours.", 12, 0.99);
		StitchedBlock multiPage = new StitchedBlock(
				"doc-zt", 1, "zero-trust-architecture-twocolumn.pdf", 2, 3, 1, 2, List.of(1, 2),
				"Microservices authenticate via mTLS with 24h SPIFFE certificates.", 15, 0.91);

		CitationHeaderFormatter.FormattedContext formatted = formatter.format(List.of(singlePage, multiPage));

		assertThat(formatted.citations()).hasSize(2);
		assertThat(formatted.citations().get(0).header())
				.isEqualTo("[SOURCE 1 | source: cybersecurity-policy-iso27001.pdf | version: 2 | page: 4]");
		assertThat(formatted.citations().get(1).header())
				.isEqualTo("[SOURCE 2 | source: zero-trust-architecture-twocolumn.pdf | version: 1 | page: 2-3]");
		assertThat(formatted.text())
				.contains("[SOURCE 1 | source: cybersecurity-policy-iso27001.pdf | version: 2 | page: 4]")
				.contains("Personal data breaches must be reported within 72 hours.")
				.contains("[SOURCE 2 | source: zero-trust-architecture-twocolumn.pdf | version: 1 | page: 2-3]")
				.contains("Microservices authenticate via mTLS with 24h SPIFFE certificates.");
	}

	@Test
	void appliesSafeDefaultsWhenOptionalMetadataIsMissing() {
		StitchedBlock sparse = new StitchedBlock(
				"doc-x", null, null, null, null, 0, 0, List.of(0),
				"Fallback text.", 3, 0.75);

		CitationHeaderFormatter.FormattedContext formatted = formatter.format(List.of(sparse));

		assertThat(formatted.citations().get(0).header())
				.isEqualTo("[SOURCE 1 | source: unknown | version: 1 | page: unknown]");
	}
}

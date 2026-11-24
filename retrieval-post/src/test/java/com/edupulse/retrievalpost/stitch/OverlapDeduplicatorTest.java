package com.edupulse.retrievalpost.stitch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OverlapDeduplicatorTest {

	private final OverlapDeduplicator deduplicator = new OverlapDeduplicator(15, 400);

	@Test
	void removesExactSuffixPrefixOverlapAcrossConsecutiveChunks() {
		String c0 = "Clause A.16 defines breach handling. Any personal data breach must be logged immediately.";
		String c1 = "Any personal data breach must be logged immediately. Escalate to the DPO within 4 hours.";
		String c2 = "Escalate to the DPO within 4 hours. Notify the supervisory authority within 72 hours.";

		String merged = deduplicator.mergeAll(List.of(c0, c1, c2));

		assertThat(merged).isEqualTo(
				"Clause A.16 defines breach handling. Any personal data breach must be logged immediately."
						+ " Escalate to the DPO within 4 hours. Notify the supervisory authority within 72 hours.");
	}

	@Test
	void removesOverlapWhenWhitespaceDiffersAcrossBoundary() {
		String c0 = "All production databases require\nAES-256-GCM encryption at rest.";
		String c1 = "AES-256-GCM  encryption   at rest. Keys are rotated every 365 days.";

		String merged = deduplicator.mergePair(c0, c1);

		assertThat(merged).isEqualTo(
				"All production databases require\nAES-256-GCM encryption at rest. Keys are rotated every 365 days.");
	}

	@Test
	void joinsCleanlyWhenNoOverlapExists() {
		String c0 = "First section covers access control.";
		String c1 = "Second section covers physical security.";

		assertThat(deduplicator.mergePair(c0, c1))
				.isEqualTo("First section covers access control.\nSecond section covers physical security.");
	}

	@Test
	void ignoresOverlapShorterThanMinThreshold() {
		String c0 = "Policy section end.";
		String c1 = "end. Next sentence begins.";

		// "end." is 4 chars (< 15), so it should not be falsely stripped.
		assertThat(deduplicator.mergePair(c0, c1))
				.isEqualTo("Policy section end.\nend. Next sentence begins.");
	}

	@Test
	void doesNotDuplicateAlreadyContainedChunk() {
		String c0 = "Full paragraph about Zero-Trust mTLS certificates and 24-hour TTL.";
		String c1 = "Zero-Trust mTLS certificates and 24-hour TTL.";

		assertThat(deduplicator.mergePair(c0, c1)).isEqualTo(c0);
	}
}

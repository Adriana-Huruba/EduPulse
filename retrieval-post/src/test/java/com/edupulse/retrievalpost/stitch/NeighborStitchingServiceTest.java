package com.edupulse.retrievalpost.stitch;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupulse.retrievalpost.config.StitchProperties;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NeighborStitchingServiceTest {

	private StitchProperties properties;
	private TokenEstimator tokenEstimator;

	@BeforeEach
	void setUp() {
		properties = new StitchProperties();
		properties.setEnabled(true);
		properties.setWindowBefore(1);
		properties.setWindowAfter(1);
		properties.setMaxTokenBudget(2000);
		properties.setMinOverlapChars(15);
		properties.setMaxOverlapChars(400);
		tokenEstimator = new TokenEstimator();
	}

	@Test
	void stitchesMiddleChunkWithPreviousAndNextAndDeduplicatesOverlap_successPath() {
		List<RetrievedChunk> docChunks = List.of(
				chunk("d1-0", "doc-1", 1, "iso.pdf", 1, 0,
						"Section 1 intro. Password rules require 14 characters minimum.", 0.5),
				chunk("d1-1", "doc-1", 1, "iso.pdf", 2, 1,
						"Password rules require 14 characters minimum. Privileged accounts must use FIDO2 MFA.", 0.95),
				chunk("d1-2", "doc-1", 1, "iso.pdf", 2, 2,
						"Privileged accounts must use FIDO2 MFA. Rotate credentials immediately upon compromise.", 0.6));

		NeighborStitchingService service = new NeighborStitchingService(inMemoryRepo(docChunks), tokenEstimator, properties);
		StitchOutcome outcome = service.stitch(List.of(docChunks.get(1)));

		assertThat(outcome.fallbackUsed()).isFalse();
		assertThat(outcome.budgetTruncated()).isFalse();
		assertThat(outcome.blocks()).hasSize(1);

		StitchedBlock block = outcome.blocks().get(0);
		assertThat(block.docId()).isEqualTo("doc-1");
		assertThat(block.version()).isEqualTo(1);
		assertThat(block.startPage()).isEqualTo(1);
		assertThat(block.endPage()).isEqualTo(2);
		assertThat(block.startChunkIndex()).isEqualTo(0);
		assertThat(block.endChunkIndex()).isEqualTo(2);
		assertThat(block.mergedChunkIndices()).containsExactly(0, 1, 2);
		assertThat(block.content()).isEqualTo(
				"Section 1 intro. Password rules require 14 characters minimum."
						+ " Privileged accounts must use FIDO2 MFA."
						+ " Rotate credentials immediately upon compromise.");
	}

	@Test
	void handlesFirstChunkBoundaryWithoutNegativeIndexLookup() {
		List<RetrievedChunk> docChunks = List.of(
				chunk("d1-0", "doc-1", 1, "iso.pdf", 1, 0,
						"First chunk opening text. Shared overlap phrase across chunks.", 0.92),
				chunk("d1-1", "doc-1", 1, "iso.pdf", 1, 1,
						"Shared overlap phrase across chunks. Second chunk continuation.", 0.70),
				chunk("d1-2", "doc-1", 1, "iso.pdf", 2, 2,
						"Third chunk should not be pulled when windowAfter=1.", 0.40));

		NeighborChunkRepository assertingRepo = (docId, version, minIdx, maxIdx) -> {
			assertThat(minIdx).isZero();
			assertThat(maxIdx).isEqualTo(1);
			return inMemoryRepo(docChunks).findNeighbors(docId, version, minIdx, maxIdx);
		};

		NeighborStitchingService service = new NeighborStitchingService(assertingRepo, tokenEstimator, properties);
		StitchOutcome outcome = service.stitch(List.of(docChunks.get(0)));

		assertThat(outcome.blocks()).hasSize(1);
		StitchedBlock block = outcome.blocks().get(0);
		assertThat(block.mergedChunkIndices()).containsExactly(0, 1);
		assertThat(block.content())
				.isEqualTo("First chunk opening text. Shared overlap phrase across chunks. Second chunk continuation.");
	}

	@Test
	void handlesLastChunkBoundaryCleanly() {
		List<RetrievedChunk> docChunks = List.of(
				chunk("d1-0", "doc-1", 1, "iso.pdf", 1, 0, "Chunk zero text.", 0.30),
				chunk("d1-1", "doc-1", 1, "iso.pdf", 2, 1,
						"Penultimate chunk text. Repeated boundary sentence here.", 0.65),
				chunk("d1-2", "doc-1", 1, "iso.pdf", 3, 2,
						"Repeated boundary sentence here. Final chunk closing statement.", 0.94));

		NeighborStitchingService service = new NeighborStitchingService(inMemoryRepo(docChunks), tokenEstimator, properties);
		StitchOutcome outcome = service.stitch(List.of(docChunks.get(2)));

		assertThat(outcome.blocks()).hasSize(1);
		StitchedBlock block = outcome.blocks().get(0);
		assertThat(block.startChunkIndex()).isEqualTo(1);
		assertThat(block.endChunkIndex()).isEqualTo(2);
		assertThat(block.mergedChunkIndices()).containsExactly(1, 2);
		assertThat(block.content())
				.isEqualTo("Penultimate chunk text. Repeated boundary sentence here. Final chunk closing statement.");
	}

	@Test
	void handlesSingleChunkDocument() {
		RetrievedChunk onlyChunk = chunk("solo-0", "doc-solo", 1, "onepager.pdf", 1, 0,
				"Single-chunk executive summary policy.", 0.91);

		NeighborStitchingService service = new NeighborStitchingService(
				inMemoryRepo(List.of(onlyChunk)), tokenEstimator, properties);
		StitchOutcome outcome = service.stitch(List.of(onlyChunk));

		assertThat(outcome.blocks()).hasSize(1);
		StitchedBlock block = outcome.blocks().get(0);
		assertThat(block.docId()).isEqualTo("doc-solo");
		assertThat(block.mergedChunkIndices()).containsExactly(0);
		assertThat(block.content()).isEqualTo("Single-chunk executive summary policy.");
	}

	@Test
	void neverCrossesDocumentOrVersionBoundaries() {
		RetrievedChunk docAChunk0 = chunk("a-0", "doc-A", 1, "a.pdf", 1, 0, "Doc A v1 chunk 0.", 0.95);
		RetrievedChunk docBChunk1 = chunk("b-1", "doc-B", 1, "b.pdf", 1, 1, "Doc B v1 chunk 1.", 0.85);
		RetrievedChunk docAV2Chunk1 = chunk("a-v2-1", "doc-A", 2, "a.pdf", 1, 1, "Doc A v2 chunk 1.", 0.80);

		// Simulate a buggy repository that erroneously returns chunks from other docs/versions.
		NeighborChunkRepository leakyRepo = (docId, version, minIdx, maxIdx) ->
				List.of(docAChunk0, docBChunk1, docAV2Chunk1);

		NeighborStitchingService service = new NeighborStitchingService(leakyRepo, tokenEstimator, properties);
		StitchOutcome outcome = service.stitch(List.of(docAChunk0, docBChunk1));

		assertThat(outcome.blocks()).hasSize(2);
		assertThat(outcome.blocks().get(0).docId()).isEqualTo("doc-A");
		assertThat(outcome.blocks().get(0).mergedChunkIndices()).containsExactly(0);
		assertThat(outcome.blocks().get(0).content()).doesNotContain("Doc B").doesNotContain("Doc A v2");

		assertThat(outcome.blocks().get(1).docId()).isEqualTo("doc-B");
		assertThat(outcome.blocks().get(1).mergedChunkIndices()).containsExactly(1);
		assertThat(outcome.blocks().get(1).content()).doesNotContain("Doc A");
	}

	@Test
	void coalescesOverlappingWindowsFromSameDocumentWithoutDuplicatingBridgeChunk() {
		List<RetrievedChunk> docChunks = List.of(
				chunk("d1-0", "doc-1", 1, "iso.pdf", 1, 0, "Zero. Overlap 0-1 sentence.", 0.4),
				chunk("d1-1", "doc-1", 1, "iso.pdf", 1, 1, "Overlap 0-1 sentence. Anchor one. Overlap 1-2 sentence.", 0.96),
				chunk("d1-2", "doc-1", 1, "iso.pdf", 2, 2, "Overlap 1-2 sentence. Bridge two. Overlap 2-3 sentence.", 0.5),
				chunk("d1-3", "doc-1", 1, "iso.pdf", 2, 3, "Overlap 2-3 sentence. Anchor three. Overlap 3-4 sentence.", 0.90),
				chunk("d1-4", "doc-1", 1, "iso.pdf", 3, 4, "Overlap 3-4 sentence. Tail four.", 0.3));

		NeighborStitchingService service = new NeighborStitchingService(inMemoryRepo(docChunks), tokenEstimator, properties);
		// Anchors at index 1 and index 3 both have window=1, so their windows [0..2] and [2..4] overlap at 2.
		StitchOutcome outcome = service.stitch(List.of(docChunks.get(1), docChunks.get(3)));

		assertThat(outcome.blocks()).hasSize(1);
		StitchedBlock block = outcome.blocks().get(0);
		assertThat(block.mergedChunkIndices()).containsExactly(0, 1, 2, 3, 4);
		assertThat(block.content()).isEqualTo(
				"Zero. Overlap 0-1 sentence. Anchor one. Overlap 1-2 sentence. Bridge two."
						+ " Overlap 2-3 sentence. Anchor three. Overlap 3-4 sentence. Tail four.");
	}

	@Test
	void enforcesStrictTokenBudgetDuringNeighborExpansionAndAcrossBlocks() {
		String longSentence = "Detailed compliance audit evidence paragraph with regulatory controls. ".repeat(4).trim();
		List<RetrievedChunk> docChunks = List.of(
				chunk("d1-0", "doc-1", 1, "iso.pdf", 1, 0, "Neighbor before: " + longSentence, 0.5),
				chunk("d1-1", "doc-1", 1, "iso.pdf", 1, 1, "Top anchor text: " + longSentence, 0.98),
				chunk("d1-2", "doc-1", 1, "iso.pdf", 2, 2, "Neighbor after: " + longSentence, 0.5),
				chunk("d2-0", "doc-2", 1, "zt.pdf", 1, 0, "Second doc anchor: " + longSentence, 0.80));

		int singleChunkTokens = tokenEstimator.estimateTokens(docChunks.get(1).content());
		// Budget fits the first anchor + at most one extra chunk, not all 4.
		int tightBudget = singleChunkTokens + 10;

		NeighborStitchingService service = new NeighborStitchingService(inMemoryRepo(docChunks), tokenEstimator, properties);
		StitchOutcome outcome = service.stitch(List.of(docChunks.get(1), docChunks.get(3)), 1, 1, tightBudget);

		assertThat(outcome.budgetTruncated()).isTrue();
		assertThat(outcome.totalEstimatedTokens()).isLessThanOrEqualTo(tightBudget);
		assertThat(outcome.blocks()).hasSize(1);
		assertThat(outcome.blocks().get(0).mergedChunkIndices()).containsExactly(1);
	}

	@Test
	void fallsBackToAnchorOnlyWhenNeighborRepositoryFails_failurePath() {
		RetrievedChunk anchor = chunk("d1-2", "doc-1", 1, "iso.pdf", 3, 2,
				"Audit logs must be retained for 7 years in a WORM archive.", 0.97);
		NeighborChunkRepository failingRepo = (docId, version, minIdx, maxIdx) -> {
			throw new RuntimeException("PostgreSQL connection pool exhausted");
		};

		NeighborStitchingService service = new NeighborStitchingService(failingRepo, tokenEstimator, properties);
		StitchOutcome outcome = service.stitch(List.of(anchor));

		assertThat(outcome.fallbackUsed()).isTrue();
		assertThat(outcome.fallbackReason()).contains("PostgreSQL connection pool exhausted");
		assertThat(outcome.blocks()).hasSize(1);
		assertThat(outcome.blocks().get(0).mergedChunkIndices()).containsExactly(2);
		assertThat(outcome.blocks().get(0).content()).isEqualTo(anchor.content());
	}

	private static NeighborChunkRepository inMemoryRepo(List<RetrievedChunk> chunks) {
		return (docId, version, minIdx, maxIdx) -> chunks.stream()
				.filter(c -> Objects.equals(c.docId(), docId))
				.filter(c -> version == null || Objects.equals(c.version(), version))
				.filter(c -> c.chunkIndex() != null && c.chunkIndex() >= minIdx && c.chunkIndex() <= maxIdx)
				.sorted(Comparator.comparingInt(RetrievedChunk::chunkIndex))
				.toList();
	}

	private static RetrievedChunk chunk(
			String id, String docId, int version, String source, int page, int idx, String content, double score) {
		return new RetrievedChunk(id, content, docId, version, source, page, idx, score, score, idx + 1, Map.of());
	}
}

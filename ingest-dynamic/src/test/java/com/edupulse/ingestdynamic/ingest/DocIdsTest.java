package com.edupulse.ingestdynamic.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DocIdsTest {

	@Test
	void sameUrlGivesSameId() {
		assertThat(DocIds.forUrl("https://Example.org:443/a/b?x=1#frag"))
				.isEqualTo(DocIds.forUrl("https://example.org/a/b?x=1"));
	}

	@Test
	void differentUrlsGiveDifferentIds() {
		assertThat(DocIds.forUrl("https://example.org/a")).isNotEqualTo(DocIds.forUrl("https://example.org/b"));
		assertThat(DocIds.forUrl("https://example.org/a?x=1")).isNotEqualTo(DocIds.forUrl("https://example.org/a?x=2"));
	}

	@Test
	void chunkIdsAreDeterministicUuidsAndDistinctPerChunk() {
		String first = DocIds.chunkId("doc", 1, 0);

		assertThat(first).isEqualTo(DocIds.chunkId("doc", 1, 0));
		assertThat(first).isNotEqualTo(DocIds.chunkId("doc", 1, 1));
		assertThat(first).isNotEqualTo(DocIds.chunkId("doc", 2, 0));
		assertThat(UUID.fromString(first)).isNotNull();
	}

}

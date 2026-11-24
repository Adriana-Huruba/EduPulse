package com.edupulse.retrievalpost.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WordPieceTokenizerTest {

	private WordPieceTokenizer tokenizer;

	@BeforeEach
	void setUp() {
		tokenizer = new WordPieceTokenizer(Map.ofEntries(
				Map.entry("[PAD]", 0L),
				Map.entry("[UNK]", 100L),
				Map.entry("[CLS]", 101L),
				Map.entry("[SEP]", 102L),
				Map.entry("data", 200L),
				Map.entry("breach", 201L),
				Map.entry("encrypt", 202L),
				Map.entry("##ion", 203L),
				Map.entry("café".replace("é", "e"), 204L),
				Map.entry("?", 205L),
				Map.entry("policy", 206L),
				Map.entry("aes", 207L)));
	}

	@Test
	void encodesQueryAndPassageWithWordPieceAndTokenTypeIds() {
		WordPieceTokenizer.EncodedPair encoded = tokenizer.encodePair(
				"Data breach?", "Café encryption policy", 32);

		// [CLS] data breach ? [SEP] cafe encrypt ##ion policy [SEP]
		assertThat(encoded.inputIds()).containsExactly(101L, 200L, 201L, 205L, 102L, 204L, 202L, 203L, 206L, 102L);
		assertThat(encoded.attentionMask()).containsOnly(1L);
		assertThat(encoded.tokenTypeIds()).containsExactly(0L, 0L, 0L, 0L, 0L, 1L, 1L, 1L, 1L, 1L);
	}

	@Test
	void unknownWordFallsBackToUnkToken() {
		WordPieceTokenizer.EncodedPair encoded = tokenizer.encodePair("data", "unknownword", 16);
		// [CLS] data [SEP] [UNK] [SEP]
		assertThat(encoded.inputIds()).containsExactly(101L, 200L, 102L, 100L, 102L);
	}

	@Test
	void truncatesLongerPassageWhilePreservingQuery() {
		WordPieceTokenizer.EncodedPair encoded = tokenizer.encodePair(
				"data", "policy policy policy policy policy policy", 7);

		// maxSequenceLength=7 => 4 content tokens: 1 query + 3 passage + [CLS],[SEP],[SEP]
		assertThat(encoded.inputIds()).hasSize(7);
		assertThat(encoded.inputIds()).containsExactly(101L, 200L, 102L, 206L, 206L, 206L, 102L);
	}

	@Test
	void rejectsTooSmallMaxSequenceLength() {
		assertThatThrownBy(() -> tokenizer.encodePair("data", "policy", 3))
				.isInstanceOf(IllegalArgumentException.class);
	}
}

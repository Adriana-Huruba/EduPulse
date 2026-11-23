package com.edupulse.ingestdynamic.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class TextChunkerTest {

	@Test
	void shortTextIsOneChunk() {
		List<String> chunks = new TextChunker(200, 20).chunk("Just one short paragraph.");

		assertThat(chunks).containsExactly("Just one short paragraph.");
	}

	@Test
	void blankTextGivesNoChunks() {
		assertThat(new TextChunker(200, 20).chunk("  \n\n  ")).isEmpty();
	}

	@Test
	void paragraphsArePackedAndChunksStayWithinSize() {
		String text = "alpha ".repeat(30).strip() + "\n\n" + "beta ".repeat(30).strip() + "\n\n"
				+ "gamma ".repeat(30).strip();

		List<String> chunks = new TextChunker(250, 40).chunk(text);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(250));
	}

	@Test
	void nextChunkRepeatsTheEndOfThePreviousOne() {
		String text = "one two three four five six seven eight nine ten ".repeat(20).strip();

		List<String> chunks = new TextChunker(120, 30).chunk(text);

		assertThat(chunks).hasSizeGreaterThan(2);
		for (int i = 1; i < chunks.size(); i++) {
			String previous = chunks.get(i - 1);
			String start = chunks.get(i).substring(0, 8).strip();
			assertThat(previous).as("chunk %d starts with text from chunk %d", i, i - 1).contains(start);
		}
	}

	@Test
	void everyWordSurvivesChunking() {
		String text = "word0 word1 word2 word3 word4 word5 word6 word7 word8 word9 ".repeat(15).strip();

		String joined = String.join(" ", new TextChunker(100, 20).chunk(text));

		for (int i = 0; i < 10; i++) {
			assertThat(joined).contains("word" + i);
		}
	}

	@Test
	void wordWithoutSpacesIsHardSplit() {
		List<String> chunks = new TextChunker(50, 10).chunk("x".repeat(500));

		assertThat(chunks).hasSizeGreaterThan(5);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(50));
	}

	@Test
	void invalidSettingsAreRejected() {
		assertThatThrownBy(() -> new TextChunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TextChunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
	}

}

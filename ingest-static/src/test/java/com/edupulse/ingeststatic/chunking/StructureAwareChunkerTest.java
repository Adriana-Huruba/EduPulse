package com.edupulse.ingeststatic.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.edupulse.ingeststatic.pdf.PageText;

class StructureAwareChunkerTest {

    @Test
    void detectsNumberedAndTitleCaseHeadingsAndTracksSections() {
        StructureAwareChunker chunker = new StructureAwareChunker(250, 50);
        String p1 = "1. Introduction\n\n"
                + "Retrieval-augmented generation improves factual accuracy by grounding responses in stored documents. "
                + "This section explains the ingestion flow in detail.\n\n"
                + "2. Chunking Strategy\n\n"
                + "Structure-aware chunking keeps paragraphs and table rows intact whenever possible.";

        List<TextChunk> chunks = chunker.chunk(List.of(new PageText(1, p1)));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).section()).isEqualTo("1. Introduction");
        assertThat(chunks.get(chunks.size() - 1).section()).isEqualTo("2. Chunking Strategy");
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(250));
    }

    @Test
    void preservesOverlapWithinSameSectionAndNeverCrossesSectionBoundary() {
        StructureAwareChunker chunker = new StructureAwareChunker(220, 60);
        String body = "1. Architecture\n\n"
                + "First sentence describes the gateway and Eureka registration for all services. "
                + "Second sentence describes how ingest-static initializes the pgvector schema cleanly. "
                + "Third sentence explains deterministic document identifiers and SHA-256 checksums. "
                + "Fourth sentence concludes the architecture overview with health check details.";

        List<TextChunk> chunks = chunker.chunk(List.of(new PageText(1, body)));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        // Sequential chunk indices starting at 0
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
            assertThat(chunks.get(i).section()).isEqualTo("1. Architecture");
        }
        // Second chunk should repeat a tail from the first chunk
        String firstText = chunks.get(0).text();
        String secondText = chunks.get(1).text();
        String tailWord = firstText.substring(firstText.lastIndexOf(' ') + 1);
        assertThat(secondText).contains(tailWord);
    }

    @Test
    void rejectsInvalidChunkingConfiguration() {
        assertThatThrownBy(() -> new StructureAwareChunker(40, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructureAwareChunker(200, 120))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

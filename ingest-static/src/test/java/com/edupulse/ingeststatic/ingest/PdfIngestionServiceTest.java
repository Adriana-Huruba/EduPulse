package com.edupulse.ingeststatic.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import com.edupulse.ingeststatic.TestPdfFactory;
import com.edupulse.ingeststatic.chunking.StructureAwareChunker;
import com.edupulse.ingeststatic.config.IngestProperties;
import com.edupulse.ingeststatic.exception.DocumentParsingException;
import com.edupulse.ingeststatic.exception.InvalidDocumentException;
import com.edupulse.ingeststatic.exception.SourceNotFoundException;
import com.edupulse.ingeststatic.exception.StorageException;
import com.edupulse.ingeststatic.pdf.PdfTextExtractor;

class PdfIngestionServiceTest {

    @TempDir
    Path tempDir;

    private VectorStore vectorStore;
    private ChunkRepository repository;
    private PdfIngestionService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        repository = mock(ChunkRepository.class);
        IngestProperties properties = new IngestProperties(400, 80, tempDir.toString(), "nomic-embed-text", 5_000_000);
        service = new PdfIngestionService(
                new PdfTextExtractor(),
                new StructureAwareChunker(properties),
                vectorStore,
                repository,
                properties);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestsPdfWithFullMetadataSchemaAndStableDocId() throws Exception {
        byte[] pdf = TestPdfFactory.multiPageDocument("EduPulse Guide", List.of(
                List.of("1. Overview",
                        "EduPulse is a modular RAG system built with Spring Boot and Spring AI for academic coursework. "
                                + "Static ingestion processes PDF documents into structured chunks stored in pgvector."),
                List.of("2. Metadata Schema",
                        "Every chunk carries doc_id, version, source, page, chunk_index, and checksum metadata fields.")
        ));
        when(repository.findLatestVersion(anyString())).thenReturn(Optional.empty());

        IngestionResult result = service.ingest("guides/overview.pdf", pdf);

        assertThat(result.status()).isEqualTo(IngestionStatus.INGESTED);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.chunks()).isGreaterThanOrEqualTo(1);
        assertThat(result.docId()).isEqualTo(DocumentIdentity.docId("guides/overview.pdf"));
        assertThat(result.checksum()).isEqualTo(DocumentIdentity.sha256(pdf));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(captor.capture());
        List<Document> storedDocs = captor.getValue();
        assertThat(storedDocs).hasSize(result.chunks());

        for (int i = 0; i < storedDocs.size(); i++) {
            Document doc = storedDocs.get(i);
            assertThat(doc.getId()).isEqualTo(DocumentIdentity.chunkId(result.docId(), 1, i));
            assertThat(doc.getMetadata()).containsKeys(
                    ChunkMetadata.DOC_ID,
                    ChunkMetadata.VERSION,
                    ChunkMetadata.SOURCE,
                    ChunkMetadata.PAGE,
                    ChunkMetadata.CHUNK_INDEX,
                    ChunkMetadata.CHECKSUM,
                    ChunkMetadata.EMBEDDING_MODEL);
            assertThat(doc.getMetadata().get(ChunkMetadata.DOC_ID)).isEqualTo(result.docId());
            assertThat(doc.getMetadata().get(ChunkMetadata.VERSION)).isEqualTo(1);
            assertThat(doc.getMetadata().get(ChunkMetadata.SOURCE)).isEqualTo("guides/overview.pdf");
            assertThat(doc.getMetadata().get(ChunkMetadata.CHUNK_INDEX)).isEqualTo(i);
            assertThat(doc.getMetadata().get(ChunkMetadata.CHECKSUM)).isEqualTo(result.checksum());
            assertThat(doc.getMetadata().get(ChunkMetadata.EMBEDDING_MODEL)).isEqualTo("nomic-embed-text");
        }
    }

    @Test
    void rerunningSameFileSkipsWithoutCreatingDuplicates() throws Exception {
        byte[] pdf = TestPdfFactory.tableDocument();
        String docId = DocumentIdentity.docId("tables.pdf");
        String checksum = DocumentIdentity.sha256(pdf);
        when(repository.findLatestVersion(docId))
                .thenReturn(Optional.of(new ChunkRepository.StoredVersion(1, checksum, 3, 3)));

        IngestionResult result = service.ingest("tables.pdf", pdf);

        assertThat(result.status()).isEqualTo(IngestionStatus.SKIPPED_UNCHANGED);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.chunks()).isEqualTo(3);
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void ingestsDirectoryAndIsolatesSingleCorruptFile() throws Exception {
        Files.write(tempDir.resolve("tables.pdf"), TestPdfFactory.tableDocument());
        Files.write(tempDir.resolve("two-column.pdf"), TestPdfFactory.twoColumnDocument());
        Files.write(tempDir.resolve("corrupt.pdf"), "%PDF-1.7\nbroken".getBytes(StandardCharsets.US_ASCII));
        when(repository.findLatestVersion(anyString())).thenReturn(Optional.empty());

        BatchIngestionResult batch = service.ingestDirectory();

        assertThat(batch.total()).isEqualTo(3);
        assertThat(batch.ingested()).isEqualTo(2);
        assertThat(batch.failed()).isEqualTo(1);
        assertThat(batch.results()).anyMatch(r -> r.source().equals("corrupt.pdf")
                && r.status() == IngestionStatus.FAILED);
    }

    @Test
    void rejectsNonPdfAndEmptyFiles() {
        assertThatThrownBy(() -> service.ingest("notes.txt", "hello world".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("%PDF-");

        assertThatThrownBy(() -> service.ingest("empty.pdf", new byte[0]))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsBlankPdfWithNoExtractableText() throws Exception {
        byte[] blank = TestPdfFactory.blankPageDocument();
        when(repository.findLatestVersion(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest("scan.pdf", blank))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("no extractable text");
    }

    @Test
    void rejectsPathTraversalAndMissingFile() {
        assertThatThrownBy(() -> service.ingestFile("../secret.pdf"))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("stay inside");

        assertThatThrownBy(() -> service.ingestFile("missing.pdf"))
                .isInstanceOf(SourceNotFoundException.class);
    }

    @Test
    void wrapsVectorStoreFailureInStorageException() throws Exception {
        byte[] pdf = TestPdfFactory.tableDocument();
        when(repository.findLatestVersion(anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("Ollama connection refused")).when(vectorStore).add(anyList());

        assertThatThrownBy(() -> service.ingest("tables.pdf", pdf))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Ollama connection refused");
    }
}

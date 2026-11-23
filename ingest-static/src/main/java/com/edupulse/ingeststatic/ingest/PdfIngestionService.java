package com.edupulse.ingeststatic.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.edupulse.ingeststatic.chunking.StructureAwareChunker;
import com.edupulse.ingeststatic.chunking.TextChunk;
import com.edupulse.ingeststatic.config.IngestProperties;
import com.edupulse.ingeststatic.exception.DocumentParsingException;
import com.edupulse.ingeststatic.exception.IngestionException;
import com.edupulse.ingeststatic.exception.InvalidDocumentException;
import com.edupulse.ingeststatic.exception.SourceNotFoundException;
import com.edupulse.ingeststatic.exception.StorageException;
import com.edupulse.ingeststatic.ingest.ChunkRepository.StoredVersion;
import com.edupulse.ingeststatic.pdf.PageText;
import com.edupulse.ingeststatic.pdf.PdfTextExtractor;
import com.edupulse.ingeststatic.pdf.TextCleaner;

/**
 * PDF ingestion pipeline (Story 1, Task 1): parse, clean, structure-aware chunking with overlap,
 * embed and store every chunk with the metadata schema and a stable {@code doc_id}.
 *
 * <p>Idempotency: the {@code doc_id} comes from the source path and chunk ids from
 * {@code doc_id + version + chunk_index}. When the stored version has the same checksum and is
 * complete, the file is skipped without calling the embedding model. When a previous run stopped
 * half way, the same version is written again and the upsert on chunk id fills the gaps, so a
 * re-run never creates duplicates.
 *
 * <p>When the content changed, the new version is written first and the old version is deleted
 * afterwards. Making that replacement atomic and failure-safe is Story 1, Task 2.
 */
@Service
public class PdfIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PdfIngestionService.class);
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    /** The PDF header may be preceded by some garbage bytes, readers accept it within the first KB. */
    private static final int PDF_MAGIC_SEARCH_WINDOW = 1024;

    private final PdfTextExtractor extractor;
    private final StructureAwareChunker chunker;
    private final VectorStore vectorStore;
    private final ChunkRepository repository;
    private final IngestProperties properties;
    private final ConcurrentHashMap<String, Object> documentLocks = new ConcurrentHashMap<>();

    public PdfIngestionService(PdfTextExtractor extractor, StructureAwareChunker chunker, VectorStore vectorStore,
                               ChunkRepository repository, IngestProperties properties) {
        this.extractor = extractor;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Ingests one PDF.
     *
     * @param source  logical source path (relative to the PDF directory, or the upload file name);
     *                it determines the {@code doc_id}
     * @param content PDF bytes
     */
    public IngestionResult ingest(String source, byte[] content) {
        long start = System.nanoTime();
        String normalizedSource = validateSource(source);
        validateContent(normalizedSource, content);
        String docId = DocumentIdentity.docId(normalizedSource);
        synchronized (documentLocks.computeIfAbsent(docId, k -> new Object())) {
            return doIngest(normalizedSource, docId, content, start);
        }
    }

    /** Ingests an uploaded file. Only the file name is kept as source, any client-side path is dropped. */
    public IngestionResult ingestUpload(String originalFilename, byte[] content) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidDocumentException("The uploaded file has no name");
        }
        String name = originalFilename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            throw new InvalidDocumentException("The uploaded file has no name");
        }
        return ingest(name, content);
    }

    /** Ingests one file of the configured PDF directory, given by its path relative to that directory. */
    public IngestionResult ingestFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new InvalidDocumentException("The 'path' parameter is required");
        }
        Path base = baseDirectory();
        Path file;
        try {
            file = base.resolve(relativePath.strip()).normalize();
        } catch (InvalidPathException e) {
            throw new InvalidDocumentException("Invalid path: " + relativePath);
        }
        if (!file.startsWith(base)) {
            throw new InvalidDocumentException("The path must stay inside the PDF directory");
        }
        if (!Files.isRegularFile(file)) {
            throw new SourceNotFoundException("File not found in the PDF directory: " + relativePath);
        }
        if (!isPdfFileName(file)) {
            throw new InvalidDocumentException("Only .pdf files can be ingested: " + relativePath);
        }
        try {
            if (!file.toRealPath().startsWith(base.toRealPath())) {
                throw new InvalidDocumentException("The path must stay inside the PDF directory");
            }
            long size = Files.size(file);
            if (size > properties.maxFileSizeBytes()) {
                throw new InvalidDocumentException("File is larger than the limit of "
                        + properties.maxFileSizeBytes() + " bytes: " + relativePath);
            }
            String source = base.relativize(file).toString().replace('\\', '/');
            return ingest(source, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IngestionException("Could not read " + relativePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Ingests every PDF of the configured directory (recursively). A failing file is logged and
     * reported, the remaining files are still processed.
     */
    public BatchIngestionResult ingestDirectory() {
        long start = System.nanoTime();
        Path base = baseDirectory();
        if (!Files.isDirectory(base)) {
            throw new SourceNotFoundException("PDF directory does not exist: " + base);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(base)) {
            files = walk.filter(Files::isRegularFile).filter(PdfIngestionService::isPdfFileName).sorted().toList();
        } catch (IOException e) {
            throw new IngestionException("Could not list PDF directory " + base + ": " + e.getMessage(), e);
        }
        log.info("Directory ingestion started: {} PDF file(s) in {}", files.size(), base);

        List<IngestionResult> results = new ArrayList<>(files.size());
        for (Path file : files) {
            String relative = base.relativize(file).toString().replace('\\', '/');
            long fileStart = System.nanoTime();
            try {
                results.add(ingestFile(relative));
            } catch (IngestionException e) {
                log.error("Ingestion of '{}' failed: {}", relative, e.getMessage());
                results.add(IngestionResult.failed(relative, e.getMessage(), elapsedMs(fileStart)));
            } catch (RuntimeException e) {
                log.error("Ingestion of '{}' failed unexpectedly", relative, e);
                results.add(IngestionResult.failed(relative, "Unexpected error: " + e.getMessage(), elapsedMs(fileStart)));
            }
        }
        BatchIngestionResult batch = BatchIngestionResult.of(base.toString(), results, elapsedMs(start));
        log.info("Directory ingestion finished in {} ms: {} ingested, {} updated, {} unchanged, {} failed",
                batch.durationMs(), batch.ingested(), batch.updated(), batch.skipped(), batch.failed());
        return batch;
    }

    public List<ChunkView> listChunks(String docId) {
        try {
            return repository.findByDocId(docId);
        } catch (DataAccessException e) {
            throw new StorageException("Could not read chunks from the vector store", e);
        }
    }

    public List<ChunkView> sampleChunks(int size) {
        try {
            return repository.randomSample(size);
        } catch (DataAccessException e) {
            throw new StorageException("Could not read chunks from the vector store", e);
        }
    }

    private IngestionResult doIngest(String source, String docId, byte[] content, long start) {
        String checksum = DocumentIdentity.sha256(content);
        Optional<StoredVersion> stored = findLatestVersion(docId);

        if (stored.isPresent() && stored.get().checksum().equals(checksum) && stored.get().isComplete()) {
            StoredVersion s = stored.get();
            log.info("Skipped '{}' (doc_id={}): unchanged, version {} with {} chunks already stored",
                    source, docId, s.version(), s.storedChunks());
            return new IngestionResult(docId, source, IngestionStatus.SKIPPED_UNCHANGED, s.version(), checksum,
                    0, s.storedChunks(), elapsedMs(start),
                    "Unchanged since version " + s.version() + ", nothing re-embedded");
        }

        // Same content but incomplete (a previous run failed half way): rewrite the same version,
        // the deterministic chunk ids make the upsert fill the gaps instead of duplicating.
        int version = stored.map(s -> s.checksum().equals(checksum) ? s.version() : s.version() + 1).orElse(1);
        if (stored.isPresent() && !stored.get().isComplete() && stored.get().checksum().equals(checksum)) {
            log.warn("'{}' (doc_id={}) version {} is incomplete ({} of {} chunks), writing it again",
                    source, docId, version, stored.get().storedChunks(), stored.get().expectedChunks());
        }

        List<PageText> pages = TextCleaner.cleanPages(extractor.extract(content, source));
        List<TextChunk> chunks = chunker.chunk(pages);
        if (chunks.isEmpty()) {
            throw new DocumentParsingException("PDF '" + source + "' contains no extractable text "
                    + "(scanned pages need OCR, which is not supported)");
        }

        List<Document> documents = toDocuments(docId, version, source, checksum, chunks);
        try {
            vectorStore.add(documents);
        } catch (RuntimeException e) {
            throw new StorageException("Embedding or storing the chunks of '" + source + "' failed: "
                    + rootCauseMessage(e), e);
        }

        int removed = 0;
        if (stored.isPresent()) {
            try {
                removed = repository.deleteOtherVersions(docId, version);
            } catch (DataAccessException e) {
                throw new StorageException("New version of '" + source + "' stored, but removing the old "
                        + "version failed: " + rootCauseMessage(e), e);
            }
        }

        IngestionStatus status = version == 1 ? IngestionStatus.INGESTED : IngestionStatus.UPDATED;
        long duration = elapsedMs(start);
        log.info("{} '{}' (doc_id={}, version={}): {} page(s), {} chunk(s), {} old chunk(s) removed, "
                        + "model={}, {} ms", status == IngestionStatus.INGESTED ? "Ingested" : "Updated",
                source, docId, version, pages.size(), chunks.size(), removed, properties.embeddingModel(), duration);
        return new IngestionResult(docId, source, status, version, checksum, pages.size(), chunks.size(), duration,
                status == IngestionStatus.INGESTED
                        ? "Ingested " + chunks.size() + " chunks"
                        : "Updated to version " + version + " with " + chunks.size() + " chunks");
    }

    List<Document> toDocuments(String docId, int version, String source, String checksum, List<TextChunk> chunks) {
        String ingestedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        List<Document> documents = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            ChunkMetadata metadata = new ChunkMetadata(docId, version, source, chunk.pageStart(), chunk.pageEnd(),
                    chunk.index(), chunks.size(), checksum, chunk.section(), properties.embeddingModel(), ingestedAt);
            documents.add(Document.builder()
                    .id(DocumentIdentity.chunkId(docId, version, chunk.index()))
                    .text(chunk.text())
                    .metadata(metadata.toMap())
                    .build());
        }
        return documents;
    }

    private Optional<StoredVersion> findLatestVersion(String docId) {
        try {
            return repository.findLatestVersion(docId);
        } catch (DataAccessException e) {
            throw new StorageException("Could not read the vector store: " + rootCauseMessage(e), e);
        }
    }

    private String validateSource(String source) {
        try {
            return DocumentIdentity.normalizeSource(source);
        } catch (IllegalArgumentException e) {
            throw new InvalidDocumentException("A source name is required");
        }
    }

    private void validateContent(String source, byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidDocumentException("File '" + source + "' is empty");
        }
        if (content.length > properties.maxFileSizeBytes()) {
            throw new InvalidDocumentException("File '" + source + "' is larger than the limit of "
                    + properties.maxFileSizeBytes() + " bytes");
        }
        if (!hasPdfHeader(content)) {
            throw new InvalidDocumentException("File '" + source + "' is not a PDF (missing %PDF- header)");
        }
    }

    static boolean hasPdfHeader(byte[] content) {
        int limit = Math.min(content.length, PDF_MAGIC_SEARCH_WINDOW) - PDF_MAGIC.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < PDF_MAGIC.length; j++) {
                if (content[i + j] != PDF_MAGIC[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private Path baseDirectory() {
        return Paths.get(properties.pdfDir()).toAbsolutePath().normalize();
    }

    private static boolean isPdfFileName(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}

package com.edupulse.ingeststatic.web;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.edupulse.ingeststatic.config.IngestProperties;
import com.edupulse.ingeststatic.exception.InvalidDocumentException;
import com.edupulse.ingeststatic.ingest.BatchIngestionResult;
import com.edupulse.ingeststatic.ingest.ChunkView;
import com.edupulse.ingeststatic.ingest.IngestionResult;
import com.edupulse.ingeststatic.ingest.PdfIngestionService;

/**
 * REST API for static PDF ingestion and chunk inspection.
 *
 * <p>Reachable through the API gateway under {@code /ingest-static/api/v1/ingest/...}.
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class PdfIngestionController {

    private final PdfIngestionService ingestionService;
    private final IngestProperties properties;

    public PdfIngestionController(PdfIngestionService ingestionService, IngestProperties properties) {
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    /** Ingests all PDF files found in the configured PDF directory. */
    @PostMapping("/directory")
    public BatchIngestionResult ingestDirectory() {
        return ingestionService.ingestDirectory();
    }

    /** Ingests a single PDF from the configured PDF directory by its relative path. */
    @PostMapping("/file")
    public IngestionResult ingestFile(@RequestParam("path") String relativePath) {
        return ingestionService.ingestFile(relativePath);
    }

    /** Ingests an uploaded PDF file. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResult upload(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("An uploaded PDF file is required");
        }
        try {
            return ingestionService.ingestUpload(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new InvalidDocumentException("Could not read uploaded file: " + e.getMessage());
        }
    }

    /** Lists all stored chunks for a given {@code doc_id}, ordered by version and {@code chunk_index}. */
    @GetMapping("/documents/{docId}/chunks")
    public List<ChunkView> listChunks(@PathVariable String docId) {
        return ingestionService.listChunks(docId);
    }

    /** Returns a random sample of stored chunks (default 20) for manual quality verification. */
    @GetMapping("/chunks/sample")
    public List<ChunkView> sampleChunks(@RequestParam(name = "size", defaultValue = "20") int size) {
        if (size < 1 || size > 100) {
            throw new InvalidDocumentException("Sample size must be between 1 and 100, got " + size);
        }
        return ingestionService.sampleChunks(size);
    }

    /** Returns the active ingestion configuration (chunk size, overlap, embedding model, PDF directory). */
    @GetMapping("/config")
    public IngestProperties config() {
        return properties;
    }
}

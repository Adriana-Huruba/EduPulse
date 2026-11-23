package com.edupulse.ingeststatic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the static PDF ingestion pipeline ({@code app.ingest.*}).
 *
 * <p>Every value can be overridden through an environment variable, see
 * {@code application.properties} and the README.
 *
 * @param chunkSize        maximum chunk length in characters, overlap included
 * @param chunkOverlap     number of characters repeated from the end of the previous chunk
 * @param pdfDir           directory scanned by the directory/file ingestion endpoints
 * @param embeddingModel   name of the embedding model, recorded in the chunk metadata
 * @param maxFileSizeBytes largest PDF accepted for ingestion
 */
@ConfigurationProperties(prefix = "app.ingest")
public record IngestProperties(
        @DefaultValue("1000") int chunkSize,
        @DefaultValue("200") int chunkOverlap,
        @DefaultValue("../resources") String pdfDir,
        @DefaultValue("unknown") String embeddingModel,
        @DefaultValue("52428800") long maxFileSizeBytes) {

    public static final int MIN_CHUNK_SIZE = 200;
    public static final int MAX_CHUNK_SIZE = 8000;

    public IngestProperties {
        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("app.ingest.chunk-size must be between "
                    + MIN_CHUNK_SIZE + " and " + MAX_CHUNK_SIZE + ", got " + chunkSize);
        }
        if (chunkOverlap < 0 || chunkOverlap * 2 > chunkSize) {
            throw new IllegalArgumentException("app.ingest.chunk-overlap must be between 0 and half of "
                    + "app.ingest.chunk-size (" + chunkSize / 2 + "), got " + chunkOverlap);
        }
        if (pdfDir == null || pdfDir.isBlank()) {
            throw new IllegalArgumentException("app.ingest.pdf-dir must not be blank");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("app.ingest.embedding-model must not be blank");
        }
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("app.ingest.max-file-size-bytes must be positive");
        }
    }
}

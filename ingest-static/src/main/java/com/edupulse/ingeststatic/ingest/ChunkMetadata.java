package com.edupulse.ingeststatic.ingest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata schema stored with every chunk in the {@code vector_store.metadata} column.
 *
 * <p>Required fields (assignment schema): {@code doc_id}, {@code version}, {@code source},
 * {@code page}, {@code chunk_index}, {@code checksum}. The other fields help with citations
 * ({@code page_end}, {@code section}), with the manifest and re-index tasks
 * ({@code embedding_model}, {@code ingested_at}, {@code chunk_count}) and with filtering
 * ({@code content_type}).
 */
public record ChunkMetadata(
        String docId,
        int version,
        String source,
        int page,
        int pageEnd,
        int chunkIndex,
        int chunkCount,
        String checksum,
        String section,
        String embeddingModel,
        String ingestedAt) {

    public static final String DOC_ID = "doc_id";
    public static final String VERSION = "version";
    public static final String SOURCE = "source";
    public static final String PAGE = "page";
    public static final String PAGE_END = "page_end";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String CHUNK_COUNT = "chunk_count";
    public static final String CHECKSUM = "checksum";
    public static final String SECTION = "section";
    public static final String EMBEDDING_MODEL = "embedding_model";
    public static final String INGESTED_AT = "ingested_at";
    public static final String CONTENT_TYPE = "content_type";

    /** Fields every chunk must carry. */
    public static final List<String> REQUIRED_FIELDS = List.of(DOC_ID, VERSION, SOURCE, PAGE, CHUNK_INDEX, CHECKSUM);

    public ChunkMetadata {
        requireText(docId, DOC_ID);
        requireText(source, SOURCE);
        requireText(checksum, CHECKSUM);
        requireText(embeddingModel, EMBEDDING_MODEL);
        requireText(ingestedAt, INGESTED_AT);
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        if (page < 1 || pageEnd < page) {
            throw new IllegalArgumentException("invalid page range " + page + "-" + pageEnd);
        }
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("invalid chunk index " + chunkIndex + " of " + chunkCount);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(DOC_ID, docId);
        map.put(VERSION, version);
        map.put(SOURCE, source);
        map.put(PAGE, page);
        map.put(PAGE_END, pageEnd);
        map.put(CHUNK_INDEX, chunkIndex);
        map.put(CHUNK_COUNT, chunkCount);
        map.put(CHECKSUM, checksum);
        if (section != null && !section.isBlank()) {
            map.put(SECTION, section);
        }
        map.put(EMBEDDING_MODEL, embeddingModel);
        map.put(INGESTED_AT, ingestedAt);
        map.put(CONTENT_TYPE, "application/pdf");
        return map;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

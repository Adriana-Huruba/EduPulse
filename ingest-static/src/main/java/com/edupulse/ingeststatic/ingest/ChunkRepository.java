package com.edupulse.ingeststatic.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read and housekeeping queries on the Spring AI {@code vector_store} table, keyed by the chunk
 * metadata. Writes of new chunks (with embeddings) go through Spring AI's {@code VectorStore}.
 */
@Repository
public class ChunkRepository {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private static final String CHUNK_COLUMNS = """
            id::text AS id, content,
            metadata->>'doc_id' AS doc_id,
            (metadata->>'version')::int AS version,
            metadata->>'source' AS source,
            (metadata->>'page')::int AS page,
            (metadata->>'page_end')::int AS page_end,
            (metadata->>'chunk_index')::int AS chunk_index,
            metadata->>'section' AS section,
            metadata->>'checksum' AS checksum,
            metadata->>'embedding_model' AS embedding_model
            """;

    private final JdbcTemplate jdbc;
    private final String table;

    public ChunkRepository(JdbcTemplate jdbc,
                           @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schema,
                           @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        if (!IDENTIFIER.matcher(schema).matches() || !IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid vector store schema or table name: " + schema + "." + tableName);
        }
        this.jdbc = jdbc;
        this.table = schema + "." + tableName;
    }

    /**
     * Latest stored version of a document.
     *
     * @param version        highest version found
     * @param checksum       checksum of that version
     * @param storedChunks   chunks of that version actually present
     * @param expectedChunks chunk count recorded in the metadata when the version was written
     */
    public record StoredVersion(int version, String checksum, int storedChunks, int expectedChunks) {

        /** False when a previous ingestion stopped before all chunks were written. */
        public boolean isComplete() {
            return storedChunks > 0 && storedChunks == expectedChunks;
        }
    }

    public Optional<StoredVersion> findLatestVersion(String docId) {
        String sql = "SELECT (metadata->>'version')::int AS version, metadata->>'checksum' AS checksum, "
                + "COUNT(*) AS stored, MAX((metadata->>'chunk_count')::int) AS expected "
                + "FROM " + table + " WHERE metadata->>'doc_id' = ? "
                + "GROUP BY 1, 2 ORDER BY 1 DESC LIMIT 1";
        List<StoredVersion> rows = jdbc.query(sql, (rs, i) -> new StoredVersion(
                rs.getInt("version"), rs.getString("checksum"), rs.getInt("stored"), rs.getInt("expected")), docId);
        return rows.stream().findFirst();
    }

    /** Removes every chunk of the document whose version differs from {@code keepVersion}. */
    public int deleteOtherVersions(String docId, int keepVersion) {
        return jdbc.update("DELETE FROM " + table
                + " WHERE metadata->>'doc_id' = ? AND (metadata->>'version')::int <> ?", docId, keepVersion);
    }

    public long countByDocId(String docId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE metadata->>'doc_id' = ?", Long.class, docId);
        return count == null ? 0 : count;
    }

    public List<ChunkView> findByDocId(String docId) {
        return jdbc.query("SELECT " + CHUNK_COLUMNS + " FROM " + table
                        + " WHERE metadata->>'doc_id' = ? ORDER BY version, chunk_index",
                ChunkRepository::mapChunk, docId);
    }

    public List<ChunkView> randomSample(int size) {
        return jdbc.query("SELECT " + CHUNK_COLUMNS + " FROM " + table + " ORDER BY random() LIMIT ?",
                ChunkRepository::mapChunk, size);
    }

    private static ChunkView mapChunk(ResultSet rs, int rowNum) throws SQLException {
        String content = rs.getString("content");
        return new ChunkView(
                rs.getString("id"),
                rs.getString("doc_id"),
                rs.getObject("version", Integer.class),
                rs.getString("source"),
                rs.getObject("page", Integer.class),
                rs.getObject("page_end", Integer.class),
                rs.getObject("chunk_index", Integer.class),
                rs.getString("section"),
                rs.getString("checksum"),
                rs.getString("embedding_model"),
                content == null ? 0 : content.length(),
                content);
    }
}

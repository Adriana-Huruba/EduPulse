package com.edupulse.retrievalpost.stitch;

import com.edupulse.retrievalpost.config.StitchProperties;
import com.edupulse.retrievalpost.model.MetadataKeys;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL {@code pgvector} implementation of {@link NeighborChunkRepository}.
 *
 * <p>Strictly scopes queries to a single {@code doc_id} (and {@code version} when supplied)
 * so neighbor lookups can never cross document boundaries.
 */
@Repository
public class JdbcNeighborChunkRepository implements NeighborChunkRepository {

	private static final Logger log = LoggerFactory.getLogger(JdbcNeighborChunkRepository.class);

	private final JdbcTemplate jdbcTemplate;
	private final String tableName;

	public JdbcNeighborChunkRepository(JdbcTemplate jdbcTemplate, StitchProperties properties) {
		this.jdbcTemplate = jdbcTemplate;
		this.tableName = validateTableName(properties.getVectorTableName());
	}

	@Override
	public List<RetrievedChunk> findNeighbors(String docId, Integer version, int minChunkIndex, int maxChunkIndex) {
		if (docId == null || docId.isBlank() || maxChunkIndex < minChunkIndex) {
			return List.of();
		}
		int clampedMin = Math.max(0, minChunkIndex);

		if (version != null) {
			String sql = """
					SELECT id::text AS id,
					       content,
					       metadata->>'doc_id' AS doc_id,
					       (metadata->>'version')::int AS version,
					       metadata->>'source' AS source,
					       (metadata->>'page')::int AS page,
					       (metadata->>'chunk_index')::int AS chunk_index
					FROM %s
					WHERE metadata->>'doc_id' = ?
					  AND (metadata->>'version')::int = ?
					  AND (metadata->>'chunk_index')::int BETWEEN ? AND ?
					ORDER BY (metadata->>'chunk_index')::int ASC
					""".formatted(tableName);
			List<RetrievedChunk> rows = jdbcTemplate.query(
					sql, this::mapRow, docId, version, clampedMin, maxChunkIndex);
			log.debug("Fetched {} neighbor chunks for docId={} version={} range=[{}, {}]",
					rows.size(), docId, version, clampedMin, maxChunkIndex);
			return rows;
		}

		String sql = """
				SELECT id::text AS id,
				       content,
				       metadata->>'doc_id' AS doc_id,
				       (metadata->>'version')::int AS version,
				       metadata->>'source' AS source,
				       (metadata->>'page')::int AS page,
				       (metadata->>'chunk_index')::int AS chunk_index
				FROM %s
				WHERE metadata->>'doc_id' = ?
				  AND (metadata->>'chunk_index')::int BETWEEN ? AND ?
				ORDER BY (metadata->>'chunk_index')::int ASC
				""".formatted(tableName);
		List<RetrievedChunk> rows = jdbcTemplate.query(
				sql, this::mapRow, docId, clampedMin, maxChunkIndex);
		log.debug("Fetched {} neighbor chunks for docId={} (any version) range=[{}, {}]",
				rows.size(), docId, clampedMin, maxChunkIndex);
		return rows;
	}

	private RetrievedChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
		String id = rs.getString("id");
		String content = rs.getString("content");
		String docId = rs.getString("doc_id");
		Integer version = rs.getObject("version", Integer.class);
		String source = rs.getString("source");
		Integer page = rs.getObject("page", Integer.class);
		Integer chunkIndex = rs.getObject("chunk_index", Integer.class);

		Map<String, Object> metadata = new LinkedHashMap<>();
		if (docId != null) {
			metadata.put(MetadataKeys.DOC_ID, docId);
		}
		if (version != null) {
			metadata.put(MetadataKeys.VERSION, version);
		}
		if (source != null) {
			metadata.put(MetadataKeys.SOURCE, source);
		}
		if (page != null) {
			metadata.put(MetadataKeys.PAGE, page);
		}
		if (chunkIndex != null) {
			metadata.put(MetadataKeys.CHUNK_INDEX, chunkIndex);
		}

		return new RetrievedChunk(
				id, content == null ? "" : content, docId, version, source, page, chunkIndex,
				null, null, rowNum + 1, metadata);
	}

	private static String validateTableName(String raw) {
		if (raw == null || !raw.matches("^[a-zA-Z0-9_.]+$")) {
			throw new IllegalArgumentException("Invalid vector table name: " + raw);
		}
		return raw;
	}
}

package com.edupulse.retrievalpre;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ChunkSearchRepository {

    private static final String MATCHING_CHUNKS = """
            WITH search AS (SELECT plainto_tsquery('simple', ?) AS terms)
            SELECT id, content, metadata->>'doc_id' AS doc_id, metadata->>'source' AS source,
                   metadata->>'version' AS version, metadata->>'page' AS page,
                   metadata->>'chunk_index' AS chunk_index
              FROM vector_store CROSS JOIN search
             WHERE to_tsvector('simple', content) @@ search.terms
            """;

    private final JdbcTemplate jdbc;

    ChunkSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Chunk> search(String query, MetadataFilter filter) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(query);
        StringBuilder sql = new StringBuilder(MATCHING_CHUNKS);

        if (!filter.conditions().isEmpty()) {
            StringJoiner conditions = new StringJoiner(" " + filter.mode().name() + " ", " AND (", ")\n");
            for (MetadataFilter.Condition condition : filter.conditions()) {
                conditions.add("(" + predicate(condition) + ")");
                parameters.add(condition.value());
            }
            sql.append(conditions);
        }
        sql.append(" ORDER BY ts_rank(to_tsvector('simple', content), search.terms) DESC, id LIMIT 20");

        return jdbc.query(sql.toString(), (row, index) -> new Chunk(
                row.getString("id"), row.getString("content"), row.getString("doc_id"),
                row.getString("source"), readInteger(row, "version"),
                readInteger(row, "page"), readInteger(row, "chunk_index")),
                parameters.toArray());
    }

    private static Integer readInteger(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String predicate(MetadataFilter.Condition condition) {
        MetadataFilter.Field field = condition.field();
        String value = "metadata->'" + field.key + "'";
        String operator = " " + condition.operator().sql + " ";
        return switch (field.type) {
            case TEXT -> "jsonb_typeof((" + value + ")::jsonb) = 'string' AND "
                    + "metadata->>'" + field.key + "'" + operator + "?";
            case INTEGER -> "jsonb_typeof((" + value + ")::jsonb) = 'number' AND "
                    + "(" + value + ")::jsonb" + operator + "to_jsonb(CAST(? AS integer))";
            case BOOLEAN -> "jsonb_typeof((" + value + ")::jsonb) = 'boolean' AND "
                    + "(" + value + ")::jsonb" + operator + "to_jsonb(CAST(? AS boolean))";
        };
    }

    record Chunk(String id, String content, String docId, String source, Integer version, Integer page,
                 Integer chunkIndex) {}
}

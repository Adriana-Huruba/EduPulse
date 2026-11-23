package com.edupulse.retrievalpre;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "RETRIEVAL_TEST_DB_URL", matches = ".+")
class ChunkSearchRepositoryPostgresTest {

    @Test
    void combinesTypedFiltersAgainstPostgresWithoutChangingTheTextSearch() {
        var dataSource = new SingleConnectionDataSource(
                System.getenv("RETRIEVAL_TEST_DB_URL"), "postgres", System.getenv("RETRIEVAL_TEST_DB_PASSWORD"), true);
        try {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TEMP TABLE vector_store (id text, content text, metadata json)");
            insert(jdbc, "a", "audit policy", """
                    {"doc_id":"a","source":"O'Reilly: audit.pdf","version":2,"page":10,"is_latest":true}
                    """);
            insert(jdbc, "b", "audit policy", """
                    {"doc_id":"b","source":"other.pdf","version":1,"page":2,"is_latest":false}
                    """);
            insert(jdbc, "c", "access control", """
                    {"doc_id":"c","source":"O'Reilly: audit.pdf","version":2,"page":10}
                    """);
            insert(jdbc, "d", "audit policy", """
                    {"doc_id":"d","source":"other.pdf","version":1,"page":"not recorded"}
                    """);
            var repository = new ChunkSearchRepository(jdbc);

            assertThat(repository.search("audit", MetadataFilter.parse(null, null)))
                    .extracting(ChunkSearchRepository.Chunk::docId).containsExactly("a", "b", "d");
            assertThat(repository.search("audit", MetadataFilter.parse(
                    List.of("source:eq:O'Reilly: audit.pdf", "page:gt:2", "is_latest:eq:true"), "AND")))
                    .extracting(ChunkSearchRepository.Chunk::docId).containsExactly("a");
            assertThat(repository.search("audit", MetadataFilter.parse(
                    List.of("source:eq:O'Reilly: audit.pdf", "page:eq:2"), "OR")))
                    .extracting(ChunkSearchRepository.Chunk::docId).containsExactly("a", "b");
            assertThat(repository.search("audit", MetadataFilter.parse(
                    List.of("page:gt:2"), "AND")))
                    .extracting(ChunkSearchRepository.Chunk::docId).containsExactly("a");
            assertThat(repository.search("audit", MetadataFilter.parse(
                    List.of("source:eq:O'Reilly: audit.pdf", "page:eq:2"), null))).isEmpty();
        } finally {
            dataSource.destroy();
        }
    }

    private static void insert(JdbcTemplate jdbc, String id, String content, String metadata) {
        jdbc.update("INSERT INTO vector_store (id, content, metadata) VALUES (?, ?, CAST(? AS json))",
                id, content, metadata);
    }
}

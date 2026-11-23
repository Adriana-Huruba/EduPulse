package com.edupulse.ingeststatic.ingest;

import java.util.List;

/** Result of a directory ingestion: one entry per PDF plus totals. */
public record BatchIngestionResult(
        String directory,
        int total,
        int ingested,
        int updated,
        int skipped,
        int failed,
        long durationMs,
        List<IngestionResult> results) {

    public static BatchIngestionResult of(String directory, List<IngestionResult> results, long durationMs) {
        return new BatchIngestionResult(
                directory,
                results.size(),
                count(results, IngestionStatus.INGESTED),
                count(results, IngestionStatus.UPDATED),
                count(results, IngestionStatus.SKIPPED_UNCHANGED),
                count(results, IngestionStatus.FAILED),
                durationMs,
                List.copyOf(results));
    }

    private static int count(List<IngestionResult> results, IngestionStatus status) {
        return (int) results.stream().filter(r -> r.status() == status).count();
    }
}

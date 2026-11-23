package com.edupulse.ingeststatic.ingest;

/**
 * Result of ingesting one PDF.
 *
 * @param docId      stable document id
 * @param source     normalised source path
 * @param status     what happened
 * @param version    version stored (or kept, when skipped)
 * @param checksum   SHA-256 of the file
 * @param pages      number of pages parsed (0 when skipped or failed)
 * @param chunks     number of chunks stored for this version
 * @param durationMs processing time
 * @param message    human readable summary or error
 */
public record IngestionResult(
        String docId,
        String source,
        IngestionStatus status,
        Integer version,
        String checksum,
        int pages,
        int chunks,
        long durationMs,
        String message) {

    public static IngestionResult failed(String source, String message, long durationMs) {
        String docId = null;
        try {
            docId = DocumentIdentity.docId(source);
        } catch (IllegalArgumentException ignored) {
            // invalid source: no id
        }
        return new IngestionResult(docId, source, IngestionStatus.FAILED, null, null, 0, 0, durationMs, message);
    }
}

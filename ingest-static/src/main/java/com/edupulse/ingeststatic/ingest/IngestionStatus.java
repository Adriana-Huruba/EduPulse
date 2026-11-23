package com.edupulse.ingeststatic.ingest;

/** Outcome of ingesting one document. */
public enum IngestionStatus {
    /** First ingestion of this {@code doc_id}. */
    INGESTED,
    /** The file content changed: a new version was stored and the previous one removed. */
    UPDATED,
    /** Same checksum as the stored version: nothing was re-embedded or written. */
    SKIPPED_UNCHANGED,
    /** Only used in batch results: this file failed, the others were still processed. */
    FAILED
}

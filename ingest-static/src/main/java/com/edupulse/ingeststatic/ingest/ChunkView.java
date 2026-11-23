package com.edupulse.ingeststatic.ingest;

/** A stored chunk as returned by the inspection endpoints. */
public record ChunkView(
        String id,
        String docId,
        Integer version,
        String source,
        Integer page,
        Integer pageEnd,
        Integer chunkIndex,
        String section,
        String checksum,
        String embeddingModel,
        int length,
        String text) {
}

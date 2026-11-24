package com.edupulse.retrievalpost.stitch;

import com.edupulse.retrievalpost.model.RetrievedChunk;
import java.util.List;

/**
 * Fetches adjacent chunks by {@code chunk_index} strictly within a single document
 * ({@code doc_id} and optional {@code version}).
 */
public interface NeighborChunkRepository {

	/**
	 * Returns all chunks belonging to {@code docId} (and {@code version} when non-null) whose
	 * {@code chunk_index} lies within {@code [minChunkIndex, maxChunkIndex]} inclusive,
	 * ordered ascending by {@code chunk_index}.
	 */
	List<RetrievedChunk> findNeighbors(String docId, Integer version, int minChunkIndex, int maxChunkIndex);
}

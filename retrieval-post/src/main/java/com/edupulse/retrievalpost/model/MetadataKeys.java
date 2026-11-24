package com.edupulse.retrievalpost.model;

/**
 * Shared chunk metadata keys produced by {@code ingest-static} and {@code ingest-dynamic}
 * and consumed across Story 4 post-retrieval stages.
 */
public final class MetadataKeys {

	public static final String DOC_ID = "doc_id";
	public static final String VERSION = "version";
	public static final String SOURCE = "source";
	public static final String PAGE = "page";
	public static final String CHUNK_INDEX = "chunk_index";
	public static final String CHECKSUM = "checksum";

	public static final String RERANK_SCORE = "rerank_score";
	public static final String ORIGINAL_RANK = "original_rank";

	private MetadataKeys() {
	}
}

package com.edupulse.ingestdynamic.ingest;

/**
 * Metadata keys attached to every chunk stored in the vector store.
 *
 * The first six are the shared schema from Story 1 and must stay identical across all
 * services, because retrieval filters on them. The last two are specific to Story 2.
 */
public final class MetadataKeys {

	public static final String DOC_ID = "doc_id";
	public static final String VERSION = "version";
	public static final String SOURCE = "source";
	public static final String PAGE = "page";
	public static final String CHUNK_INDEX = "chunk_index";
	public static final String CHECKSUM = "checksum";

	/** Story 2: the URL the content was fetched from. */
	public static final String SOURCE_URL = "source_url";
	/** Story 2: ISO-8601 instant at which the content was fetched. */
	public static final String FETCH_DATE = "fetch_date";

	private MetadataKeys() {
	}

}

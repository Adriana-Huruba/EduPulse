package com.edupulse.ingestdynamic.fetch;

/**
 * The single place where ingestion makes outbound HTTP requests. Keeping it behind an
 * interface lets Task 4 harden every fetch in one spot and lets tests swap it.
 */
public interface SourceFetcher {

	/**
	 * Downloads the resource at {@code url}.
	 *
	 * @throws FetchException if the resource cannot be fetched
	 */
	FetchedResource fetch(String url);

}

package com.edupulse.ingestdynamic.ingest;

/**
 * Outcome for one requested source.
 *
 * @param url the requested URL
 * @param status OK, PARTIAL (some files of a repository failed) or FAILED
 * @param documents number of documents stored (a repository can yield several)
 * @param chunks number of chunks stored
 * @param error what went wrong, or null
 */
public record SourceResult(String url, Status status, int documents, int chunks, String error) {

	public enum Status {
		OK, PARTIAL, FAILED
	}

	public static SourceResult failed(String url, String error) {
		return new SourceResult(url, Status.FAILED, 0, 0, error);
	}

}

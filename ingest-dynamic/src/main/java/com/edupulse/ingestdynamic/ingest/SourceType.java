package com.edupulse.ingestdynamic.ingest;

/** How a requested source should be treated. {@code AUTO} decides from the response. */
public enum SourceType {

	AUTO,
	HTML,
	PDF,
	TEXT,
	/** A GitHub repository URL: its README and docs files are ingested. */
	GITHUB_REPO

}

package com.edupulse.ingestdynamic.ingest;

import java.util.List;

/** Result of a batch: one entry per requested source, in request order. */
public record BatchResult(int total, int ok, int partial, int failed, List<SourceResult> results) {

	public static BatchResult of(List<SourceResult> results) {
		int ok = count(results, SourceResult.Status.OK);
		int partial = count(results, SourceResult.Status.PARTIAL);
		int failed = count(results, SourceResult.Status.FAILED);
		return new BatchResult(results.size(), ok, partial, failed, List.copyOf(results));
	}

	private static int count(List<SourceResult> results, SourceResult.Status status) {
		return (int) results.stream().filter(r -> r.status() == status).count();
	}

}

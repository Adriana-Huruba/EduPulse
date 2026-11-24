package com.edupulse.retrievalpost.grounding;

/**
 * Structured citation entry corresponding to a formatted context block header.
 */
public record Citation(
		int index,
		String docId,
		String source,
		int version,
		String page,
		Double score,
		String header,
		String snippet) {
}

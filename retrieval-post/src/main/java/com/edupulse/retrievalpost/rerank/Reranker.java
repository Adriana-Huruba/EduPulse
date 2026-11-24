package com.edupulse.retrievalpost.rerank;

import java.util.List;

/**
 * Cross-encoder reranker that scores (query, passage) pairs jointly.
 */
public interface Reranker {

	/**
	 * Scores each passage against the query, returning one relevance score per passage
	 * in the same order as {@code passages}.
	 *
	 * @param query user query
	 * @param passages candidate passage texts
	 * @return relevance scores aligned by index with {@code passages}
	 * @throws RerankerException if scoring fails
	 */
	double[] score(String query, List<String> passages);

	/**
	 * Human-readable pinned model identifier (model ID + revision).
	 */
	String getModelDescriptor();
}

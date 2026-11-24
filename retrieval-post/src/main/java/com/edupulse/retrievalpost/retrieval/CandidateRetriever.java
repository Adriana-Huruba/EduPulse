package com.edupulse.retrievalpost.retrieval;

import com.edupulse.retrievalpost.model.RetrievedChunk;
import java.util.List;

/**
 * Retrieves an initial wider candidate set (top-N) before post-retrieval reranking.
 */
public interface CandidateRetriever {

	List<RetrievedChunk> retrieve(String query, int topN, String filterExpression);
}

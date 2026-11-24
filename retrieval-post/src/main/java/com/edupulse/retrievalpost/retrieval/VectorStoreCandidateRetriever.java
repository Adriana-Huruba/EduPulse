package com.edupulse.retrievalpost.retrieval;

import com.edupulse.retrievalpost.model.RetrievedChunk;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

/**
 * Spring AI {@link VectorStore}-backed candidate retriever fetching a wider top-N candidate pool.
 */
@Component
public class VectorStoreCandidateRetriever implements CandidateRetriever {

	private static final Logger log = LoggerFactory.getLogger(VectorStoreCandidateRetriever.class);

	private final VectorStore vectorStore;

	public VectorStoreCandidateRetriever(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
	}

	@Override
	public List<RetrievedChunk> retrieve(String query, int topN, String filterExpression) {
		SearchRequest.Builder builder = SearchRequest.builder()
				.query(query)
				.topK(topN);
		if (filterExpression != null && !filterExpression.isBlank()) {
			builder.filterExpression(filterExpression);
		}
		List<Document> docs = vectorStore.similaritySearch(builder.build());
		if (docs == null || docs.isEmpty()) {
			log.info("Vector store returned 0 candidates for topN={}", topN);
			return List.of();
		}
		List<RetrievedChunk> chunks = new ArrayList<>(docs.size());
		for (int i = 0; i < docs.size(); i++) {
			chunks.add(RetrievedChunk.fromDocument(docs.get(i), i + 1));
		}
		log.debug("Vector store returned {} candidates (requested topN={})", chunks.size(), topN);
		return chunks;
	}
}

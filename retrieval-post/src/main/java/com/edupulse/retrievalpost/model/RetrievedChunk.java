package com.edupulse.retrievalpost.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Represents a candidate chunk retrieved from the vector store and optionally scored by the reranker.
 */
public record RetrievedChunk(
		String id,
		String content,
		String docId,
		Integer version,
		String source,
		Integer page,
		Integer chunkIndex,
		Double vectorScore,
		Double rerankScore,
		int originalRank,
		Map<String, Object> metadata) {

	public RetrievedChunk {
		metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
	}

	public static RetrievedChunk fromDocument(Document document, int originalRank) {
		Map<String, Object> meta = document.getMetadata() == null ? Map.of() : document.getMetadata();
		Double score = document.getScore();
		if (score == null && meta.get("distance") instanceof Number distance) {
			score = 1.0 - distance.doubleValue();
		}
		return new RetrievedChunk(
				document.getId(),
				document.getText() == null ? "" : document.getText(),
				asString(meta.get(MetadataKeys.DOC_ID)),
				asInteger(meta.get(MetadataKeys.VERSION)),
				asString(meta.get(MetadataKeys.SOURCE)),
				asInteger(meta.get(MetadataKeys.PAGE)),
				asInteger(meta.get(MetadataKeys.CHUNK_INDEX)),
				score,
				null,
				originalRank,
				meta);
	}

	public RetrievedChunk withRerankScore(double newRerankScore) {
		Map<String, Object> enriched = new LinkedHashMap<>(metadata);
		enriched.put(MetadataKeys.RERANK_SCORE, newRerankScore);
		enriched.put(MetadataKeys.ORIGINAL_RANK, originalRank);
		return new RetrievedChunk(
				id, content, docId, version, source, page, chunkIndex,
				vectorScore, newRerankScore, originalRank, enriched);
	}

	private static String asString(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static Integer asInteger(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.parseInt(value.toString().trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}
}

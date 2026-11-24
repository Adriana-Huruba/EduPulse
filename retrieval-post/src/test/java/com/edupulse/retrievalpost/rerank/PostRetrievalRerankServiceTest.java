package com.edupulse.retrievalpost.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.edupulse.retrievalpost.config.RerankProperties;
import com.edupulse.retrievalpost.model.MetadataKeys;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import com.edupulse.retrievalpost.retrieval.CandidateRetriever;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostRetrievalRerankServiceTest {

	private RerankProperties properties;
	private List<RetrievedChunk> twentyCandidates;

	@BeforeEach
	void setUp() {
		properties = new RerankProperties();
		properties.setEnabled(true);
		properties.setTopN(20);
		properties.setTopK(5);

		twentyCandidates = new ArrayList<>();
		for (int i = 1; i <= 20; i++) {
			twentyCandidates.add(new RetrievedChunk(
					"chunk-" + i,
					"Compliance passage number " + i,
					"doc-1",
					1,
					"policy.pdf",
					i,
					i - 1,
					1.0 - (i * 0.02),
					null,
					i,
					Map.of(MetadataKeys.DOC_ID, "doc-1", MetadataKeys.CHUNK_INDEX, i - 1)));
		}
	}

	@Test
	void retrievesWiderTopNAndReranksDownToTopK_successPath() {
		AtomicInteger capturedTopN = new AtomicInteger();
		CandidateRetriever retriever = (query, topN, filter) -> {
			capturedTopN.set(topN);
			return twentyCandidates.subList(0, Math.min(topN, twentyCandidates.size()));
		};
		Reranker reranker = new Reranker() {
			@Override
			public double[] score(String query, List<String> passages) {
				double[] scores = new double[passages.size()];
				// Promote chunk-18, chunk-12, chunk-7, chunk-19, chunk-3 to the top 5
				scores[17] = 0.99;
				scores[11] = 0.95;
				scores[6] = 0.91;
				scores[18] = 0.88;
				scores[2] = 0.84;
				return scores;
			}

			@Override
			public String getModelDescriptor() {
				return "test-cross-encoder@v1";
			}
		};

		PostRetrievalRerankService service = new PostRetrievalRerankService(retriever, reranker, properties);
		RerankOutcome outcome = service.retrieveAndRerank("breach SLA", null, null, null, null);

		assertThat(capturedTopN.get()).isEqualTo(20);
		assertThat(outcome.retrievedCount()).isEqualTo(20);
		assertThat(outcome.returnedCount()).isEqualTo(5);
		assertThat(outcome.reranked()).isTrue();
		assertThat(outcome.fallbackUsed()).isFalse();
		assertThat(outcome.fallbackReason()).isNull();
		assertThat(outcome.model()).isEqualTo("test-cross-encoder@v1");
		assertThat(outcome.chunks())
				.extracting(RetrievedChunk::id)
				.containsExactly("chunk-18", "chunk-12", "chunk-7", "chunk-19", "chunk-3");
		assertThat(outcome.chunks().get(0).rerankScore()).isEqualTo(0.99);
		assertThat(outcome.chunks().get(0).metadata()).containsEntry(MetadataKeys.RERANK_SCORE, 0.99);
		assertThat(outcome.chunks().get(0).metadata()).containsEntry(MetadataKeys.ORIGINAL_RANK, 18);
	}

	@Test
	void keepsOriginalOrderWhenRerankerThrows_failurePath() {
		CandidateRetriever retriever = (query, topN, filter) -> twentyCandidates;
		Reranker failingReranker = new Reranker() {
			@Override
			public double[] score(String query, List<String> passages) {
				throw new RerankerException("ONNX runtime session crashed");
			}

			@Override
			public String getModelDescriptor() {
				return "broken-model@sha";
			}
		};

		PostRetrievalRerankService service = new PostRetrievalRerankService(retriever, failingReranker, properties);
		RerankOutcome outcome = service.retrieveAndRerank("breach SLA", 20, 5, null, true);

		assertThat(outcome.reranked()).isFalse();
		assertThat(outcome.fallbackUsed()).isTrue();
		assertThat(outcome.fallbackReason()).contains("ONNX runtime session crashed");
		assertThat(outcome.retrievedCount()).isEqualTo(20);
		assertThat(outcome.returnedCount()).isEqualTo(5);
		assertThat(outcome.chunks())
				.extracting(RetrievedChunk::id)
				.containsExactly("chunk-1", "chunk-2", "chunk-3", "chunk-4", "chunk-5");
		assertThat(outcome.chunks()).allMatch(c -> c.rerankScore() == null);
	}

	@Test
	void keepsOriginalOrderWhenRerankerReturnsNonFiniteScore_failurePath() {
		CandidateRetriever retriever = (query, topN, filter) -> twentyCandidates.subList(0, 10);
		Reranker nanReranker = new Reranker() {
			@Override
			public double[] score(String query, List<String> passages) {
				double[] scores = new double[passages.size()];
				scores[3] = Double.NaN;
				return scores;
			}

			@Override
			public String getModelDescriptor() {
				return "nan-model@sha";
			}
		};

		PostRetrievalRerankService service = new PostRetrievalRerankService(retriever, nanReranker, properties);
		RerankOutcome outcome = service.retrieveAndRerank("query", 10, 3, null, true);

		assertThat(outcome.reranked()).isFalse();
		assertThat(outcome.fallbackUsed()).isTrue();
		assertThat(outcome.fallbackReason()).contains("non-finite");
		assertThat(outcome.chunks())
				.extracting(RetrievedChunk::id)
				.containsExactly("chunk-1", "chunk-2", "chunk-3");
	}

	@Test
	void skipsRerankerWhenDisabled() {
		CandidateRetriever retriever = (query, topN, filter) -> twentyCandidates;
		Reranker shouldNotBeCalled = new Reranker() {
			@Override
			public double[] score(String query, List<String> passages) {
				throw new AssertionError("Reranker must not be invoked when disabled");
			}

			@Override
			public String getModelDescriptor() {
				return "unused";
			}
		};

		PostRetrievalRerankService service = new PostRetrievalRerankService(retriever, shouldNotBeCalled, properties);
		RerankOutcome outcome = service.retrieveAndRerank("query", 20, 4, null, false);

		assertThat(outcome.reranked()).isFalse();
		assertThat(outcome.fallbackUsed()).isFalse();
		assertThat(outcome.chunks())
				.extracting(RetrievedChunk::id)
				.containsExactly("chunk-1", "chunk-2", "chunk-3", "chunk-4");
	}

	@Test
	void clampsTopNToAtLeastTopKAndHandlesEmptyResults() {
		AtomicInteger capturedTopN = new AtomicInteger();
		CandidateRetriever emptyRetriever = (query, topN, filter) -> {
			capturedTopN.set(topN);
			return List.of();
		};
		Reranker noop = new Reranker() {
			@Override
			public double[] score(String query, List<String> passages) {
				return new double[0];
			}

			@Override
			public String getModelDescriptor() {
				return "noop";
			}
		};

		PostRetrievalRerankService service = new PostRetrievalRerankService(emptyRetriever, noop, properties);
		RerankOutcome outcome = service.retrieveAndRerank("query", 2, 5, null, true);

		assertThat(capturedTopN.get()).isEqualTo(5);
		assertThat(outcome.chunks()).isEmpty();
		assertThat(outcome.retrievedCount()).isZero();
		assertThat(outcome.returnedCount()).isZero();
	}
}

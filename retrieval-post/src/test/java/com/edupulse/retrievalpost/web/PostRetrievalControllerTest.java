package com.edupulse.retrievalpost.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edupulse.retrievalpost.config.GroundingProperties;
import com.edupulse.retrievalpost.config.RerankProperties;
import com.edupulse.retrievalpost.config.StitchProperties;
import com.edupulse.retrievalpost.grounding.Citation;
import com.edupulse.retrievalpost.grounding.GroundedAnswerResponse;
import com.edupulse.retrievalpost.grounding.GroundedAnswerService;
import com.edupulse.retrievalpost.grounding.GroundingException;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import com.edupulse.retrievalpost.rerank.PostRetrievalRerankService;
import com.edupulse.retrievalpost.rerank.RerankOutcome;
import com.edupulse.retrievalpost.stitch.NeighborStitchingService;
import com.edupulse.retrievalpost.stitch.StitchOutcome;
import com.edupulse.retrievalpost.stitch.StitchedBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PostRetrievalControllerTest {

	@Mock
	private PostRetrievalRerankService rerankService;

	@Mock
	private NeighborStitchingService stitchingService;

	@Mock
	private GroundedAnswerService groundingService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		RerankProperties rerankProperties = new RerankProperties();
		StitchProperties stitchProperties = new StitchProperties();
		GroundingProperties groundingProperties = new GroundingProperties();
		PostRetrievalController controller = new PostRetrievalController(
				rerankService, stitchingService, groundingService,
				rerankProperties, stitchProperties, groundingProperties);
		this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void retrieveEndpointReturnsRerankedOutcome() throws Exception {
		RetrievedChunk chunk = new RetrievedChunk(
				"iso27001-c3", "Breach notification within 72 hours.", "doc-iso", 1,
				"cybersecurity-policy-iso27001.pdf", 4, 3, 0.72, 0.96, 4, Map.of());
		RerankOutcome outcome = new RerankOutcome(
				List.of(chunk), 20, 1, true, false, null,
				"cross-encoder/ms-marco-MiniLM-L6-v2@233902d25c440f23af6f7d6e94d2946bac0bee0a (onnx/model.onnx)",
				12L, 45L);

		when(rerankService.retrieveAndRerank(eq("breach notification window"), eq(20), eq(5), eq(null), eq(true)))
				.thenReturn(outcome);

		mockMvc.perform(post("/api/v1/retrieval/retrieve")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "query": "breach notification window",
								  "topN": 20,
								  "topK": 5,
								  "rerank": true
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reranked").value(true))
				.andExpect(jsonPath("$.fallbackUsed").value(false))
				.andExpect(jsonPath("$.retrievedCount").value(20))
				.andExpect(jsonPath("$.chunks[0].id").value("iso27001-c3"))
				.andExpect(jsonPath("$.chunks[0].rerankScore").value(0.96));
	}

	@Test
	void retrieveEndpointRejectsBlankQuery() throws Exception {
		mockMvc.perform(post("/api/v1/retrieval/retrieve")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "query": "   "
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation failed"));
	}

	@Test
	void rerankOnlyEndpointReranksSuppliedCandidates() throws Exception {
		RetrievedChunk chunk = new RetrievedChunk(
				"c2", "AES-256-GCM encryption at rest", "doc-1", 1, "policy.pdf", 3, 2, 0.5, 0.93, 2, Map.of());
		RerankOutcome outcome = new RerankOutcome(
				List.of(chunk), 2, 1, true, false, null, "pinned-model", 0L, 18L);

		when(rerankService.rerankCandidates(eq("encryption at rest"), any(), anyInt(), anyBoolean()))
				.thenReturn(outcome);

		mockMvc.perform(post("/api/v1/retrieval/rerank")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "query": "encryption at rest",
								  "topK": 1,
								  "candidates": [
								    { "id": "c1", "content": "Unrelated physical badge entry" },
								    { "id": "c2", "content": "AES-256-GCM encryption at rest" }
								  ]
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.chunks[0].id").value("c2"))
				.andExpect(jsonPath("$.reranked").value(true));
	}

	@Test
	void stitchEndpointReturnsStitchedBlocks() throws Exception {
		StitchedBlock block = new StitchedBlock(
				"doc-1", 1, "iso.pdf", 1, 2, 0, 1, List.of(0, 1),
				"Merged content without overlap.", 12, 0.95);
		StitchOutcome outcome = new StitchOutcome(
				List.of(block), 1, 1, 12, 2000, false, false, null, 2L);

		when(stitchingService.stitch(any(), eq(1), eq(1), eq(2000), any())).thenReturn(outcome);

		mockMvc.perform(post("/api/v1/retrieval/stitch")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "anchors": [
								    { "id": "c0", "docId": "doc-1", "version": 1, "source": "iso.pdf", "page": 1, "chunkIndex": 0, "content": "Part one." }
								  ],
								  "neighborPool": [
								    { "id": "c0", "docId": "doc-1", "version": 1, "source": "iso.pdf", "page": 1, "chunkIndex": 0, "content": "Part one." },
								    { "id": "c1", "docId": "doc-1", "version": 1, "source": "iso.pdf", "page": 2, "chunkIndex": 1, "content": "Part two." }
								  ],
								  "windowBefore": 1,
								  "windowAfter": 1,
								  "tokenBudget": 2000
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outputBlockCount").value(1))
				.andExpect(jsonPath("$.blocks[0].docId").value("doc-1"))
				.andExpect(jsonPath("$.blocks[0].mergedChunkIndices[0]").value(0))
				.andExpect(jsonPath("$.blocks[0].mergedChunkIndices[1]").value(1));
	}

	@Test
	void stitchEndpointRejectsEmptyAnchors() throws Exception {
		mockMvc.perform(post("/api/v1/retrieval/stitch")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "anchors": []
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation failed"));
	}

	@Test
	void retrieveAndStitchEndpointCombinesTask1AndTask2() throws Exception {
		RetrievedChunk chunk = new RetrievedChunk(
				"c1", "Breach SLA 72h.", "doc-1", 1, "iso.pdf", 4, 3, 0.8, 0.98, 2, Map.of());
		RerankOutcome rerankOutcome = new RerankOutcome(
				List.of(chunk), 20, 1, true, false, null, "pinned-model", 10L, 120L);
		StitchedBlock block = new StitchedBlock(
				"doc-1", 1, "iso.pdf", 3, 5, 2, 4, List.of(2, 3, 4),
				"Context before. Breach SLA 72h. Context after.", 25, 0.98);
		StitchOutcome stitchOutcome = new StitchOutcome(
				List.of(block), 1, 1, 25, 2000, false, false, null, 3L);

		when(rerankService.retrieveAndRerank(eq("breach SLA"), eq(20), eq(5), eq(null), eq(true)))
				.thenReturn(rerankOutcome);
		when(stitchingService.stitch(eq(List.of(chunk)), eq(1), eq(1), eq(2000)))
				.thenReturn(stitchOutcome);

		mockMvc.perform(post("/api/v1/retrieval/retrieve-and-stitch")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "query": "breach SLA",
								  "topN": 20,
								  "topK": 5,
								  "rerank": true,
								  "windowBefore": 1,
								  "windowAfter": 1,
								  "tokenBudget": 2000
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rerank.reranked").value(true))
				.andExpect(jsonPath("$.stitch.outputBlockCount").value(1))
				.andExpect(jsonPath("$.stitch.totalEstimatedTokens").value(25));
	}

	@Test
	void groundEndpointReturnsCitedResponse() throws Exception {
		Citation citation = new Citation(
				1, "doc-iso", "cybersecurity-policy-iso27001.pdf", 1, "4", 0.96,
				"[SOURCE 1 | source: cybersecurity-policy-iso27001.pdf | version: 1 | page: 4]",
				"Breach SLA 72 hours.");
		GroundedAnswerResponse resp = new GroundedAnswerResponse(
				"Breach SLA?",
				"Report within 72 hours [SOURCE 1].",
				false, null, 0.96, 0.5, List.of(citation),
				"[SOURCE 1 | source: cybersecurity-policy-iso27001.pdf | version: 1 | page: 4]\nBreach SLA 72 hours.",
				"system", "user", 5L);

		when(groundingService.groundAndAnswer(eq("Breach SLA?"), any(), eq(0.5), eq(true))).thenReturn(resp);

		mockMvc.perform(post("/api/v1/retrieval/ground")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "query": "Breach SLA?",
								  "similarityThreshold": 0.5,
								  "generate": true,
								  "blocks": []
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.refused").value(false))
				.andExpect(jsonPath("$.citations[0].source").value("cybersecurity-policy-iso27001.pdf"))
				.andExpect(jsonPath("$.citations[0].version").value(1))
				.andExpect(jsonPath("$.citations[0].page").value("4"));
	}

	@Test
	void groundEndpointReturns503WhenLlmUnavailable() throws Exception {
		when(groundingService.groundAndAnswer(eq("Breach SLA?"), any(), eq(0.5), eq(true)))
				.thenThrow(new GroundingException("Ollama unreachable"));

		mockMvc.perform(post("/api/v1/retrieval/ground")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "query": "Breach SLA?",
								  "similarityThreshold": 0.5,
								  "generate": true,
								  "blocks": []
								}
								"""))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error").value("Ollama unreachable"));
	}

	@Test
	void configEndpointExposesPinnedModelStitchAndGroundingDefaults() throws Exception {
		mockMvc.perform(get("/api/v1/retrieval/config"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.topN").value(20))
				.andExpect(jsonPath("$.topK").value(5))
				.andExpect(jsonPath("$.modelId").value("cross-encoder/ms-marco-MiniLM-L6-v2"))
				.andExpect(jsonPath("$.modelRevision").value("233902d25c440f23af6f7d6e94d2946bac0bee0a"))
				.andExpect(jsonPath("$.stitch.enabled").value(true))
				.andExpect(jsonPath("$.stitch.maxTokenBudget").value(2000))
				.andExpect(jsonPath("$.grounding.similarityThreshold").value(0.5))
				.andExpect(jsonPath("$.grounding.refusalMessage").value("I don't know."));
	}
}

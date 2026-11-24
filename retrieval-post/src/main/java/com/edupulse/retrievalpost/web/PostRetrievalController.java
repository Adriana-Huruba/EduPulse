package com.edupulse.retrievalpost.web;

import com.edupulse.retrievalpost.config.GroundingProperties;
import com.edupulse.retrievalpost.config.RerankProperties;
import com.edupulse.retrievalpost.config.StitchProperties;
import com.edupulse.retrievalpost.grounding.GroundedAnswerResponse;
import com.edupulse.retrievalpost.grounding.GroundedAnswerService;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import com.edupulse.retrievalpost.rerank.PostRetrievalRerankService;
import com.edupulse.retrievalpost.rerank.RerankOutcome;
import com.edupulse.retrievalpost.stitch.NeighborChunkRepository;
import com.edupulse.retrievalpost.stitch.NeighborStitchingService;
import com.edupulse.retrievalpost.stitch.StitchOutcome;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Story 4 post-retrieval reranking, neighbor stitching, grounding, and configuration.
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class PostRetrievalController {

	private final PostRetrievalRerankService rerankService;
	private final NeighborStitchingService stitchingService;
	private final GroundedAnswerService groundingService;
	private final RerankProperties rerankProperties;
	private final StitchProperties stitchProperties;
	private final GroundingProperties groundingProperties;

	public PostRetrievalController(
			PostRetrievalRerankService rerankService,
			NeighborStitchingService stitchingService,
			GroundedAnswerService groundingService,
			RerankProperties rerankProperties,
			StitchProperties stitchProperties,
			GroundingProperties groundingProperties) {
		this.rerankService = rerankService;
		this.stitchingService = stitchingService;
		this.groundingService = groundingService;
		this.rerankProperties = rerankProperties;
		this.stitchProperties = stitchProperties;
		this.groundingProperties = groundingProperties;
	}

	@PostMapping("/retrieve")
	public RerankOutcome retrieve(@Valid @RequestBody RetrieveRequest request) {
		return rerankService.retrieveAndRerank(
				request.query(),
				request.topN(),
				request.topK(),
				request.filterExpression(),
				request.rerank());
	}

	@PostMapping("/rerank")
	public RerankOutcome rerank(@Valid @RequestBody RerankOnlyRequest request) {
		List<RetrievedChunk> chunks = toRetrievedChunks(request.candidates());
		int topK = request.topK() != null ? request.topK() : rerankProperties.getTopK();
		boolean shouldRerank = request.rerank() != null ? request.rerank() : rerankProperties.isEnabled();
		return rerankService.rerankCandidates(request.query(), chunks, topK, shouldRerank);
	}

	@PostMapping("/stitch")
	public StitchOutcome stitch(@Valid @RequestBody StitchRequest request) {
		List<RetrievedChunk> anchors = toRetrievedChunks(request.anchors());
		if (request.neighborPool() != null && !request.neighborPool().isEmpty()) {
			List<RetrievedChunk> pool = toRetrievedChunks(request.neighborPool());
			NeighborChunkRepository inlineRepo = (docId, version, minIdx, maxIdx) -> pool.stream()
					.filter(c -> Objects.equals(c.docId(), docId))
					.filter(c -> version == null || Objects.equals(c.version(), version))
					.filter(c -> c.chunkIndex() != null && c.chunkIndex() >= minIdx && c.chunkIndex() <= maxIdx)
					.sorted(Comparator.comparingInt(RetrievedChunk::chunkIndex))
					.toList();
			return stitchingService.stitch(
					anchors, request.windowBefore(), request.windowAfter(), request.tokenBudget(), inlineRepo);
		}
		return stitchingService.stitch(
				anchors, request.windowBefore(), request.windowAfter(), request.tokenBudget());
	}

	@PostMapping("/retrieve-and-stitch")
	public RetrieveAndStitchResponse retrieveAndStitch(@Valid @RequestBody RetrieveAndStitchRequest request) {
		RerankOutcome rerank = rerankService.retrieveAndRerank(
				request.query(),
				request.topN(),
				request.topK(),
				request.filterExpression(),
				request.rerank());
		StitchOutcome stitch = stitchingService.stitch(
				rerank.chunks(),
				request.windowBefore(),
				request.windowAfter(),
				request.tokenBudget());
		return new RetrieveAndStitchResponse(rerank, stitch);
	}

	@PostMapping("/ground")
	public GroundedAnswerResponse ground(@Valid @RequestBody GroundRequest request) {
		return groundingService.groundAndAnswer(
				request.query(),
				request.blocks(),
				request.similarityThreshold(),
				request.generate());
	}

	@PostMapping("/answer")
	public FullAnswerResponse answer(@Valid @RequestBody FullAnswerRequest request) {
		RerankOutcome rerank = rerankService.retrieveAndRerank(
				request.query(),
				request.topN(),
				request.topK(),
				request.filterExpression(),
				request.rerank());
		StitchOutcome stitch = stitchingService.stitch(
				rerank.chunks(),
				request.windowBefore(),
				request.windowAfter(),
				request.tokenBudget());
		GroundedAnswerResponse grounding = groundingService.groundAndAnswer(
				request.query(),
				stitch.blocks(),
				request.similarityThreshold(),
				request.generate());
		return new FullAnswerResponse(rerank, stitch, grounding);
	}

	@GetMapping("/config")
	public Map<String, Object> config() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("enabled", rerankProperties.isEnabled());
		body.put("topN", rerankProperties.getTopN());
		body.put("topK", rerankProperties.getTopK());
		body.put("modelId", rerankProperties.getModelId());
		body.put("modelRevision", rerankProperties.getModelRevision());
		body.put("onnxFile", rerankProperties.getOnnxFile());
		body.put("onnxSha256", rerankProperties.getOnnxSha256());
		body.put("maxSequenceLength", rerankProperties.getMaxSequenceLength());
		body.put("similarityThreshold", groundingProperties.getSimilarityThreshold());

		Map<String, Object> stitch = new LinkedHashMap<>();
		stitch.put("enabled", stitchProperties.isEnabled());
		stitch.put("windowBefore", stitchProperties.getWindowBefore());
		stitch.put("windowAfter", stitchProperties.getWindowAfter());
		stitch.put("maxTokenBudget", stitchProperties.getMaxTokenBudget());
		stitch.put("minOverlapChars", stitchProperties.getMinOverlapChars());
		stitch.put("maxOverlapChars", stitchProperties.getMaxOverlapChars());
		body.put("stitch", stitch);

		Map<String, Object> grounding = new LinkedHashMap<>();
		grounding.put("similarityThreshold", groundingProperties.getSimilarityThreshold());
		grounding.put("refusalMessage", groundingProperties.getRefusalMessage());
		grounding.put("chatModel", groundingProperties.getChatModel());
		grounding.put("chatEnabled", groundingProperties.isChatEnabled());
		body.put("grounding", grounding);

		return body;
	}

	private static List<RetrievedChunk> toRetrievedChunks(List<RerankOnlyRequest.CandidateInput> inputs) {
		List<RetrievedChunk> chunks = new ArrayList<>(inputs.size());
		for (int i = 0; i < inputs.size(); i++) {
			RerankOnlyRequest.CandidateInput input = inputs.get(i);
			chunks.add(new RetrievedChunk(
					input.id() != null ? input.id() : "candidate-" + (i + 1),
					input.content(),
					input.docId(),
					input.version(),
					input.source(),
					input.page(),
					input.chunkIndex(),
					input.vectorScore(),
					null,
					i + 1,
					input.metadata() == null ? Map.of() : input.metadata()));
		}
		return chunks;
	}
}

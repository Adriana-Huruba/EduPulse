package com.edupulse.retrievalpost.stitch;

import com.edupulse.retrievalpost.config.StitchProperties;
import com.edupulse.retrievalpost.model.RetrievedChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates Story 4, Task 2: expands retrieved anchor chunks with adjacent neighbors
 * by {@code chunk_index}, deduplicates overlapping text, enforces a strict token budget,
 * and never crosses document ({@code doc_id}, {@code version}) boundaries.
 */
@Service
public class NeighborStitchingService {

	private static final Logger log = LoggerFactory.getLogger(NeighborStitchingService.class);

	private final NeighborChunkRepository defaultRepository;
	private final TokenEstimator tokenEstimator;
	private final StitchProperties properties;

	public NeighborStitchingService(
			NeighborChunkRepository defaultRepository,
			TokenEstimator tokenEstimator,
			StitchProperties properties) {
		this.defaultRepository = defaultRepository;
		this.tokenEstimator = tokenEstimator;
		this.properties = properties;
	}

	public StitchOutcome stitch(List<RetrievedChunk> anchorChunks) {
		return stitch(anchorChunks, null, null, null, defaultRepository);
	}

	public StitchOutcome stitch(
			List<RetrievedChunk> anchorChunks,
			Integer windowBeforeOverride,
			Integer windowAfterOverride,
			Integer tokenBudgetOverride) {
		return stitch(anchorChunks, windowBeforeOverride, windowAfterOverride, tokenBudgetOverride, defaultRepository);
	}

	public StitchOutcome stitch(
			List<RetrievedChunk> anchorChunks,
			Integer windowBeforeOverride,
			Integer windowAfterOverride,
			Integer tokenBudgetOverride,
			NeighborChunkRepository repositoryOverride) {

		long startNanos = System.nanoTime();
		int tokenBudget = tokenBudgetOverride != null ? tokenBudgetOverride : properties.getMaxTokenBudget();
		int windowBefore = windowBeforeOverride != null ? windowBeforeOverride : properties.getWindowBefore();
		int windowAfter = windowAfterOverride != null ? windowAfterOverride : properties.getWindowAfter();
		NeighborChunkRepository repo = repositoryOverride != null ? repositoryOverride : defaultRepository;

		if (anchorChunks == null || anchorChunks.isEmpty()) {
			return new StitchOutcome(List.of(), 0, 0, 0, tokenBudget, false, false, null, 0L);
		}

		OverlapDeduplicator deduplicator = new OverlapDeduplicator(
				properties.getMinOverlapChars(), properties.getMaxOverlapChars());

		// 1. Collect per-document anchor windows and fetch neighbors strictly within each (docId, version).
		Map<DocVersionKey, List<AnchorWindow>> windowsByDoc = new LinkedHashMap<>();
		List<StandaloneAnchor> standaloneAnchors = new ArrayList<>();
		boolean fallbackUsed = false;
		String fallbackReason = null;

		for (int priority = 0; priority < anchorChunks.size(); priority++) {
			RetrievedChunk anchor = anchorChunks.get(priority);
			if (anchor == null) {
				continue;
			}
			if (anchor.docId() == null || anchor.docId().isBlank() || anchor.chunkIndex() == null) {
				standaloneAnchors.add(new StandaloneAnchor(priority, anchor));
				continue;
			}

			int minIdx = Math.max(0, anchor.chunkIndex() - windowBefore);
			int maxIdx = anchor.chunkIndex() + windowAfter;
			TreeMap<Integer, RetrievedChunk> fetched = new TreeMap<>();
			fetched.put(anchor.chunkIndex(), anchor);

			if (properties.isEnabled() && (windowBefore > 0 || windowAfter > 0)) {
				try {
					List<RetrievedChunk> neighbors = repo.findNeighbors(
							anchor.docId(), anchor.version(), minIdx, maxIdx);
					if (neighbors != null) {
						for (RetrievedChunk neighbor : neighbors) {
							// Defense in depth: strictly enforce document + version boundary.
							if (neighbor == null || neighbor.chunkIndex() == null) {
								continue;
							}
							if (!Objects.equals(neighbor.docId(), anchor.docId())
									|| (anchor.version() != null && !Objects.equals(neighbor.version(), anchor.version()))) {
								log.warn("Ignoring neighbor chunk {} with mismatched docId/version (expected {}:{}, got {}:{})",
										neighbor.id(), anchor.docId(), anchor.version(),
										neighbor.docId(), neighbor.version());
								continue;
							}
							fetched.putIfAbsent(neighbor.chunkIndex(), neighbor);
						}
					}
				}
				catch (RuntimeException ex) {
					fallbackUsed = true;
					fallbackReason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
					log.error("Neighbor lookup failed for docId={} chunkIndex={}; keeping anchor chunk only: {}",
							anchor.docId(), anchor.chunkIndex(), fallbackReason, ex);
				}
			}

			DocVersionKey key = new DocVersionKey(anchor.docId(), anchor.version());
			windowsByDoc.computeIfAbsent(key, k -> new ArrayList<>())
					.add(new AnchorWindow(priority, anchor.chunkIndex(), minIdx, maxIdx, bestScore(anchor), fetched));
		}

		// 2. Coalesce overlapping/adjacent windows within the SAME (docId, version) only.
		List<CandidateGroup> allGroups = new ArrayList<>();
		for (Map.Entry<DocVersionKey, List<AnchorWindow>> entry : windowsByDoc.entrySet()) {
			allGroups.addAll(coalesceDocumentWindows(entry.getKey(), entry.getValue()));
		}
		for (StandaloneAnchor standalone : standaloneAnchors) {
			allGroups.add(CandidateGroup.standalone(standalone.priority(), standalone.chunk()));
		}

		// 3. Process groups in descending relevance (lowest anchor priority index first) under token budget.
		allGroups.sort(Comparator.comparingInt(CandidateGroup::bestPriority));

		List<StitchedBlock> outputBlocks = new ArrayList<>();
		int remainingBudget = tokenBudget;
		int totalTokens = 0;
		boolean budgetTruncated = false;

		for (CandidateGroup group : allGroups) {
			if (remainingBudget <= 0) {
				budgetTruncated = true;
				break;
			}

			BuildResult result = buildBudgetedBlock(group, remainingBudget, outputBlocks.isEmpty(), deduplicator);
			if (result.budgetTruncated()) {
				budgetTruncated = true;
			}
			if (result.block() == null) {
				// Not enough budget for this group's anchor chunks; stop in relevance order.
				budgetTruncated = true;
				break;
			}
			outputBlocks.add(result.block());
			totalTokens += result.block().estimatedTokens();
			remainingBudget -= result.block().estimatedTokens();
		}

		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
		log.info("Stitched {} anchor chunks -> {} blocks (tokens={}/{}, truncated={}, fallback={}) in {} ms",
				anchorChunks.size(), outputBlocks.size(), totalTokens, tokenBudget,
				budgetTruncated, fallbackUsed, elapsedMs);

		return new StitchOutcome(
				outputBlocks,
				anchorChunks.size(),
				outputBlocks.size(),
				totalTokens,
				tokenBudget,
				budgetTruncated,
				fallbackUsed,
				fallbackReason,
				elapsedMs);
	}

	private List<CandidateGroup> coalesceDocumentWindows(DocVersionKey key, List<AnchorWindow> windows) {
		windows.sort(Comparator.comparingInt(AnchorWindow::minIdx).thenComparingInt(AnchorWindow::maxIdx));
		List<CandidateGroup> merged = new ArrayList<>();

		int curMin = -1;
		int curMax = -1;
		int curBestPriority = Integer.MAX_VALUE;
		Double curTopScore = null;
		TreeSet<Integer> curAnchorIndices = new TreeSet<>();
		TreeMap<Integer, RetrievedChunk> curPool = new TreeMap<>();

		for (AnchorWindow w : windows) {
			if (curPool.isEmpty()) {
				curMin = w.minIdx();
				curMax = w.maxIdx();
				curBestPriority = w.priority();
				curTopScore = w.score();
				curAnchorIndices.add(w.anchorIndex());
				curPool.putAll(w.fetchedChunks());
			}
			else if (w.minIdx() <= curMax + 1) {
				curMax = Math.max(curMax, w.maxIdx());
				curBestPriority = Math.min(curBestPriority, w.priority());
				curTopScore = maxScore(curTopScore, w.score());
				curAnchorIndices.add(w.anchorIndex());
				for (Map.Entry<Integer, RetrievedChunk> chunkEntry : w.fetchedChunks().entrySet()) {
					curPool.putIfAbsent(chunkEntry.getKey(), chunkEntry.getValue());
				}
			}
			else {
				merged.add(new CandidateGroup(key, curBestPriority, curTopScore,
						new TreeSet<>(curAnchorIndices), new TreeMap<>(curPool), null));
				curMin = w.minIdx();
				curMax = w.maxIdx();
				curBestPriority = w.priority();
				curTopScore = w.score();
				curAnchorIndices = new TreeSet<>();
				curAnchorIndices.add(w.anchorIndex());
				curPool = new TreeMap<>(w.fetchedChunks());
			}
		}

		if (!curPool.isEmpty()) {
			merged.add(new CandidateGroup(key, curBestPriority, curTopScore, curAnchorIndices, curPool, null));
		}
		return merged;
	}

	private BuildResult buildBudgetedBlock(
			CandidateGroup group,
			int remainingBudget,
			boolean isFirstBlock,
			OverlapDeduplicator deduplicator) {

		if (group.standaloneChunk() != null) {
			RetrievedChunk c = group.standaloneChunk();
			String content = c.content() == null ? "" : c.content().strip();
			int tokens = tokenEstimator.estimateTokens(content);
			boolean truncated = false;
			if (tokens > remainingBudget) {
				if (!isFirstBlock) {
					return new BuildResult(null, true);
				}
				content = tokenEstimator.truncateToBudget(content, remainingBudget);
				tokens = tokenEstimator.estimateTokens(content);
				truncated = true;
			}
			StitchedBlock block = new StitchedBlock(
					c.docId(), c.version(), c.source(), c.page(), c.page(),
					c.chunkIndex(), c.chunkIndex(),
					c.chunkIndex() == null ? List.of() : List.of(c.chunkIndex()),
					content, tokens, bestScore(c));
			return new BuildResult(block, truncated);
		}

		// Start with the anchor chunk(s) in this group.
		TreeMap<Integer, RetrievedChunk> selected = new TreeMap<>();
		for (Integer anchorIdx : group.anchorIndices()) {
			RetrievedChunk chunk = group.chunkPool().get(anchorIdx);
			if (chunk != null) {
				selected.put(anchorIdx, chunk);
			}
		}

		String mergedText = mergeSelected(selected, deduplicator);
		int mergedTokens = tokenEstimator.estimateTokens(mergedText);
		boolean truncated = false;

		if (mergedTokens > remainingBudget) {
			if (!isFirstBlock) {
				return new BuildResult(null, true);
			}
			// Even the top anchor exceeds total budget: trim cleanly so we still return grounded context.
			mergedText = tokenEstimator.truncateToBudget(mergedText, remainingBudget);
			mergedTokens = tokenEstimator.estimateTokens(mergedText);
			truncated = true;
			return new BuildResult(toBlock(group, selected, mergedText, mergedTokens), truncated);
		}

		// Expand outward from the anchor range one neighbor at a time while staying within budget.
		List<Integer> expansionOrder = computeExpansionOrder(group.anchorIndices(), group.chunkPool());
		for (Integer candidateIdx : expansionOrder) {
			RetrievedChunk neighbor = group.chunkPool().get(candidateIdx);
			if (neighbor == null || selected.containsKey(candidateIdx)) {
				continue;
			}
			selected.put(candidateIdx, neighbor);
			String trialText = mergeSelected(selected, deduplicator);
			int trialTokens = tokenEstimator.estimateTokens(trialText);
			if (trialTokens <= remainingBudget) {
				mergedText = trialText;
				mergedTokens = trialTokens;
			}
			else {
				selected.remove(candidateIdx);
				truncated = true;
			}
		}

		return new BuildResult(toBlock(group, selected, mergedText, mergedTokens), truncated);
	}

	private static List<Integer> computeExpansionOrder(
			TreeSet<Integer> anchorIndices,
			TreeMap<Integer, RetrievedChunk> pool) {
		List<Integer> order = new ArrayList<>();
		int minAnchor = anchorIndices.first();
		int maxAnchor = anchorIndices.last();

		// First include any interior bridge chunks between multiple anchors in the same group.
		for (Integer idx : pool.keySet()) {
			if (idx > minAnchor && idx < maxAnchor && !anchorIndices.contains(idx)) {
				order.add(idx);
			}
		}

		// Then alternate outward: minAnchor-1, maxAnchor+1, minAnchor-2, maxAnchor+2, ...
		int maxDistance = Math.max(
				minAnchor - pool.firstKey(),
				pool.lastKey() - maxAnchor);
		for (int d = 1; d <= maxDistance; d++) {
			int left = minAnchor - d;
			if (pool.containsKey(left)) {
				order.add(left);
			}
			int right = maxAnchor + d;
			if (pool.containsKey(right)) {
				order.add(right);
			}
		}
		return order;
	}

	private static String mergeSelected(TreeMap<Integer, RetrievedChunk> selected, OverlapDeduplicator deduplicator) {
		List<String> texts = selected.values().stream()
				.map(RetrievedChunk::content)
				.toList();
		return deduplicator.mergeAll(texts);
	}

	private static StitchedBlock toBlock(
			CandidateGroup group,
			TreeMap<Integer, RetrievedChunk> selected,
			String content,
			int estimatedTokens) {

		List<Integer> indices = new ArrayList<>(selected.keySet());
		Integer startIdx = indices.isEmpty() ? null : indices.get(0);
		Integer endIdx = indices.isEmpty() ? null : indices.get(indices.size() - 1);

		String source = null;
		Integer startPage = null;
		Integer endPage = null;
		for (RetrievedChunk chunk : selected.values()) {
			if (source == null && chunk.source() != null) {
				source = chunk.source();
			}
			if (chunk.page() != null) {
				startPage = startPage == null ? chunk.page() : Math.min(startPage, chunk.page());
				endPage = endPage == null ? chunk.page() : Math.max(endPage, chunk.page());
			}
		}

		return new StitchedBlock(
				group.key().docId(),
				group.key().version(),
				source,
				startPage,
				endPage,
				startIdx,
				endIdx,
				indices,
				content,
				estimatedTokens,
				group.topScore());
	}

	private static Double bestScore(RetrievedChunk chunk) {
		if (chunk.rerankScore() != null) {
			return chunk.rerankScore();
		}
		return chunk.vectorScore();
	}

	private static Double maxScore(Double a, Double b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return Math.max(a, b);
	}

	private record DocVersionKey(String docId, Integer version) {
	}

	private record AnchorWindow(
			int priority,
			int anchorIndex,
			int minIdx,
			int maxIdx,
			Double score,
			TreeMap<Integer, RetrievedChunk> fetchedChunks) {
	}

	private record StandaloneAnchor(int priority, RetrievedChunk chunk) {
	}

	private record CandidateGroup(
			DocVersionKey key,
			int bestPriority,
			Double topScore,
			TreeSet<Integer> anchorIndices,
			TreeMap<Integer, RetrievedChunk> chunkPool,
			RetrievedChunk standaloneChunk) {

		static CandidateGroup standalone(int priority, RetrievedChunk chunk) {
			return new CandidateGroup(null, priority, bestScore(chunk), null, null, chunk);
		}
	}

	private record BuildResult(StitchedBlock block, boolean budgetTruncated) {
	}
}

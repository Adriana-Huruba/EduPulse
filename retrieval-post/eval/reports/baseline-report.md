# Story 4 Evaluation Baseline Report

- **Generated at:** `2026-11-24T11:12:49.572022500Z`
- **Pinned reranker model:** `cross-encoder/ms-marco-MiniLM-L6-v2@233902d25c440f23af6f7d6e94d2946bac0bee0a (onnx/model.onnx)`
- **Retrieval config:** `topN=20`, `topK=5`, `similarityThreshold=0.50`
- **Golden set:** `36` total (`24` answerable, `12` unanswerable)

## Summary Metrics

| Metric | Baseline (No Rerank) | Full Pipeline (Rerank + Stitch + Ground) |
|---|---:|---:|
| **Recall@5** | `1.0000` | **`1.0000`** |
| **MRR@5** | `0.8264` | **`0.9375`** |
| **Recall@1** | `0.6667` | **`0.8750`** |
| **Faithfulness Rate** | — | **`1.0000`** (mean score `1.0000`) |
| **Unanswerable Refusal Accuracy** | — | **`1.0000`** |
| **False Refusal Rate** | — | **`0.0000`** |
| **Added Rerank Latency (CPU)** | `0 ms` | mean **`196.5 ms`**, p50 **`189 ms`**, p95 **`298 ms`** |

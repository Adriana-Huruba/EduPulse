# `retrieval-post` — Story 4: Post-Retrieval and Grounding

Spring Boot microservice (port `8084`, reachable via API Gateway at `/retrieval-post/**`) implementing Story 4 of the EduPulse RAG pipeline.

## Story 4 Task Status

| Task | Description | Status |
|---|---|---|
| Task 1 | Cross-encoder reranker: retrieve wider top-N, rerank down to top-k, pinned model, fallback on failure | **Done** |
| Task 2 | Neighbor stitching using `chunk_index`, overlap deduplication, token budget | **Done** |
| Task 3 | Citation headers (`source`, `version`, `page`), grounded prompt, similarity-threshold "I don't know" fallback | **Done** |
| Task 4 | Evaluation harness: 30+ golden Q&A set, recall@k, MRR, faithfulness check, regression command & baseline report | **Done** |

---

## Task 1: Cross-Encoder Reranker

### How It Works

1. **Wider candidate retrieval (`VectorStoreCandidateRetriever`)**: fetches `topN` candidates (default `20`) from PostgreSQL `pgvector` via Spring AI `VectorStore.similaritySearch(...)`, optionally applying a filter expression.
2. **In-process ONNX cross-encoder (`OnnxCrossEncoderReranker` + `WordPieceTokenizer`)**: scores all `(query, candidate)` pairs jointly using ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime:1.32.0`) and a pure-Java BERT uncased WordPiece tokenizer, then normalizes raw logits via sigmoid into `(0, 1)`.
3. **Truncation & metadata enrichment (`PostRetrievalRerankService`)**: sorts descending by `rerankScore` (tie-breaking by `originalRank`), truncates to `topK` (default `5`), attaches `rerank_score` and `original_rank` to chunk metadata, and logs retrieval/rerank latency and top score.
4. **Resilient fallback**: if the reranker fails for any reason (missing/corrupted artifact, ONNX runtime error, non-finite scores, or mismatched score count), the error is logged and the service returns the top-k candidates in their **original vector-retrieval order** with `fallbackUsed=true` and `fallbackReason` populated.

### Pinned Model Version

| Setting | Pinned Value |
|---|---|
| HuggingFace model ID | `cross-encoder/ms-marco-MiniLM-L6-v2` |
| Pinned commit SHA | `233902d25c440f23af6f7d6e94d2946bac0bee0a` |
| ONNX artifact | `onnx/model.onnx` (FP32, CPU-portable across x86_64 and ARM64) |
| ONNX SHA-256 | `5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a` |
| Vocabulary file | `vocab.txt` (30,522 BERT uncased WordPiece tokens) |
| Runtime | `com.microsoft.onnxruntime:onnxruntime:1.32.0` |

`PinnedModelManager` downloads the pinned revision lazily on first inference to `app.rerank.model-cache-dir` (git-ignored under `models/`) and verifies the SHA-256 digest before loading.

### Configuration (`app.rerank.*`)

No secrets are stored in this repository. All reranker settings are bound to `RerankProperties` via `application.properties`:

| Environment Variable | Property | Default | Description |
|---|---|---|---|
| `APP_RERANK_ENABLED` | `app.rerank.enabled` | `true` | Enable or disable cross-encoder reranking |
| `APP_RERANK_TOP_N` | `app.rerank.top-n` | `20` | Wider first-stage retrieval pool size (`1..200`) |
| `APP_RERANK_TOP_K` | `app.rerank.top-k` | `5` | Final number of reranked chunks returned (`1..100`) |
| `APP_RERANK_MODEL_ID` | `app.rerank.model-id` | `cross-encoder/ms-marco-MiniLM-L6-v2` | Pinned HuggingFace model repository ID |
| `APP_RERANK_MODEL_REVISION` | `app.rerank.model-revision` | `233902d25c440f23af6f7d6e94d2946bac0bee0a` | Pinned HuggingFace commit SHA |
| `APP_RERANK_ONNX_FILE` | `app.rerank.onnx-file` | `onnx/model.onnx` | Relative path to ONNX file inside the repo |
| `APP_RERANK_ONNX_SHA256` | `app.rerank.onnx-sha256` | `5d3e70fd...1d4d4a` | Expected SHA-256 digest of the ONNX file |
| `APP_RERANK_VOCAB_FILE` | `app.rerank.vocab-file` | `vocab.txt` | Relative path to WordPiece vocabulary file |
| `APP_RERANK_MODEL_CACHE_DIR` | `app.rerank.model-cache-dir` | `models/reranker` | Local cache directory for downloaded artifacts |
| `APP_RERANK_AUTO_DOWNLOAD` | `app.rerank.auto-download` | `true` | Auto-download pinned artifacts if not cached |
| `APP_RERANK_MAX_SEQUENCE_LENGTH` | `app.rerank.max-sequence-length` | `512` | Max WordPiece tokens per `(query, passage)` pair |
| `APP_RERANK_DOWNLOAD_TIMEOUT_SECONDS` | `app.rerank.download-timeout-seconds` | `60` | HTTP timeout for model artifact download |

### Measured Quality & Latency (Golden Set Benchmark)

Measured by `OnnxCrossEncoderGoldenSetBenchmarkTest` on the 15-question EduPulse compliance golden set (`src/main/resources/eval/golden-set.json`) over 40 compliance chunks (`src/main/resources/eval/corpus/compliance-chunks.json`), retrieving `topN=20` and reranking down to `topK=5` on CPU:

| Metric | Baseline (No Reranking) | With Cross-Encoder Reranking | Delta |
|---|---:|---:|---:|
| **MRR@5** | `0.7222` | **`0.9000`** | **+0.1778 (+24.6%)** |
| **Recall@1** | `0.4667` | **`0.8000`** | **+0.3333 (+71.4%)** |
| **Recall@5** | `1.0000` | `1.0000` | `0.0000` |

**Added latency (20 candidates -> top 5, steady-state CPU inference):**

| Measurement | Latency |
|---|---:|
| Cold-start ONNX session load (from local disk cache) | `401 ms` |
| Steady-state **mean** per query | **`154.1 ms`** |
| Steady-state **p50** per query | **`153 ms`** |
| Steady-state **p95** per query | **`240 ms`** |
| Steady-state **max** per query | **`240 ms`** |

This adds ~154 ms on average—well within the EduPulse `< 2.0 s` end-to-end retrieval latency NFR.

---

## Task 2: Neighbor Stitching

### How It Works

1. **Same-document neighbor lookup (`JdbcNeighborChunkRepository`)**: for each anchor chunk, queries PostgreSQL `vector_store` strictly filtered by `metadata->>'doc_id' = ?` (and `(metadata->>'version')::int = ?` when present) and `(metadata->>'chunk_index')::int BETWEEN max(0, chunk_index - windowBefore) AND (chunk_index + windowAfter)`.
2. **Document boundary isolation (`NeighborStitchingService`)**: groups and coalesces touching/overlapping windows strictly per `(doc_id, version)` key and validates every returned neighbor's `doc_id`/`version` in memory as defense-in-depth, so stitching **never crosses document or version boundaries**.
3. **Overlap deduplication (`OverlapDeduplicator`)**: merges adjacent chunks in `chunk_index` order by removing the longest matching suffix/prefix overlap (both exact and whitespace-normalized) within `[minOverlapChars, maxOverlapChars]`.
4. **Strict token budget (`TokenEstimator`)**: allocates tokens in descending anchor-relevance order, expanding outward (`-1`, `+1`, `-2`, `+2`, ...) only while the merged block fits within the remaining budget, guaranteeing `totalEstimatedTokens <= tokenBudget`.
5. **Resilient fallback**: if neighbor lookup fails (e.g. DB error), the error is logged and the service falls back to emitting the anchor chunk(s) only (`fallbackUsed=true`, `fallbackReason` populated).

### Configuration (`app.stitch.*`)

Bound to `StitchProperties` via `application.properties`:

| Environment Variable | Property | Default | Description |
|---|---|---|---|
| `APP_STITCH_ENABLED` | `app.stitch.enabled` | `true` | Enable or disable neighbor expansion |
| `APP_STITCH_WINDOW_BEFORE` | `app.stitch.window-before` | `1` | Preceding chunks (`chunk_index - N`) to fetch (`0..5`) |
| `APP_STITCH_WINDOW_AFTER` | `app.stitch.window-after` | `1` | Following chunks (`chunk_index + N`) to fetch (`0..5`) |
| `APP_STITCH_MAX_TOKEN_BUDGET` | `app.stitch.max-token-budget` | `2000` | Maximum total estimated tokens across all stitched blocks (`50..32000`) |
| `APP_STITCH_MIN_OVERLAP_CHARS` | `app.stitch.min-overlap-chars` | `15` | Minimum suffix/prefix match length to deduplicate |
| `APP_STITCH_MAX_OVERLAP_CHARS` | `app.stitch.max-overlap-chars` | `400` | Maximum overlap search window in characters |
| `APP_VECTOR_TABLE_NAME` | `app.stitch.vector-table-name` | `vector_store` | PostgreSQL vector table queried for neighbors |

---

## Task 3: Citations, Grounded Prompt, and Similarity-Threshold Refusal

### How It Works

1. **Citation headers (`CitationHeaderFormatter`)**: prefixes every context block with a standardized header containing `source`, `version`, and `page` (or `startPage-endPage` for multi-page stitched blocks):
   ```text
   [SOURCE 1 | source: cybersecurity-policy-iso27001.pdf | version: 1 | page: 4]
   <stitched block content>
   ```
   and extracts structured `Citation` objects (`index`, `docId`, `source`, `version`, `page`, `score`, `header`, `snippet`) for the audit trail.
2. **Grounded prompt (`GroundedPromptBuilder`)**: builds a strict system prompt instructing the LLM to:
   - Answer **only** from the provided context blocks.
   - Cite every factual statement inline using `[SOURCE N]`.
   - Reply with exactly `"I don't know."` if the context is insufficient.
3. **Similarity-threshold refusal gate (`GroundedAnswerService`)**: checks the top relevance score against `app.grounding.similarity-threshold` (`0.50`). When no blocks are retrieved or `maxScore < threshold`, the service short-circuits without calling the LLM and returns `"I don't know."` (`refused=true`, `citations=[]`). Only blocks meeting the threshold are injected into the prompt.
4. **LLM generation & error handling (`SpringAiAnswerGenerator`)**: invokes Spring AI's `ChatClient` (backed by Ollama `llama3.2` via `APP_CHAT_MODEL`). If the LLM is unreachable or fails, `GroundingException` is logged and mapped to `503 Service Unavailable`.

### Configuration (`app.grounding.*`)

Bound to `GroundingProperties` via `application.properties`:

| Environment Variable | Property | Default | Description |
|---|---|---|---|
| `APP_SIMILARITY_THRESHOLD` | `app.grounding.similarity-threshold` | `0.5` | Minimum relevance score (`0.0..1.0`) required to answer |
| `APP_GROUNDING_REFUSAL_MESSAGE` | `app.grounding.refusal-message` | `I don't know.` | Exact fallback response below the similarity threshold |
| `APP_CHAT_MODEL` | `app.grounding.chat-model` / `spring.ai.ollama.chat.options.model` | `llama3.2` | Ollama chat model used for grounded answer generation |
| `APP_GROUNDING_CHAT_ENABLED` | `app.grounding.chat-enabled` | `true` | Enable LLM generation (when `false`, returns grounded prompt and citations only) |

### Threshold Justification (Eval Data)

Evaluated by `GroundingThresholdEvalTest` using the pinned ONNX cross-encoder (`cross-encoder/ms-marco-MiniLM-L6-v2@233902d25c440f23af6f7d6e94d2946bac0bee0a`) across **15 in-scope compliance questions** (`src/main/resources/eval/golden-set.json`) and **10 out-of-scope questions** (`src/main/resources/eval/out-of-scope.json`):

| Question Set | Count | Min Top-1 Score | Mean Top-1 Score | Max Top-1 Score | Outcome at `threshold = 0.50` |
|---|---:|---:|---:|---:|---|
| In-scope compliance (`golden-set.json`) | 15 | `0.9989` | `0.9996` | `1.0000` | **15/15 accepted** (100%) |
| Out-of-scope (`out-of-scope.json`) | 10 | `0.0000` | `0.0000` | `0.0000` | **10/10 refused** with `"I don't know."` (100%) |

Because cross-encoder sigmoid scores separate relevant vs. irrelevant passages by a `0.9989` margin (`0.0000` vs. `0.9989`), `threshold = 0.50` (corresponding to raw cross-encoder logit `0.0`) sits symmetrically in the middle of the margin—maximizing safety against both false refusals on borderline in-scope queries and false accepts on out-of-scope queries.

---

## Task 4: Evaluation Harness & Pre-Merge Regression Gate

### How It Works

1. **Golden Q&A set (`src/main/resources/eval/golden-set.json`)**: **36 question-and-source pairs** — 24 answerable compliance questions covering all three regulatory documents (`cybersecurity-policy-iso27001.pdf`, `security-controls-audit-tables.pdf`, `zero-trust-architecture-twocolumn.pdf`) and 12 unanswerable/out-of-scope questions (including near-domain unanswerables).
2. **End-to-end harness (`EvaluationHarness`)**: executes all 36 questions through Task 1 (wider `topN=20` -> cross-encoder `topK=5`), Task 2 (neighbor stitching under token budget), Task 3 (citation headers + `0.50` refusal gate + grounded answer), and Task 4 (`FaithfulnessChecker`), reporting `Recall@k`, `MRR@k`, `Recall@1`, `faithfulnessRate`, `refusalAccuracy`, `falseRefusalRate`, and added rerank latency (`mean`, `p50`, `p95`).
3. **Faithfulness check (`FaithfulnessChecker`)**:
   - For refused/unanswerable questions: verifies exact `"I don't know."` response and zero fabricated citations.
   - For answerable questions: validates that inline `[SOURCE N]` citations exist and refer to valid indices (`1..citations.size()`), and verifies that content claims are lexically grounded in the provided context (`supportRatio >= app.eval.min-support-ratio`, default `0.70`).
4. **Committed baseline & regression gate (`RegressionGate`, `EvaluationRegressionTest`)**: compares every run against committed `eval/reports/baseline-report.json` (and human-readable `eval/reports/baseline-report.md`), enforcing both absolute quality floors and maximum allowed regression (`app.eval.max-metric-drop=0.02`).

### Baseline Report Summary (`eval/reports/baseline-report.json`)

| Metric (36 questions: 24 answerable, 12 unanswerable) | Baseline (No Rerank) | Full Pipeline (Committed Baseline) |
|---|---:|---:|
| **Recall@5** | `1.0000` | **`1.0000`** |
| **MRR@5** | `0.8264` | **`0.9375`** |
| **Recall@1** | `0.6667` | **`0.8750`** |
| **Faithfulness Rate** | — | **`1.0000`** (mean score `1.0000`) |
| **Unanswerable Refusal Accuracy** | — | **`1.0000`** (12/12 refused) |
| **False Refusal Rate** | — | **`0.0000`** |
| **Added Rerank Latency (CPU)** | `0 ms` | mean **`196.5 ms`**, p50 **`189 ms`**, p95 **`298 ms`** |

### Configuration (`app.eval.*`)

Bound to `EvalProperties` via `application.properties`:

| Environment Variable | Property | Default | Description |
|---|---|---|---|
| `APP_EVAL_GOLDEN_SET_PATH` | `app.eval.golden-set-path` | `/eval/golden-set.json` | Classpath golden Q&A dataset |
| `APP_EVAL_CORPUS_PATH` | `app.eval.corpus-path` | `/eval/corpus/compliance-chunks.json` | Classpath compliance corpus |
| `APP_EVAL_BASELINE_REPORT_PATH` | `app.eval.baseline-report-path` | `eval/reports/baseline-report.json` | Committed regression baseline JSON |
| `APP_EVAL_MIN_RECALL_AT_K` | `app.eval.min-recall-at-k` | `0.90` | Minimum allowed Recall@k floor |
| `APP_EVAL_MIN_MRR_AT_K` | `app.eval.min-mrr-at-k` | `0.85` | Minimum allowed MRR@k floor |
| `APP_EVAL_MIN_FAITHFULNESS_RATE` | `app.eval.min-faithfulness-rate` | `0.90` | Minimum allowed faithfulness pass rate |
| `APP_EVAL_MIN_REFUSAL_ACCURACY` | `app.eval.min-refusal-accuracy` | `1.00` | Minimum allowed unanswerable refusal accuracy |
| `APP_EVAL_MIN_SUPPORT_RATIO` | `app.eval.min-support-ratio` | `0.70` | Minimum lexical grounding support ratio per answer |
| `APP_EVAL_MAX_METRIC_DROP` | `app.eval.max-metric-drop` | `0.02` | Maximum allowed regression vs. committed baseline |

### One-Command Evaluation & Pre-Merge Regression Policy

Before merging any pipeline change to `main`, run the one-command regression check (must exit `0`):

```powershell
# Windows (PowerShell)
.\retrieval-post\scripts\run-eval.ps1
```

```bash
# Linux / macOS / CI
./retrieval-post/scripts/run-eval.sh
```

To refresh the committed baseline report after an intentional, peer-reviewed pipeline improvement:

```powershell
.\retrieval-post\scripts\run-eval.ps1 -WriteBaseline
```

`EvaluationRegressionTest` also runs automatically as part of `mvn test`, enforcing the regression gate in every test run.

### REST Endpoints

Through the API Gateway (`http://localhost:8080/retrieval-post`) or directly on port `8084`:

- `POST /api/v1/retrieval/retrieve` — retrieves wider `topN` from `pgvector` and reranks down to `topK`:
  ```json
  {
    "query": "How quickly must a personal data breach be reported?",
    "topN": 20,
    "topK": 5,
    "filterExpression": null,
    "rerank": true
  }
  ```
- `POST /api/v1/retrieval/rerank` — reranks caller-supplied candidate chunks directly without querying `pgvector`:
  ```json
  {
    "query": "What encryption is required for PostgreSQL at rest?",
    "topK": 3,
    "candidates": [
      { "id": "c1", "content": "Physical server rooms require biometric entry." },
      { "id": "c2", "content": "PostgreSQL data at rest must use AES-256-GCM." }
    ]
  }
  ```
- `POST /api/v1/retrieval/stitch` — stitches supplied anchor chunks with neighbors (from PostgreSQL, or from an optional inline `neighborPool` for standalone demos):
  ```json
  {
    "anchors": [
      { "id": "d1-1", "docId": "doc-1", "version": 1, "source": "iso.pdf", "page": 2, "chunkIndex": 1, "content": "..." }
    ],
    "windowBefore": 1,
    "windowAfter": 1,
    "tokenBudget": 2000
  }
  ```
- `POST /api/v1/retrieval/retrieve-and-stitch` — runs Task 1 (retrieve `topN` + rerank to `topK`) followed by Task 2 (neighbor stitching under token budget).
- `POST /api/v1/retrieval/ground` — applies the similarity-threshold refusal gate, formats `[SOURCE N | source | version | page]` headers, builds the grounded prompt, and optionally invokes the LLM on caller-supplied blocks.
- `POST /api/v1/retrieval/answer` — runs the full end-to-end Story 4 pipeline (Task 1 rerank -> Task 2 neighbor stitch -> Task 3 citation headers, threshold gate, and grounded answer).
- `GET /api/v1/retrieval/config` — returns active reranker, stitcher, and grounding settings plus pinned model metadata.

### Running Automated Tests

Run all unit, controller, and ONNX golden-set benchmark tests (no Postgres or Ollama required):

```powershell
mvn -f retrieval-post/pom.xml test "-Dtest=!RetrievalPostApplicationTests"
```

Run the full suite including `RetrievalPostApplicationTests` when Postgres (`POSTGRES_PASSWORD`) and Ollama are available:

```powershell
mvn -f retrieval-post/pom.xml test
```

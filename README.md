# EduPulse

A RAG (retrieval-augmented generation) system split into Spring Boot microservices. The goal is to improve the quality of an intelligent application while reducing its operating costs.

**Epic 1: RAG, deadline December 7.** Each story has its own microservice.

> **Current state:** Story 1, Task 1 (PDF ingestion in `ingest-static`) and Story 2, Task 1 (internet ingestion in `ingest-dynamic`) are implemented with automated tests. All other story tasks are still at baseline. See [Story status](#story-status).

---

## Architecture

| Module | Role | Port | Public? |
|---|---|---|---|
| `eureka-server` | Service discovery (infrastructure) | 8761 | localhost only |
| `api-gateway` | Single entry point, routes to services via Eureka | 8080 | yes |
| `ingest-static` | **Story 1**: static ingestion resources | 8081 | via gateway |
| `ingest-dynamic` | **Story 2**: dynamic ingestion resources | 8082 | via gateway |
| `retrieval-pre` | **Story 3**: pre-retrieval pipeline | 8083 | via gateway |
| `retrieval-post` | **Story 4**: post-retrieval and grounding | 8084 | via gateway |
| `pgvector` (container) | PostgreSQL with the pgvector extension | 5432 | localhost only |

Under Docker Compose only the gateway (8080) is published. Eureka (8761) and Postgres are bound to `127.0.0.1`, and the four services are reachable only through the gateway.

### Gateway routes

The gateway builds routes from Eureka (discovery locator, lower-case service IDs):

```
/ingest-static/**   -> ingest-static
/ingest-dynamic/**  -> ingest-dynamic
/retrieval-pre/**   -> retrieval-pre
/retrieval-post/**  -> retrieval-post
```

The prefix is stripped before forwarding, so `GET /ingest-static/actuator/health` reaches the service's `/actuator/health`. Every registered service is exposed this way. That is fine for coursework but needs explicit routes in production.

> The gateway refreshes its copy of the Eureka registry about every 30 seconds, so it can return `404` for a short while after services start.

### Stack

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.10 |
| Spring Cloud | 2025.1.4 |
| Spring AI | 2.0.1 |

These differ slightly from the versions named in the assignment PDF (Boot 4.0.7, Cloud 2025.1.2, AI 2.0.0). The Spring Initializr no longer offers 4.0.7, so the nearest available patch releases were used.

---

## Prerequisites

- **JDK 21** (the POMs set `java.version` to 21)
- **Docker Desktop**, for the Compose stack and the database
- Maven is not required: use `mvn` if installed, or the included `mvnw` wrapper in each module

---

## Configuration

Copy `.env.example` to `.env` and fill in the two required values. `.env` is git-ignored: **never commit real secrets.**

```powershell
Copy-Item .env.example .env
```

| Variable | Required | Default | Used by |
|---|---|---|---|
| `POSTGRES_PASSWORD` | **yes** | none | all four services, pgvector |
| `VENDOR_WEBHOOK_SECRET` | **yes** | none | `ingest-dynamic` |
| `POSTGRES_DB` | no | `edupulse` | services, pgvector |
| `POSTGRES_USER` | no | `edupulse` | services, pgvector |
| `POSTGRES_HOST_PORT` | no | `5432` | host port for Postgres (use `5433` if 5432 is taken) |
| `OLLAMA_BASE_URL` | no | `http://host.docker.internal:11434` (Compose), `http://localhost:11434` (local) | all four services |
| `APP_EMBEDDING_MODEL` | no | `nomic-embed-text` | all four services |
| `EMBEDDING_DIMENSIONS` | no | `768` | all four services |

Docker Compose **refuses to start** if either required value is empty.

Embeddings come from [Ollama](https://ollama.com), which must be running on the host with the model pulled (`ollama pull nomic-embed-text`). Containers reach it through `host.docker.internal`. If they get connection refused, set `OLLAMA_HOST=0.0.0.0` in the Windows environment and restart Ollama.

`EMBEDDING_DIMENSIONS` must match the embedding model (768 for `nomic-embed-text`, 1024 for `mxbai-embed-large`). Changing the model later means re-indexing.

### `ingest-static` configuration (Story 1, Task 1)

Bound via `IngestProperties` (`app.ingest.*`):

| Variable | Property | Default | Description |
|---|---|---|---|
| `APP_CHUNK_SIZE` | `app.ingest.chunk-size` | `800` | Maximum chunk length in characters (200–8000, overlap included) |
| `APP_CHUNK_OVERLAP` | `app.ingest.chunk-overlap` | `100` | Characters overlapped from the previous chunk (0 to half of `chunk-size`) |
| `APP_PDF_DIR` | `app.ingest.pdf-dir` | `../resources` (local), `/resources` (Compose) | Directory scanned by directory/file ingestion endpoints |
| `APP_EMBEDDING_MODEL` | `app.ingest.embedding-model` | `nomic-embed-text` | Embedding model name (also sets `spring.ai.ollama.embedding.model`) |
| `APP_MAX_FILE_SIZE_BYTES` | `app.ingest.max-file-size-bytes` | `52428800` (50 MB) | Maximum PDF file size accepted |

### `ingest-dynamic` and placeholder variables

The `ingest-dynamic` variables below are bound to `IngestProperties` (`app.ingest.*`), but the allowlist, private-network block, size and timeout are only enforced in Story 2, Task 4. For `retrieval-pre` and `retrieval-post`, **no code reads the variables yet**. Map them to your own properties when you implement the remaining stories.

| Variable | Default | Service |
|---|---|---|
| `APP_CHUNK_SIZE`, `APP_CHUNK_OVERLAP` | 800, 100 | `ingest-static` (bound), `ingest-dynamic` (bound) |
| `APP_INGEST_BLOCK_PRIVATE_NETWORKS` | `true` | `ingest-dynamic` (bound, not enforced until Task 4) |
| `APP_INGEST_ALLOWED_DOMAINS` | empty | `ingest-dynamic` (bound, not enforced until Task 4) |
| `APP_INGEST_MAX_BYTES` | 10485760 | `ingest-dynamic` (bound, not enforced until Task 4) |
| `APP_INGEST_TIMEOUT_SECONDS` | 15 | `ingest-dynamic` (bound, not enforced until Task 4) |
| `APP_QUERY_EXPANSION_VARIANTS` | 3 | `retrieval-pre` |
| `APP_RERANK_TOP_N`, `APP_RERANK_TOP_K` | 20, 5 | `retrieval-post` |
| `APP_SIMILARITY_THRESHOLD` | 0.5 | `retrieval-post` |

> **SSRF warning:** `APP_INGEST_BLOCK_PRIVATE_NETWORKS` only signals the setting. Compose cannot enforce it. The actual block on localhost, private IP ranges and cloud metadata addresses must be implemented in `ingest-dynamic` (Story 2, Task 4).

---

## Running

### With Docker Compose

1. Create `.env` as described above.
2. Put the PDFs to ingest in `./resources`. It is mounted read-only into `ingest-static` and `ingest-dynamic` at `/resources`.
3. Start everything:

```powershell
docker compose up --build -d
```

Startup order:

1. `pgvector` and `eureka-server` start first and must report healthy.
2. `ingest-static` follows. It is the **only** service that creates the `vector_store` table (`initialize-schema=true`), which avoids four services racing to create it.
3. `ingest-dynamic`, `retrieval-pre` and `retrieval-post` wait for `ingest-static`.
4. `api-gateway` starts after Eureka.

Check it through the gateway:

```powershell
curl.exe http://localhost:8080/ingest-static/actuator/health
```

> **Verification status:** all six images build and the full Compose stack has been started end to end (see [Verification](#verification)).
>
> **Changing the password later:** Postgres keeps the password from its first start in the `pgdata` volume. If you change `POSTGRES_PASSWORD` in `.env` afterwards, run `docker compose down -v` first (this deletes the database), or the services will fail to log in.

### Locally, without Compose

Start a pgvector database and the services from your IDE or the command line. Example with a throwaway container on port 5433:

```powershell
docker run -d --name edupulse-pg -e POSTGRES_USER=edupulse -e POSTGRES_PASSWORD=<password> -e POSTGRES_DB=edupulse -p 127.0.0.1:5433:5432 pgvector/pgvector:pg17
```

Then set the environment variables for the shell that runs the services, and start them in this order: `eureka-server`, `ingest-static`, then the rest.

```powershell
$env:POSTGRES_PASSWORD = "<password>"; $env:POSTGRES_PORT = "5433"
cd eureka-server; .\mvnw spring-boot:run
```

Without Compose, the services default to `localhost` for the database and for Eureka.

### Tests

The generated `contextLoads` tests start the full Spring context, so they need a reachable Postgres and `POSTGRES_PASSWORD` set and Ollama reachable. Without them `mvnw package` fails. Use `-DskipTests` to build without a database. The Dockerfiles skip tests for the same reason.

---

## Baseline for every task

The assignment defines a definition of done that every task must meet in addition to its own criteria. Status of each item for the project today:

| Baseline item | Status |
|---|---|
| Code is peer-reviewed and merged to the main branch | **Not started.** Nothing from this baseline is committed yet. |
| Automated tests pass, with at least one success-path and one failure-path test | **Partial.** Only the generated `contextLoads` test exists per module (it passes against a real database). No success/failure-path tests for features yet. |
| No secrets in the repository, configuration documented | **Done for the baseline.** `.env` is git-ignored, `.env.example` documents every variable, and the properties files have no default for the password or API key. |
| Errors and key events are logged | **Not started.** Only Spring's default logging. No feature code to log. |
| README or docs are updated, and the result is demonstrated to the product owner | **In progress.** This README is the first version. No demo yet. |

---

## Story status

Legend: **Done** / **Skeleton** (the module exists and runs but has no feature code) / **Not started**.

### Infrastructure: `eureka-server` and `api-gateway`

| Item | Status |
|---|---|
| Eureka server (`@EnableEurekaServer`, no self-registration) | Done |
| Gateway with Eureka discovery routing | Done |
| Actuator health for Compose healthchecks | Done |
| Verified running together | Yes. The gateway registers as `UP` and routes to all four services. |

### Story 1: Static ingestion resources (`ingest-static`, port 8081)

**Present now:** PDF ingestion pipeline (`PdfTextExtractor` with two-column layout detection and table preservation, `TextCleaner` for ligatures/hyphenation/running headers & footers, `StructureAwareChunker` with configurable size/overlap and section tracking, `PdfIngestionService` with stable `doc_id`, SHA-256 checksum idempotency, full metadata schema, and REST endpoints under `/api/v1/ingest/*`). Cybersecurity sample PDFs (ISO 27001 policy, audit controls tables, and two-column Zero-Trust paper) are in `resources/`.

**Endpoints (via gateway `http://localhost:8080/ingest-static`):**
- `POST /api/v1/ingest/directory` — ingests all PDFs in `app.ingest.pdf-dir`
- `POST /api/v1/ingest/file?path=<relative-path>` — ingests a single PDF from `app.ingest.pdf-dir`
- `POST /api/v1/ingest/upload` (`multipart/form-data`, part `file`) — ingests an uploaded PDF
- `GET /api/v1/ingest/documents/{docId}/chunks` — lists stored chunks for a `doc_id`
- `GET /api/v1/ingest/chunks/sample?size=20` — returns a random sample of chunks for manual quality inspection
- `GET /api/v1/ingest/config` — returns active chunking and embedding configuration

| Task | Status |
|---|---|
| 1. PDF ingestion: parse, structure-aware chunking with overlap, embed, store with metadata schema and stable `doc_id` | **Done** |
| 2. Update process: checksum comparison, idempotent re-ingest, atomic replacement of old chunks | Not started |
| 3. Delete process: remove all chunks by `doc_id`, with a check for orphaned vectors | Not started |
| 4. Ingestion manifest and re-index support | Not started |

### Story 2: Dynamic ingestion resources (`ingest-dynamic`, port 8082)

**Present now:** internet ingestion (Task 1, below), plus Eureka registration and datasource and embedding configuration. It does **not** create the vector table. The `VENDOR_WEBHOOK_SECRET` variable is wired in through Compose but nothing reads it yet.

#### Task 1: Internet ingestion

`POST /ingest/urls` (through the gateway: `POST http://localhost:8080/ingest-dynamic/ingest/urls`) takes a batch of sources, fetches each one, removes boilerplate, chunks the text, adds metadata and stores the chunks in the vector store.

```powershell
curl.exe -X POST http://localhost:8080/ingest-dynamic/ingest/urls -H "Content-Type: application/json" -d '{
  "sources": [
    { "url": "https://docs.spring.io/spring-ai/reference/index.html" },
    { "url": "https://example.org/some-paper.pdf" },
    { "url": "https://github.com/spring-projects/spring-ai", "type": "GITHUB_REPO" }
  ]
}'
```

`type` is optional: `AUTO` (default, decided from the Content-Type, the URL and the file contents), `HTML`, `PDF`, `TEXT` or `GITHUB_REPO`. The answer is always `200` with one result per source, so a failing source never hides the others (a malformed request body is a `400`):

```json
{ "total": 3, "ok": 2, "partial": 0, "failed": 1,
  "results": [
    { "url": "...", "status": "OK", "documents": 1, "chunks": 12, "error": null },
    { "url": "...", "status": "FAILED", "documents": 0, "chunks": 0, "error": "HTTP 404 from ..." } ] }
```

`PARTIAL` means some files of a repository failed while others were stored. Each failure is logged with its URL and reason.

How it works:

| Step | Class | Notes |
|---|---|---|
| Fetch | `HttpSourceFetcher` | The only place that makes outbound HTTP. Applies the timeout and size limit. Redirects are followed by hand (max 5) so Task 4 can check every hop. |
| Extract | `HtmlExtractor`, `PdfExtractor`, `PlainTextExtractor` | HTML: picks `<main>`/`<article>`, removes `nav`, `footer`, `aside`, scripts, styles, forms controls, dialogs and elements named like cookie/consent/GDPR banners. PDF: one section per page. |
| Repository docs | `GitHubRepoResolver` | github.com only. Ingests the top-level `README*` and every `.md`, `.markdown`, `.txt`, `.rst`, `.adoc` file under `docs/`, up to `app.ingest.repo-max-files` (25). Uses the git tree API and `raw.githubusercontent.com`. |
| Chunk | `TextChunker` | Keeps paragraphs together, splits long ones at whitespace, repeats `APP_CHUNK_OVERLAP` characters at the start of each next chunk. Sizes are in **characters**, not tokens. |
| Store | `IngestionService` | Calls `VectorStore.add`. Chunk ids are deterministic (hash of `doc_id`, version, `chunk_index`), so ingesting the same URL again overwrites instead of duplicating. |

Metadata on every chunk (the names are in `MetadataKeys` and must stay the same across services):

| Key | Value |
|---|---|
| `doc_id` | Stable hash of the normalized URL |
| `version` | Always `1` until Task 2 |
| `source` | The URL asked for (for repositories, the GitHub page of the file) |
| `page` | Page number for PDFs, `1` otherwise |
| `chunk_index` | 0-based position within the document |
| `checksum` | SHA-256 of the downloaded bytes |
| `source_url` | The URL actually fetched, after redirects |
| `fetch_date` | ISO-8601 time of the download |

Known limits of Task 1:
- If a page later shrinks, chunks beyond the new last index are **not** removed. Replacing old chunks is Task 2 (versioning).
- The allowlist, HTTPS-only rule, private-network (SSRF) block and content-type allowlist are **not enforced yet** (Task 4). Until then this endpoint will fetch any URL it is given, so do not expose it publicly.
- Pages that need JavaScript to render are not supported, and neither are scanned PDFs without a text layer.
- Request handling is synchronous and one source at a time; keep batches small (at most 50 sources are accepted).

Tests (no database or Ollama needed): `TextChunkerTest`, `HtmlExtractorTest`, `PdfExtractorTest`, `HttpSourceFetcherTest`, `DocIdsTest`, `IngestionServiceTest` (real HTTP against a local stub server and a mocked vector store: web page, remote PDF, GitHub repository, partial failure, a batch with several failing sources, repeat ingestion) and `IngestControllerTest`. The Spring context test needs a database, as for the other services.

| Task | Status |
|---|---|
| 1. Internet ingestion (pages, files, third-party repos) with boilerplate cleanup | Implemented and unit/integration tested. **Not yet run against a real database and real websites.** |
| 2. Versioning with `version` / `is_latest` metadata, retrieval defaults to latest | Not started |
| 3. Automation: file watcher and signed vendor webhook receiver with a retry queue | Not started. The watcher and retry-queue libraries are not added yet. |
| 4. Security: SSRF protection, domain allowlist, size and content-type limits, sanitization | Not started |

### Story 3: Pre-retrieval pipeline (`retrieval-pre`, port 8083)

**Present now:** the Spring Boot service validates metadata filters, normalizes queries, and runs full-text search against the shared PostgreSQL `vector_store` table. It registers with Eureka and is reachable through the gateway.

| Task | Status |
|---|---|
| 1. Safe metadata filtering (validated filter object, whitelisted fields, explicit errors) | **Done** |
| 2. Query normalization (ordered, precompiled, ReDoS-safe regex rules) | **Done** |
| 3. Acronym and synonym expansion | Not started |
| 4. Multi-query expander with result fusion and a cost/latency cap | Not started |

`GET /retrieval-pre/search` takes a query and optional `field:operator:value` filters. The service validates allowed fields, operators, and value types before running SQL. Filter values are bound as SQL parameters. Conditions combine with `AND` by default or with `OR` on request. Without filters, the service runs the standard text search and returns up to 20 ranked chunks with source metadata. Invalid filters return HTTP 400 with the list of allowed fields.

Query normalization removes leading polite phrases and trailing question marks using ordered, precompiled rules. The same input can be normalized repeatedly without changing the result. The original query is logged with control characters escaped, and a query that becomes empty returns HTTP 400. All 42 tests passed with the PostgreSQL integration test enabled. The [module README](retrieval-pre/README.md) includes API examples, Docker instructions, and the Postman collection.

### Story 4: Post-retrieval and grounding (`retrieval-post`, port 8084)

**Present now:** the Spring Boot module with embedding and pgvector dependencies, Eureka registration, and datasource and embedding configuration. The assignment expects `classpath:eval/golden-set.json`, which **does not exist yet**. No cross-encoder reranker library has been chosen or added.

| Task | Status |
|---|---|
| 1. Reranker (cross-encoder): retrieve wider top-k, rerank to a few | Not started |
| 2. Neighbor stitching using `chunk_index`, with overlap deduplication and a token budget | Not started |
| 3. Citation headers and a grounded prompt with the similarity-threshold "I don't know" fallback | Not started |
| 4. Evaluation harness: golden Q&A set, recall@k, MRR, faithfulness, regression run before merge | Not started |

---

## Verification

What has actually been checked on this baseline:

- All six modules build with Maven, and the `contextLoads` tests of the four services pass against a real pgvector database.
- All six services start and report healthy against a real pgvector (PostgreSQL 17) container.
- `ingest-static` creates the `vector_store` table. The other three do not.
- The four services register with Eureka as `UP`, and the gateway routes `/<service>/...` to each of them.
- All six Docker images build, and `docker compose config` validates, including the required-secret checks.
- `docker compose up -d` starts the full stack: all seven containers report healthy, in the documented order (Eureka and pgvector, then `ingest-static`, then the other three, with the gateway after Eureka).
- Inside Docker the four services and the gateway register with Eureka as `UP`, `vector_store` is created, and the gateway returns 200 for `/<service>/actuator/health` for all four services. Registration and routing were ready within seconds of the containers becoming healthy.
- From the host only 8080 (gateway), 8761 (Eureka) and the Postgres port are reachable; 8081-8084 are closed. No `ERROR` lines in any service log.

What has **not** been checked:

- No real Ollama call has been made, so embeddings are untested.
- No ingestion, retrieval or webhook flow exists yet, so none of them have been exercised.

---

## Known issues and notes

- **Port 5432 clash:** if another Postgres already uses 5432 on your machine, set `POSTGRES_HOST_PORT=5433` in `.env`. The services reach the database over Docker's internal network, so this only changes the host port.
- **Gateway startup delay:** about 30 seconds before newly registered services are routable.
- **`APP_*` placeholders** are not bound to any properties yet (see above).
- **Vector dimensions:** the table is created with the dimension from `EMBEDDING_DIMENSIONS` at first start. Changing the embedding model afterwards means re-indexing, and the existing table must be recreated.
- **Spring AI 2.0 / Boot 4 names:** starter and property names were checked against the real 2.0.1 jars, but Spring AI 2.0 changed some provider options, so the `spring.ai.ollama.*` properties may need small adjustments when real calls are added.

---

## Project layout

```
EduPulse/
├── docker-compose.yml     # full stack
├── .env.example           # copy to .env and fill in
├── resources/             # PDFs to ingest, mounted read-only (/resources)
├── eureka-server/         # service discovery
├── api-gateway/           # public entry point
├── ingest-static/         # Story 1
├── ingest-dynamic/        # Story 2
├── retrieval-pre/         # Story 3
└── retrieval-post/        # Story 4
```

Each module is a standalone Maven project with its own `pom.xml`, `Dockerfile` and `src/`. There is no root parent POM.

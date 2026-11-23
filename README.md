# EduPulse

A RAG (retrieval-augmented generation) system split into Spring Boot microservices. The goal is to improve the quality of an intelligent application while reducing its operating costs.

**Epic 1: RAG, deadline December 7.** Each story has its own microservice.

> **Current state: project baseline only.** The six services exist, build, start, register with Eureka and are reachable through the gateway. **None of the story features (PDF ingestion, versioning, filtering, reranking, ...) are implemented yet.** See [Story status](#story-status).

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

### Placeholder variables (not bound to code yet)

These are passed to the containers, but **no code reads them yet**. Map them to your own properties when you implement the stories (for example `app.chunk-size=${APP_CHUNK_SIZE}`).

| Variable | Default | Service |
|---|---|---|
| `APP_CHUNK_SIZE`, `APP_CHUNK_OVERLAP` | 800, 100 | `ingest-static` |
| `APP_INGEST_BLOCK_PRIVATE_NETWORKS` | `true` | `ingest-dynamic` |
| `APP_INGEST_ALLOWED_DOMAINS` | empty | `ingest-dynamic` |
| `APP_INGEST_MAX_BYTES` | 10485760 | `ingest-dynamic` |
| `APP_INGEST_TIMEOUT_SECONDS` | 15 | `ingest-dynamic` |
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

**Present now:** the Spring Boot module with the PDF reader, Ollama embedding model and pgvector store dependencies; Eureka registration; datasource and embedding configuration; and the `vector_store` table, which this service creates on first start (`vector(768)` embedding column with an HNSW cosine index).

| Task | Status |
|---|---|
| 1. PDF ingestion: parse, structure-aware chunking with overlap, embed, store with metadata schema and stable `doc_id` | Not started |
| 2. Update process: checksum comparison, idempotent re-ingest, atomic replacement of old chunks | Not started |
| 3. Delete process: remove all chunks by `doc_id`, with a check for orphaned vectors | Not started |
| 4. Ingestion manifest and re-index support | Not started |

### Story 2: Dynamic ingestion resources (`ingest-dynamic`, port 8082)

**Present now:** the Spring Boot module with the PDF and jsoup (HTML) readers, embedding and pgvector dependencies; Eureka registration; datasource and embedding configuration. It does **not** create the vector table. The `VENDOR_WEBHOOK_SECRET` variable is wired in through Compose but nothing reads it yet.

| Task | Status |
|---|---|
| 1. Internet ingestion (pages, files, third-party repos) with boilerplate cleanup | Not started |
| 2. Versioning with `version` / `is_latest` metadata, retrieval defaults to latest | Not started |
| 3. Automation: file watcher and signed vendor webhook receiver with a retry queue | Not started. The watcher and retry-queue libraries are not added yet. |
| 4. Security: SSRF protection, domain allowlist, size and content-type limits, sanitization | Not started |

### Story 3: Pre-retrieval pipeline (`retrieval-pre`, port 8083)

**Present now:** the Spring Boot module with embedding and pgvector dependencies, Eureka registration, and datasource and embedding configuration. The assignment expects `classpath:acronyms.yml`, which **does not exist yet**.

| Task | Status |
|---|---|
| 1. Safe metadata filtering (validated filter object, whitelisted fields, explicit errors) | Not started |
| 2. Query normalization (ordered, precompiled, ReDoS-safe regex rules) | Not started |
| 3. Acronym and synonym expansion | Not started |
| 4. Multi-query expander with result fusion and a cost/latency cap | Not started |

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

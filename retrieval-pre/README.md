# Retrieval Pre

Retrieval Pre implements Story 3, Tasks 1 and 2. It validates metadata filters, normalizes a search query and returns up to 20 matching chunks from the shared PostgreSQL `vector_store` table. PostgreSQL full-text search ranks the matches by relevance.

## Search API

Call `GET /search` directly or `GET /retrieval-pre/search` through the gateway. Pass the query exactly once as `q`. The service strips trailing question marks and leading phrases such as `please`, `kindly`, `could you`, `would you` and `can you`. It preserves internal punctuation and letter case. Normalizing an already normalized query produces the same result. The original accepted query is recorded in the application log, with line controls escaped.

Use `filter=field:operator:value` once per condition. The default `filterMode=AND` requires every condition. `filterMode=OR` requires at least one. Omitting `filter` searches all matching chunks. Percent-encode each parameter value or use your client's parameter editor.

| Type | Fields | Operators |
| --- | --- | --- |
| Text | `doc_id`, `source`, `checksum`, `source_url` | `eq`, `ne` |
| Integer | `version`, `page`, `chunk_index` | `eq`, `ne`, `gt`, `gte`, `lt`, `lte` |
| Boolean | `is_latest` | `eq`, `ne` |

Text comparisons are case sensitive. `page` and `version` start at 1, while `chunk_index` starts at 0. Boolean values are `true` or `false`. Missing metadata never satisfies a condition, including `ne`. The service accepts up to 16 conditions, 512 characters per filter value and 4096 characters for `q`. Invalid filters return HTTP 400 with `code: "invalid_filter"` and `validFields`. A missing or empty normalized query returns HTTP 400 with `code: "invalid_query"`. Query and filter values are bound as SQL parameters.

```powershell
curl.exe --get http://localhost:8083/search --data-urlencode "q=Please, access control??" --data-urlencode "filter=version:eq:1" --data-urlencode "filter=page:gte:2"
curl.exe --get http://localhost:8083/search --data-urlencode "q=access control" --data-urlencode "filterMode=OR" --data-urlencode "filter=page:eq:1" --data-urlencode "filter=page:eq:2"
```

The response has `normalizedQuery` and `results`. Each result contains `id`, `docId`, `content`, `source`, `version`, `page` and `chunkIndex`. An empty result set returns HTTP 200. Import [the Postman collection](postman/retrieval-pre.postman_collection.json) for examples with and without filters, including AND, OR and invalid requests.

## Run locally

Use Java 21 or later and a PostgreSQL database initialized by `ingest-static`. Run ingestion first to populate the table. Set the connection values for that database before starting the module.

| Variable | Default |
| --- | --- |
| `POSTGRES_HOST` | `localhost` |
| `POSTGRES_PORT` | `5432` |
| `POSTGRES_DB` | `edupulse` |
| `POSTGRES_USER` | `edupulse` |
| `POSTGRES_PASSWORD` | Required |
| `SERVER_PORT` | `8083` |
| `EUREKA_ENABLED` | `true` |
| `EUREKA_URI` | `http://localhost:8761/eureka` |

From this directory in PowerShell, disable Eureka for a direct local run and start the service.

```powershell
$env:EUREKA_ENABLED = "false"
.\mvnw.cmd spring-boot:run
```

Check `http://localhost:8083/actuator/health`. On Linux or macOS use `./mvnw` in place of `.\mvnw.cmd`.

## Run with Docker

From the repository root, fill in the shared `.env` using `.env.example` and start the service with the gateway. Compose starts the database and service registry dependencies.

```powershell
docker compose up --build -d retrieval-pre api-gateway
docker build -t edupulse-retrieval-pre ./retrieval-pre
```

The standalone build uses the same Dockerfile as Compose. The gateway URL for searches is `http://localhost:8080/retrieval-pre/search`.

## Tests

From this directory, run `.\mvnw.cmd test` for normalization and HTTP contract tests. To also run the PostgreSQL test, set `RETRIEVAL_TEST_DB_URL` to a test database JDBC URL and `RETRIEVAL_TEST_DB_PASSWORD` to its `postgres` user's password. The test uses a connection-local temporary table and fixtures. It checks unfiltered searches, numeric and boolean comparisons, AND/OR grouping and text values containing punctuation.

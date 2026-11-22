# EduPulse
EduPulse AI – Intelligent Multi-Tenant Compliance &amp; Audit Assistant

## Modules

| Module             | Port | Role                                                        |
|--------------------|------|-------------------------------------------------------------|
| `discovery-server` | 8761 | Eureka service registry (dashboard at http://localhost:8761) |
| `api-gateway`      | 8080 | Spring Cloud Gateway, single entry point, routes via Eureka |
| `rag-service`      | 8081 | RAG / Q&A over internal docs (Ollama + pgvector)            |

Gateway routes (`api-gateway/src/main/resources/application.yaml`) forward to `lb://rag-service`:
`/api/ollama/**`, `/api/employee-handbook/**`, `/api/internal-qa/**`.

Adding a new service: create a module, add it to the root `pom.xml`, give it a unique
`spring.application.name`, add the Eureka client starter, and add a route in the gateway.

## Running locally

Prerequisites: Postgres/pgvector (`docker compose -f docker/postgres/pgvector.yml up -d`) and Ollama
(`gemma3:1b`, `nomic-embed-text`) for `rag-service`.

Start in this order, each from the repo root:

```
./mvnw -pl discovery-server spring-boot:run
./mvnw -pl api-gateway spring-boot:run
./mvnw -pl rag-service spring-boot:run
```

Then call services through the gateway, e.g. `POST http://localhost:8080/api/internal-qa/ask`.
Services can take ~30 s after startup to show up in the gateway's load-balancer cache.

Note: `rag-service` reads/writes `src/main/resources/rag/.../vectorstore.json` relative to its working
directory, so run it from the `rag-service` folder (IntelliJ's default) or the path will not resolve.

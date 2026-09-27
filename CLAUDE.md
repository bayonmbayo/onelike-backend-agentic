# onelike-backend

Spring Boot 3.1.3 / Java, Maven build, MySQL (Hibernate `ddl-auto=update`, no Flyway/Liquibase — schema is entity-driven). This is the backend behind the `onelike-agentic` React frontend (sibling repo, same parent directory).

## Commands
- `./mvnw spring-boot:run` — run locally
- `./mvnw test` — run tests
- `./mvnw package` — build the jar
- `docker-compose up` — local MySQL (see `docker-compose.yml` for port/db mapping — this repo's compose maps `3307:3306`, separate from other sibling repos' MySQL containers)

## Layout
Organized by layer, single package `com.onelike.app`, everything flat within each layer folder:
- `controller/` — one class per feature/language variant (e.g. `SearchController`, `SearchControllerDE`, `SearchControllerFR`, ...), no shared `/api` prefix — each controller declares its own `@RequestMapping` path
- `model/` — JPA `@Entity` classes (`News`, `NewsBlock`, `FilterNews`, `Site`, `Quote`, `Breach`, `GDPRData`, `RoosBreakerPlayer`, `Myproxy`)
- `repository/` — Spring Data `JpaRepository` interfaces, one per entity
- `service/` — `@Service` classes, one per feature, plus `ErrorService`/`CacheService`/`CacheLoader`/`CleanupService`
- `error/` — `ApiError` (the error response shape)
- `exception/` — feature-specific `Exception` subclasses
- `configuration/`, `support/`, `util/` — infra glue, email helpers, static helpers

No `model/dto` package exists yet in this repo (unlike the sibling `onelike-service-jx`, which has one).

## Conventions to follow

- **Error handling**: every controller method wraps its body in try/catch and returns `errorService.createApiError(HttpStatus, message, throwable)` on failure — see `service/ErrorService.java` / `error/ApiError.java`. Follow this exactly; don't introduce a different error-response shape.
- **No server-side auth/session.** There's no active Spring Security filter chain (the starter is present in `pom.xml` but not wired into any active config) — controllers that need to know "who's calling" take the caller's identity fields directly in the request DTO body (uuid/email/firstname/name/displayname) and upsert against a `User` row by email/uuid. This is the pattern used by the sibling `onelike-service-jx` repo's `Pin` feature and should be followed for any new per-user feature here too.
- **CORS** is set per-controller via `@CrossOrigin(...)`, not a global config bean.
- **Response bodies** are often hand-built JSON (`net.minidev.json` `JSONObject`/`JSONArray`) wrapped in `ResponseEntity<Object>` rather than typed response DTOs — match whatever the specific feature area already does.
- **Entities**: Lombok `@Data` + `@Entity`, `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`. Hibernate creates/updates tables from these automatically — no migration files to write.

## Sibling repos (same parent directory)

- `../onelike-agentic` — the React frontend this backend serves. Has its own `CLAUDE.md`/`AGENTIC-LOOP.md`/`.claude/agents/` for the same six-stage loop.
- `../onelike-service-jx` — a separate, more mature backend sharing this package structure, which already has a working `User`/`InfoNews`/`Pin` feature at `POST /servicepub/pin` (referenced when designing new per-user features here, since it's the closest real precedent even though the code lives in a different repo).

## Agentic loop

This project is developed by the same six-stage subagent cycle as the frontend, defined in `.claude/agents/`. See **[AGENTIC-LOOP.md](./AGENTIC-LOOP.md)** for the full cycle and the mandatory human-confirmation gate after planning — same rule as the frontend: `planner`'s proposed plan must be confirmed by the user before `builder` touches code.

When a task spans both this backend and the frontend (a new API + the frontend code that calls it), invoke `bridge` after the plan is confirmed and before either side's `builder` starts — see AGENTIC-LOOP.md's "Cross-repo work" section.

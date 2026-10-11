# onelike-backend

Spring Boot 3.1.3 / Java, Maven build, MySQL (Hibernate `ddl-auto=update`, no Flyway/Liquibase — schema is entity-driven). This is the backend behind the `onelike-agentic` React frontend (sibling repo, same parent directory).

## Commands
- `./mvnw spring-boot:run` — run locally
- `./mvnw test` — runs 11 tests: a context load plus the behavioural suites under `src/test/java/com/onelike/app/{support,controller}` (`ConsoleTokenTest` for token verification, `FilterNewsControllerAuthTest` for the 401/403 boundary with `MockMvc`). **Extend these rather than hand-building fixtures** — a harness now exists. The `test` profile (`src/test/resources/application-test.properties`) points them at the `onelike_test` schema on the same host MySQL, never the live `onelike`
- To reset test state: `DROP DATABASE onelike_test;` — the next `./mvnw test` recreates it from the entities
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
- Per-user Social News cards live in `UserNewsContainerController`/`UserNewsContainerService`/`model/UserNewsContainer`, over the 20 built-in platform names in `util/SocialMediaPlatforms.ALL` (the single source of that list, also used by `SearchController`'s feed job)

`model/dto/` holds the request DTOs (`PinDTO`, `ContainerDTO`, `ContainerDeleteDTO`, `ContainerOrderDTO`, `ContainerOrderItem`) — same package as the sibling `onelike-service-jx`'s.

## Conventions to follow

- **Error handling**: every controller method wraps its body in try/catch and returns `errorService.createApiError(HttpStatus, message, throwable)` on failure — see `service/ErrorService.java` / `error/ApiError.java`. Follow this exactly; don't introduce a different error-response shape.
- **No Spring Security at all, and two legitimate identity patterns.** `spring-boot-starter-security` and the oauth2 resource server are **commented out** in `pom.xml` — `./mvnw -o dependency:list` resolves **zero** spring-security artifacts and nothing under `src/main/java` imports `org.springframework.security`. So there is no filter chain to wire, and introducing a `SecurityFilterChain` would first require un-commenting the starter, which activates Boot's default chain and authenticates **every** request across all existing controllers. Don't.
  - **Self-asserted identity, for per-user data.** Controllers take the caller's identity fields in the request DTO body (uuid/email/firstname/name/displayname) and upsert a `User` row by email/uuid — the `Pin` and `UserNewsContainer` features, and the sibling `onelike-service-jx` precedent. It is a product gate, not a security boundary: anyone can post any email.
  - **A verified JWT, for anything privileged.** `FilterNewsController` reads `@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)` and `support/ConsoleToken` verifies the HS512 signature against `jwt.secret` with jjwt (already on the classpath), taking the role from the signed claim and the caller's email from the token subject — **not** client-assertable. The token is issued by the communo backend, which owns login; this repo only verifies. Use this for anything where a bad write affects other users. `required = false` is load-bearing: with `required = true` Spring answers its own 400 and the feature's 401 never fires.
- **CORS** is set per-controller via `@CrossOrigin(...)`, not a global config bean.
- **Response bodies** are often hand-built JSON (`net.minidev.json` `JSONObject`/`JSONArray`) wrapped in `ResponseEntity<Object>` rather than typed response DTOs — match whatever the specific feature area already does.
- **Entities**: Lombok `@Data` + `@Entity`, `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`. Hibernate creates/updates tables from these automatically — no migration files to write.

## Sibling repos (same parent directory)

- `../onelike-agentic` — the React frontend this backend serves. Has its own `CLAUDE.md`/`AGENTIC-LOOP.md`/`.claude/agents/` for the same six-stage loop.
- `../onelike-service-jx` — a separate, more mature backend sharing this package structure, which already has a working `User`/`InfoNews`/`Pin` feature at `POST /servicepub/pin` (referenced when designing new per-user features here, since it's the closest real precedent even though the code lives in a different repo). **Note it hardcodes the HIBP and Webshare API keys in three places with no `.env` infrastructure**, so rotating either key breaks it until updated.
- `../onelike-mobile-agentic` — the React + Capacitor mobile app, same codebase family as the web frontend.
- `../onelike-backend-jx2-agentic` — the communo/Space backend. **It owns login and issues the JWT this repo verifies**, owns the `role` enum (`USER`/`MODERATOR`/`ADMIN`) and the role-granting UI, and in dev points at the **same `onelike` schema** on the same host — so the `user` table is shared locally. In production the two are separate MySQL instances that merely share a schema name. Anything touching roles, tokens or `jwt.secret` is coupled to this repo.

## Measurement instruments

**An instrument is only sound for a *question*.** `grep` is fine for "does this string occur" and wrong for "how many CR bytes". Before citing a number or a command as evidence, find the question here. If the instrument you are about to use is not listed and the claim is load-bearing, say so and corroborate with a second, different instrument.

`LEARNINGS.md` records *which cycle* added or demoted a row; this section is the current state.

**Any stage that retracts an instrument adds a row here**, and the orchestrator applies it at stage 06 with the other process edits — never queued. This table is the only defence against the artefact class of failure, where the instrument answers deterministically wrong and the falsifier test structurally cannot catch it, so it must not depend on anyone remembering.

**Sound on this machine**

| Question | Instrument |
|---|---|
| line endings of a file | `perl -0777` raw-byte counts of `\r\n` / `\r` / `\n`, `od -c`, `file -b` — **on a checked-out worktree** |
| how many rows a table holds | `SELECT COUNT(*)` |
| is this method byte-identical to an earlier commit | md5 of the brace-matched method body, line endings normalised first |
| which schema issued a statement | `performance_schema` digests — but only the **connection's default** schema; see `verifier.md` rule 5 for the three-leg claim |
| is a compose file valid | `docker compose -f <file> config -q` (client-side, no daemon needed) |
| does this string occur in tracked files | `git grep -F`, over worktree / `HEAD` / `--cached` as appropriate |

**Unsound here — do not cite a number from these**

| Instrument | Why, and for which question |
|---|---|
| `awk` | strips CR in this Git Bash, so it reports a CRLF region as LF |
| `grep -c $'\r'` inside `"$( … )"` | same family; disagreed with `od -c` and `file -b` on a line-ending count |
| `grep … \| cat -A` | renders a bare `$` with no `^M` on files that are provably CRLF |
| `information_schema.TABLES.TABLE_ROWS` | an InnoDB estimate; off by thousands. Not a row-count baseline |
| `git show` / `git cat-file` | return the **LF-normalised blob**, so under `core.autocrlf=true` a CRLF file reads as pure LF. Never take a line-ending reading from a blob |
| `git diff --stat` | cannot see an untracked file, so it cannot show which files a change touched |
| `git diff --numstat` | an insertion count cannot show that a *method* is unchanged |
| a non-ASCII HTTP body passed as a **shell-literal argument** (e.g. `curl --data-binary '{"name":"S\u00fcddeutsche"}'`) | the shell layer here transcodes `\uXXXX` escapes to a single-byte codepage before curl sees them, so the server stores mojibake and you cannot tell transport corruption from a real storage bug. Write the UTF-8 bytes to a file and send `--data-binary @file` |

**Credential handling.** `git diff`, `git show`, `git log -p` and `git add -p` all **render a removed literal**, so the first instinct of reviewing a secret-removal change prints the secret. Use `--stat` / `--numstat` / `git status`, or pipe through a redactor: `sed -E 's/"[A-Za-z0-9]{28,48}"/"<REDACTED>"/g'`.

## Agentic loop

This project is developed by the same six-stage subagent cycle as the frontend, defined in `.claude/agents/`. See **[AGENTIC-LOOP.md](./AGENTIC-LOOP.md)** for the full cycle and the mandatory human-confirmation gate after planning — same rule as the frontend: `planner`'s proposed plan must be confirmed by the user before `builder` touches code.

When a task spans both this backend and the frontend (a new API + the frontend code that calls it), invoke `bridge` after the plan is confirmed and before either side's `builder` starts — see AGENTIC-LOOP.md's "Cross-repo work" section.

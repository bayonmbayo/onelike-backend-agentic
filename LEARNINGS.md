# Learnings

Durable, cycle-by-cycle notes from `learner`, plus `manager`'s reconciled backlog and `controller`'s process review appended to the same entry by the orchestrator, meant to be read by `planner`/`manager` at the start of future cycles — not just living in loop conversation output that scrolls away. Append a new dated entry per cycle; keep entries short and specific, refine an existing entry rather than duplicating it if the same lesson recurs.

(Created 2026-09-20, mirroring `../onelike-agentic/LEARNINGS.md`. Earlier cycles in this repo — the Google News URL decoder, `ImagePreview`, `ProxyAuthenticator`, the `Pin` feature — predate this file and are not backfilled.)

---

## Cycle — 2026-09-20 — `ArticleImage` DB cache so preview-image scanning hits the proxy once per article

Objective: the proxy budget was being burned re-scanning the same article pages every 5-minute cycle. Added `model/ArticleImage` + `repository/ArticleImageRepository` + `util/ArticleImageCache`, wired into `SearchController.giveMeArrayOfLinks`. 4 tasks, `mvnw -o compile` and `mvnw -o test` green, `verifier` PASS-WITH-NOTES, wire shape of `linkspreview` unchanged so no `bridge`.

**Worked**
- **The static-DAO cache is the established "scan once" pattern in this repo** — `@Component` with an `@Autowired` instance field copied into a `private static` repository in `@PostConstruct initStaticDao()`, used by `GoogleNewsUrlDecoder`, `SearchController`, and now `ArticleImageCache`. Reuse its design points verbatim for any future "expensive external lookup, stable answer" problem (publisher favicon, RSS→canonical URL, ...): SHA-256 of the key instead of the raw URL (utf8mb4 URLs exceed MySQL's 3072-byte unique-index limit); a `null` value column = deliberate negative result with a TTL (24h) so unreachable pages are not re-scanned every cycle; one `findBy…In(hashes)` query per batch; `saveAll` with a per-row `save` fallback because overlapping language jobs can insert the same hash concurrently; and "if the repository/DB is unavailable, degrade to scanning everything" so the cache can never make the feature worse than before.
- `planner` pre-warning `builder` about the heredoc/apostrophe pitfall meant the tool switch cost one failed call, not a debugging detour. Keep environment pitfalls in the per-cycle `builder` prompt — a definition-file note alone does not survive session-level tool preferences.
- `contextLoads` booting against live MySQL with `ddl-auto=update` is the cheapest real check this repo has: it validates bean wiring and that Spring Data can parse the derived query name (`findByUrlHashIn`) at bootstrap. A typo in a derived method name fails here, not at first request.

**Didn't work / watch out for**
- **Bash heredocs break on apostrophes inside Java comments — even `<<'EOF'` quoted ones, via the tool wrapper.** New `.java` files must be created with the Write tool; existing files edited with Edit; reserve `sed` for single-line mechanical swaps (the import replacement was fine). Put this sentence in every backend `builder` prompt.
- **`mvnw test` is not hermetic and its target DB is ambiguous.** The only test (`OnelikeBackendApplicationTests.contextLoads`) boots the full context against `application.properties` → `localhost:3306`, but this repo's `docker-compose*.yml` map MySQL to `3307`. So a green run mutates (creates tables in) whatever MySQL is listening on 3306 — a host install or another sibling repo's container, not this repo's compose DB — and there is no test profile/H2. `verifier` must state which DB the test actually touched, and `planner` should treat "the test passed" as "schema got applied somewhere" until a cycle adds a `test` profile or points `application.properties` at 3307.
- **Hibernate 6.2 drops the name of a single-column `@Index(unique=true)`** — it is collapsed into an auto-named `UK_…` unique constraint, so `idx_ai_url_hash` (and the pre-existing `idx_gnl_article_hash` on `GoogleNewsLink`) never exist in the schema. Harmless functionally, but: don't cite an `@Index` name as if it were in the DB, and if a name ever matters use `@Table(uniqueConstraints = @UniqueConstraint(name=…))` instead. `verifier` should check the real schema (`SHOW INDEX`) rather than the annotation when an index is part of the verdict.
- **Commit (or at least stage) at the end of every cycle.** The tree carried ~25 untracked files and 4 modifications from earlier cycles, so `verifier` had no pre-task baseline and had to diff against HEAD, which blurs "this cycle" with "everything since `f1b94be`". Also: `logs/onelike.log` and `logs/archived/` show up as changes every run — they should be gitignored so they stop polluting the diff.
- Two small follow-ups flagged by `verifier`, both backlog not blockers: `ArticleImageCache.persist()` relies on a double-caught NPE when the static repository is null (add an explicit `if (imageRepository == null) return;`); and the static-DAO pattern has no seam for unit tests (the static field is private with no setter), so all `util/` cache logic is trace-verified only. If a future cycle wants a real test for `GoogleNewsUrlDecoder`/`ArticleImageCache`, add a package-private static setter for the repository first.

**Backlog seeded**: test profile or 3307 alignment for `mvnw test`; gitignore `logs/`; null-guard in `persist()`; test seam for static-DAO utilities; commit the accumulated uncommitted cycles.

---

## Cycle — 2026-09-21 — Scheduled 72h purge of unpinned `Pin`, orphaned `InfoNews`, unused `GoogleNewsLink`/`ArticleImage`

Objective: stop the two cache tables and the pin tables growing forever. Added `service/CleanupService` (`@Scheduled` + `@ConditionalOnProperty("onelike.cleanup.enabled")`, four config keys under `onelike.cleanup.*` in both properties files) and one `@Modifying @Transactional` delete per repository. No schema change, no wire change, no `bridge`. `mvnw -o test` green, `verifier` PASS-WITH-NOTES.

**Worked**
- **Ground "unused/orphaned" in the actual join columns before writing any delete.** Planner found the only real FK is `pin.info_news_id → info_news.id`; the cache tables join to pins purely by publisher-URL string (`google_news_link.publisher_url` / `article_image.url_hash = SHA2(info_news.url,256)`). That single fact fixed the mandatory delete order (pin → info_news → link → image) and showed which deletes could be JPQL (`NOT EXISTS` on entity paths) versus which one had to be native (`SHA2()` has no JPQL equivalent). Reuse this checklist for any future bulk delete: list FKs, list string-keyed joins, order deletes so FK parents go last.
- **All four entities already carried a usable timestamp** (`pinnedat`, `savedat`, `created`, `checked`) — check for existing columns before proposing `ddl-auto` additions; this cycle needed none.
- **`@Transactional` on the repository method, not the service.** `CleanupService.purgeUnused()` runs each step in its own try/catch so one failing delete does not stop the others; a class-level `@Transactional` on the service would have marked the whole thing rollback-only after the first failure. Same shape for any future multi-step scheduled job.
- **A single `min-age-ms` guard substitutes for the missing transaction in `PinService.pin()`**: it saves `InfoNews` then `Pin` in two calls, so a cleanup run between them would delete a just-saved orphan. Age-gating every delete by the same cutoff (72h) removes the race without touching `PinService`. Keep the guard if the delay is ever shortened; it is load-bearing, not cosmetic.
- **Native `@Query` deletes are NOT validated by `contextLoads`** (only JPQL/derived queries are parsed at bootstrap). `verifier` dry-ran the native one as `SELECT COUNT(*)` with the same WHERE against the live DB and checked `SHA2(url,256)` matches Java's lowercase-hex `url_hash` byte-for-byte on real rows. Make this the standard verification for any native SQL in this repo.
- **Properties files are CRLF.** An Edit with LF-anchored `old_string` failed harmlessly on the first try; the retry with CRLF-aware anchors worked. Tell `builder` up front when a task touches `src/main/resources/*.properties`.

**Didn't work / watch out for**
- **`NOT IN (subquery)` is NULL-poisoned in MySQL** — the native `article_image` delete guards with `AND n.url IS NOT NULL`; the JPQL ones use `NOT EXISTS`, which is immune. Any future `NOT IN` needs the same guard or should be rewritten as `NOT EXISTS`; `verifier` should look for it explicitly.
- **`pin.pinned` can be NULL.** `PinService.pin()` has no null guard on `pinDTO.getPinned()`, so a `pinned=NULL` row is neither deleted (`pinned = false` misses it) nor protects cache rows (`pinned = true` misses it) but does keep its `InfoNews` alive forever. Backlog: default `pinned` to `false` in `PinService` or make the column `nullable=false` — do this before any cycle that reports cleanup counts as a metric.
- **Stale javadoc lies after a behaviour change**: `model/GoogleNewsLink.java:17-20` and `model/ArticleImage.java:19-20` still say "permanent cache / never invalidated". When a cycle changes an entity's lifecycle, grep the entity javadoc for words like "permanent"/"never" and include the fix in the same task.
- **Recurrences of 2026-09-20 lessons**: the test still ran against host MySQL `3306` (compose `3307` not up), and both properties diffs bundle last cycle's `onelike.news.languages` line with this cycle's block because nothing was committed in between. Both backlog items from the previous entry are still open and are now costing `verifier` time every cycle — `manager` should schedule the commit + `logs/` gitignore before another feature cycle.

**Backlog seeded**: null-guard/default for `pinned`; fix the two stale javadocs; (carried) commit accumulated cycles, gitignore `logs/`, test profile or 3307 alignment.

---

## Cycle — 2026-10-10 — Per-user custom news containers (themes) for Social News, phase 1 of 2 (backend)

Objective: a logged-in user adds themed cards ("AI", "Crypto") alongside the 20 built-in platform cards in Social News and rearranges all of them. Cross-repo (frontend is `../onelike-agentic`), so `bridge` ran after the confirmation gate and before `builder`; only the backend was built this cycle. New `UserNewsContainer` entity + repository + service + `UserNewsContainerController` + 4 DTOs + `util/SocialMediaPlatforms`. `verifier` FAIL on round 1, PASS-WITH-NOTES on round 2 after two `builder` fix passes.

**Worked**
- **Ground "is this feasible" in the actual fetch code before promising a feature.** `planner` found `articlesOf` was already a generic Google-News-RSS-by-query fetcher, so themed feeds were reuse, not new machinery — and rejected the cheaper-looking `/service/searchnews` because its Aho-Corasick substring matching makes "AI" match "said"/"maintain"/"chain" and it can only return news *about* the 20 platforms. The same pass found the 20 platform names were a hardcoded `static List<String>` assigned in `@PostConstruct`, which is what made phase 1 scopable at all. Do this fetch-path reading *before* the gate; it is also what justified the two-phase split the user accepted.
- **`bridge` earns its slot on wire-level detail no `builder` would guess.** Five corrections to a plan the user had already confirmed, all from reading real code: `namekey` is NOT the `NewsBlock` key (`articlesOf` writes `socialmedia = searchText` verbatim, so the key is the trimmed display title); `ApiError.status` serialises as the enum NAME and `message` is runtime-prefixed, so the client must branch on HTTP status + `errors[0]` and never on `message`; new DTOs inherit no `FAIL_ON_UNKNOWN_PROPERTIES` relaxation and each need their own `@JsonIgnoreProperties`; `articlesOf` output would hand custom cards `description:"Describe me"` / `website:"https://www.google.com"` unless explicitly overwritten; the reorder payload must be projected to `{kind,name}` or the client POSTs megabytes of article JSON on every drag. Treat "the plan is confirmed" as orthogonal to "the contract is right".
- **One shared predicate instead of two copies that drift.** Renderer and position calculator both call `isRendered(container, language)` (`UserNewsContainerService.java:484`, used by `containersJson` and `wirePosition`) — the round-1 `position` inconsistency was exactly two independent copies of the same rule. Reuse this shape whenever a value is both rendered and counted.
- **`builder` stating a trade-off beats inventing schema mid-pass.** When the user made themes language-scoped mid-cycle, `builder` made language part of theme identity (`uk_unc_user_kind_namekey_language`), kept themes language-matched but platforms deliberately language-blind in the reorder key (keying platforms by language would make a German reorder match none of the 20 seeded rows), kept the 10-theme cap global with a javadoc saying so *so a future cycle does not "fix" it*, and wrote the cross-language `position` artefact down plainly rather than bolting on a per-language scheme. Renaming the key deliberately so `verifier` could make a positive `SHOW INDEX` check is a good habit — pick names that make verification falsifiable.
- **`verifier` proving rather than asserting**, both times: it reproduced `Duplicate entry '6-THEME-crypto' for key 'uk_unc_user_kind_namekey'` instead of claiming the stale key would bite, and it corrected `builder`'s *reasoning* on the column widths (the old 3-column key fit at 3068 bytes; 4 columns at default widths would be 4088 and fail table creation, not merely be wasteful).
- **Throwaway-schema technique — reuse this.** `verifier` was denied permission for both the authorised `DROP TABLE` and the gentler `DROP INDEX`. Instead of stopping or routing around the denial, it created a scratch schema `onelike_vfy`, let `ddl-auto=update` build it from nothing, ran every behavioural test there, and dropped it. All evidence came from a correctly-built schema with no leftovers, and the destructive DDL on the dev schema stayed a decision for the human. This is now the standard move for any verification that needs a clean schema.
- `builder`'s unrequested explicit column lengths prevented a real bug: a 40-char title containing `U+0130` lowercases to 80 chars, which under `varchar(40)` is MySQL error 1406 surfacing as the 500 catch-all. Any `namekey`-style lowercased derivative needs headroom over its source column.

**Didn't work / watch out for**
- **A serialisation assumption that was true for the wrong class — the round-1 FAIL.** The binding contract assumed json-smart stringifies `java.util.Date` via `toString()`. True for that class *exactly*; Hibernate returns `java.sql.Timestamp`, a subclass, which falls through to bean reflection and emits `{"nanos":N}` (`java.sql.Date` emits `{}`). Worse, it was *type-unstable for the same row*: a string on the create that made it (in-memory `Date`), an object on every read afterwards. `/service/news` is safe only because it puts `new Date()`. Two rules out of this: **(1)** when a wire contract pins a type, verify on the **read-back** path — a create-only smoke test cannot see this class of bug; **(2)** hand-built json-smart bodies dispatch on the *declared runtime type*, so normalise at one choke point (`wireDate` re-wraps as `new Date(getTime())`, `UserNewsContainerService.java:515`) and grep every `.put(` site for other values of that type — `builder` found 23, two of them `Date`-valued, one on a branch `verifier` had not flagged.
- **`ddl-auto=update` adds, it does not reconcile.** It shrank the three columns and added the new 4-column unique key but left the stale 3-column `uk_unc_user_kind_namekey` in place, so the dev schema rejects writes the code considers legal. When a cycle changes a `@UniqueConstraint` (or any constraint, not just a column), assume **the old one survives** and plan an explicit `DROP INDEX` as a human-confirmed step in the same cycle — do not let it become a mystery 500 three cycles later. Corollary for `planner`: "entity-driven schema, no migrations" is true for columns and new constraints only.
- **Open issue, code-evident not live-tested**: `isRendered` treats a null `language` as English, but duplicate detection passes the literal value and MySQL's unique index treats NULL as distinct from everything — a hand-written null-language row would render in the English view yet never be found as a duplicate. No write path produces one today. Fix by making `language` `nullable=false` with a default, not by special-casing the predicate.
- **Wire `position` is presentation-only, never an identifier**: it is the dense index of the *language-filtered* list, so the same row reports different positions in different languages (stored 22, wire 20 in one run). Phase 2 / the frontend must not persist or key off it. Related and arguably more user-visible than the theme artefact: platform order is global across all languages by design.
- Cost to watch (bounded by the 10-theme cap, not a problem yet): `wirePosition` adds one extra query per create, and reads do one `news_block` query per theme.
- **~~Correction to the 2026-09-21 entry~~ — this correction was itself wrong; retracted 2026-10-11 (third).** It claimed the properties files are mixed, "head CRLF but the `onelike.cleanup.*` block LF-only". `application.properties` is **uniformly CRLF**: 208 CRLF / 208 CR / 208 LF measured per line on a fresh clone with `core.autocrlf=true` smudge applied, all four `onelike.cleanup.*` lines included. The 2026-09-21 claim it overturned ("properties files are CRLF") was correct and stands. Cause of the error: **`git show` / `git cat-file` return the LF-normalised blob**, so under `core.autocrlf=true` a reading taken through git reports `CRLF=0` for a file that is CRLF on disk — `git show 1ee1f03:…/application.properties` gives `CRLF=0 LF=208` while the same commit checked out gives `CRLF=208`. The surviving rule is still the right one for a different reason: **`cat -A` the actual anchor region in the worktree before editing — and never take a line-ending reading from a blob.**
- **Recurrence #4, now the oldest open item**: `mvnw test` still targets host MySQL `3306` rather than this repo's compose `3307`, so a green run mutates whichever MySQL answers there. The test-isolation cycle was planned but parked unconfirmed. `manager`: re-surface it at the gate rather than re-planning it.

**Backlog seeded**: human-confirmed `DROP INDEX uk_unc_user_kind_namekey`; `language` `nullable=false` + default on `UserNewsContainer`; phase 2 (frontend side in `../onelike-agentic`, against `bridge`'s spec plus this entry's `position`/`last_update` notes); per-language ordering if the cross-language `position` artefact proves annoying; (carried) test profile or 3307 alignment, `pinned` null-guard, stale entity javadocs. *(Note: "commit accumulated cycles" and "gitignore `logs/`" from earlier entries are now done — commits `b004e7c`..`918755a` on `master`.)*

---

## Cycle — 2026-10-10 (second) — `test` profile so `mvnw test` stops writing to the live dev schema

Objective: the four-cycle-old recurrence. Solved without Docker: a profile-specific `src/test/resources/application-test.properties` (3 keys — `onelike_test` datasource URL with `createDatabaseIfNotExist=true`, `searchnews=false`, `cleanup.enabled=false`), `@ActiveProfiles("test")` on `OnelikeBackendApplicationTests`, the tracked 0-byte `application.tmp.properties` deleted, two `CLAUDE.md` lines. `verifier` PASS first time, no fix pass. Committed as `f3199ef`. **Recurrence #4 is closed** — `mvnw test` no longer touches `onelike`.

**Worked**
- **Prove isolation by attribution, not by inference.** `verifier` refused to accept `The following 1 profile is active: "test"` as proof, on the grounds that a merge-order mistake prints that line too. It used `performance_schema.events_statements_summary_by_digest`: across two runs `onelike_test`'s `MAX(LAST_SEEN)` advanced to `18:33:05` while `onelike`'s stayed frozen at `17:30:41`, cross-checked against the entities' own timestamp columns (`MAX(google_news_link.created)` etc. all `2026-09-21`, zero rows in the test window). **This is the technique for any "did X touch Y" question on this MySQL** — but statement digests attribute writes to the **connection's default schema**, not to the tables touched, so a digest alone is necessary and not sufficient; see the 2026-10-11 (second) entry for the three-part claim that is sound. Logs only attribute intent.
- **Verify a dependency's behaviour by mechanism when running it is the thing you're trying to avoid.** Planner established that `spring-dotenv` resolves `.env` under surefire without running the test (which would still have polluted `onelike` at that point): its `META-INF/spring.factories` registers a `SpringApplicationRunListener`, `@SpringBootTest` calls `run()` which fires those listeners, the dependency is compile scope so it is on the test classpath, and `pom.xml` has no surefire config so the working directory is the repo root where `.env` lives. Same pass used `javap` on the `mysql-connector-j-8.0.32.jar` actually on the classpath to confirm `createDatabaseIfNotExist` exists there, rather than trusting the docs for some other version. Chain-of-mechanism beats both "try it" and "the docs say so" when trying it is the risk.
- **Profile-specific merges; `application.properties` in test resources shadows.** `application-test.properties` layers over the 197-line main file, so dialect/Hikari/mail/redis/JWT/languages are all inherited and the password stays `${ONELIKE_DB_PASSWORD}`. A plain `src/test/resources/application.properties` would shadow the main file entirely (test-classes precedes classes) and the context would fail to start. `builder` wrote that distinction into the file's own header comment so a future cycle does not "simplify" it — **do that for any non-obvious choice: the explanation belongs next to the code, not only in this file.**

**Didn't work / watch out for**
- **`information_schema.TABLES.TABLE_ROWS` is an InnoDB estimate, and planner used it as the baseline.** The pre-run baseline for `onelike` was recorded as `news_block=276, google_news_link=25912, article_image=928`; real `SELECT COUNT(*)` afterwards gave `300 / 33731 / 1014`, which reads as "three tables moved during the test". `verifier` did not guess — it re-read `TABLE_ROWS`, found it *still* says exactly `276 / 25912 / 928`, and identified the delta as real-vs-estimate, not before-vs-after. The sharp part: **the verification brief had explicitly warned "use `SELECT COUNT(*)`, not `TABLE_ROWS`, it will produce a false positive" — and the plan's own baseline then fell into exactly that trap.** Writing a caveat into the steps you hand downstream does not protect the measurements you take yourself. Any number `planner` captures as a baseline must meet the same bar it imposes on `verifier`.
- **New standing caveat: the test is now isolated *from* the dev schema, which means it no longer covers it.** `onelike` has 42 tables; `onelike_test` has 17, one per mapped entity — the 25 dev-only tables (`chat`, `comment`, `discussion`, `space`, `image*`, `report*`, …) have no entity backing and are invisible to the test. More sharply: against a fresh schema `ddl-auto=update` always emits the ideal `CREATE TABLE` and always succeeds; the same entity change against the drifted `onelike` must express itself as an `ALTER` that can fail on real data — a duplicate row under a new unique key, a narrowing column type, an orphaned FK. **A green `./mvnw test` will no longer warn about any of that.** The previous cycle's `ALTER TABLE user_news_container DROP INDEX uk_unc_user_kind_namekey` churn is exactly this class of event, and it is in this server's own statement history. `planner`/`manager`: never read a green test as "this migration is safe against dev or prod" — for an entity/constraint change, the evidence still has to come from the dev schema or from `verifier`'s scratch-schema technique.
- **`refreshProxies()` is ungated; the test escapes it on timing, not by design.** `SearchController.java:889`, `@Scheduled(fixedRate = 900000, initialDelay = 10000)`, not behind `searchnews` (unlike `performJob()` at :894). The context starts the scheduler around 16s and the test tears down at 21–25s, so the 10s delay lands just past shutdown — `onelike_test.myproxy` has 0 rows, so it never fired. A slower context or one more test would let `replaceInactiveProxies()` call the live Webshare API and insert ~80 rows. Deliberately not fixed: gating it on `searchnews` would also stop proxy refresh in production whenever the feed is paused, which is its own decision. Backlog: a dedicated `onelike.database.proxies.enabled` key.
- `target/classes/application.tmp.properties` survives the deletion as stale build output — `maven-resources-plugin` does not remove deleted resources without a `clean`, so a `./mvnw package` without `clean` still bundles the 0-byte file. No functional impact; just don't be surprised by it.

**For the record** (useful when touching the test profile again): the 15 language `performJob()` methods all read the same `@Value("${onelike.database.searchnews}")`, so one key disables all of them; `CleanupService` is `@ConditionalOnProperty(..., matchIfMissing = true)`, i.e. ON unless named explicitly, which is why `cleanup.enabled=false` is in the file; and `onelike_test` is auto-created with the server default collation, which `verifier` confirmed (rather than taking planner's word) matches `onelike` exactly at `utf8mb4_0900_ai_ci` — the index-byte arithmetic from the previous entry depends on that.

**Backlog seeded**: `onelike.database.proxies.enabled` to make the test hermetic by design rather than by timing; (carried) `language` `nullable=false` + default on `UserNewsContainer`, phase 2 frontend in `../onelike-agentic`, `pinned` null-guard, stale entity javadocs. *(The `DROP INDEX uk_unc_user_kind_namekey` item is resolved — it shows in this server's statement history. "Test profile or 3307 alignment", open since 2026-09-20, is closed by this cycle.)*

---

## Cycle — 2026-10-11 — Self-refreshing theme news, plus `onelike.database.proxies.enabled` to make the test hermetic by design

Objective: custom theme cards were frozen at their creation minute, and the previous cycle's hermeticity rested on a timing coincidence. Added `repository/ThemeKey` (interface projection), a JPQL `DISTINCT (name, language)` query on `UserNewsContainerRepository`, `service/ThemeRefreshService` (gated `@Scheduled`, 30-min `fixedDelay`, `max-per-tick=5`, oldest-first `nullsFirst`), raised `loadThemeContent`/`isFresh` to package-private as a shared choke point with an in-flight guard, and gated `SearchController.refreshProxies()`. Five new keys in both main properties files, two in the test profile. No entity, column or constraint change. `verifier` PASS-WITH-NOTES after one `builder` fix pass.

**Worked**

- **A write-once cache is a defect, not a phase-2 item — and it is invisible to every check the loop has.** `loadThemeContent` had exactly one call site, inside `createContainer`, which returns early for an existing theme; so the `block-max-age-ms` freshness check could never fire again after the first create. The API shape was right, the tests were green, the plan was implemented — the feature was wrong only *in time*. **Rule for `planner`: whenever a cycle introduces a cached or fetched value, name who calls the refresh the second time, and treat "nobody" as a bug in that cycle.**
- **Proof by digest accounting — pair this with the attribution technique from 2026-10-10 (second).** The real integration risk in the fix was whether the post-fetch re-read saw committed state or echoed the pre-fetch value from the same persistence context (if the latter, *nothing* would ever count as refreshed). `verifier` predicted the exact `COUNT_STAR` for the single-row `news_block` SELECT digest — 1 fresh tick + 8 staleness loop + 5 `isFresh` + 5 `articlesOf` no-proxy arm + 5 new re-read = 23 — measured **24**, and noted a session-cached read would have produced 19. Same instrument as the schema-attribution check, different question: **statement digests turn "is this read real?" into a falsifiable arithmetic prediction**, which no log line can do.
- **Choose the fixture that makes the cheap path the reachable one.** `verifier` proved "a failed refresh cannot damage a card" at zero cost by leaving `myproxy` empty in its scratch schema: `MyproxyService.getProxyNumber()` is `repository.count()`, so with no rows `articlesOf`'s proxy loop never iterates, `proxyAvailable` stays false, and control reaches the branch that returns the cached feed without saving. A free failure path is worth more than a paid success path.
- **A reasoned partial with its reasoning shown beats a contrived pass.** Post-fix, `refreshed` has never been observed non-zero: only a live fetch produces that, and `verifier` declined to spend real proxy budget plus an outbound Google News request. It also established there is no honest cheap substitute (`articlesOf` fetches *through* an HTTP proxy, so a local fake feed needs TLS interception with a trusted cert) and explicitly refused to fake it with a timed external `UPDATE` into the ~10 ms window, which would prove only that two `Date`s compare correctly while being flaky. **"I did not buy this" is a finding.** Residual risk is low — the unobserved line is `refreshed++` in the true branch of a 4-line predicate whose false branch runs 5× per tick, on a read mechanism proven above. Carried as a one-line backlog item for the next run that legitimately has live proxies.
- **One definition each, deliberately**: `isFresh` is absolute age against a threshold; `hasMoved` is a relative comparison of two observations and reads no threshold and no `now`. Keeping them distinct is what preserved the single-choke-point property from the `isRendered` lesson. (`hasMoved` does not require a non-null feed whereas `isFresh` does — not reachable today, since every path that moves `updated` sets the feed in the same unit, but the asymmetry looks like an oversight when it isn't. Said so in the javadoc.)
- **The in-flight guard's `add` sits outside the `try`**, so a losing thread's `finally` cannot evict the winner's key; inside, the guard would have defeated itself. Reuse that placement.

**Didn't work / watch out for**

- **A measurement must be able to see the thing it is being used to claim.** I wrote "`git diff --stat` confirms the fix pass touched exactly one file" — but `ThemeRefreshService.java` is **untracked**, so it is invisible to `git diff`, and a fix pass editing only that file would produce an identical `--stat`. The claim was true; the method could not support it. `verifier` substituted methods that work: `git status` plus a content comparison against its own earlier full read, and `git diff --numstat` per tracked file. **This is the same class of error as the previous entry's `TABLE_ROWS` baseline — two instances, two stages, both mine, so record the class and not the instances: before quoting a number, state what it would look like if the claim were false.** (Minor: `--stat`'s figure is total changed lines, not insertions — `SearchController` is 12 insertions / 1 deletion, not 13.)
- **A counter that reports total failure as total success is a real defect, even with no row harmed.** `builder`'s first pass counted `loadThemeContent`'s boolean — which honestly means "a fetch was *attempted*" — as `refreshed`, so with every proxy down the summary read `5 refreshed, 0 failed`. Root cause: `articlesOf` swallows its own failures and returns a `JSONObject` either way, so a caller cannot distinguish an outage from a refresh. Fix uses `NewsBlock.updated` as the only honest signal, since only the save inside `articlesOf` moves it. Two things worth keeping: **for an unattended job the single summary log line *is* the monitoring**, so a lying counter is a defect against the confirmed plan (which specified those five counters) and therefore a fix pass, not backlog; and the repaired counter deliberately answers *"does the card have new content"*, not *"did my fetch succeed"* — a concurrent external writer saving the same theme inside the window counts as `refreshed` even if this job's own fetch failed. That is the more useful question for a one-line summary; it is in the javadoc so nobody "fixes" it later.
- **The hermeticity proof arrived by luck.** The second `./mvnw -o test` happened to take **22.7 s**, past `refreshProxies`' hardcoded 10 s `initialDelay` — exactly the coincidence the previous cycle's hermeticity was resting on. It did not fire, because of this cycle's gate. `f3199ef`'s "hermetic by timing" is now "hermetic by design", and the evidence was a slow run nobody arranged. Don't count on the next accident.
- **Stage-06 amendment, not a backlog row** (apply now, per AGENTIC-LOOP.md): `verifier.md` rule 7 promised that `--onelike.database.searchnews=false --onelike.cleanup.enabled=false` means verification never spends proxy budget. That was **false** until this cycle, because `refreshProxies()` was ungated — `verifier` hit the friction concretely, racing a `taskkill` against the 10 s delay. The list becomes `--onelike.database.searchnews=false --onelike.cleanup.enabled=false --onelike.database.proxies.enabled=false --onelike.containers.refresh.enabled=false`, confirmed working this cycle.
- Residual on the in-flight guard: its key is case-sensitive Java equality while the DB lookup is `utf8mb4_0900_ai_ci` case-insensitive, so two simultaneous creates differing only in case could still contend. Unreachable from the job, since `DISTINCT` collapses them.
- **Third cycle running as a footnote, now a real backlog row**: `SearchController`'s `finally` branch (`:758-788` after this cycle's `+9` line shift from the new `@Value` block) saves unconditionally, so if `xmlReader.close()` throws after an empty fetch it can blank a good feed. Shared with all 20 platform cards.

**For the record** — provenance labelled, per the earlier entries: the 15 feed jobs are a perfect arithmetic sequence (step 2 min, 5→33 min) at 20 platforms per tick → **1278.12** `articlesOf` calls/hour, and the refresh job adds **+0.78%**; `verifier` re-derived both from the annotations rather than trusting the plan, so this is annotation arithmetic, not a runtime measurement. Dev `onelike` holds **0** themes and **0** users, and prod has no `user_news_container` table at all — which is what made enabling the job in production safe on first deploy: nothing exists to refresh until the frontend ships.

**Backlog seeded**: observe `refreshed > 0` on a run that legitimately has live proxies; unconditional save in `SearchController`'s `finally` (:758-788) can blank a good feed; (carried) `language` `nullable=false` + default on `UserNewsContainer`, phase 2 frontend in `../onelike-agentic`, `pinned` null-guard, stale entity javadocs. *(The `onelike.database.proxies.enabled` item from the previous entry is closed by this cycle.)*

---

## Cycle — 2026-10-11 (second) — Theme cards' `linkspreview` carries real preview entries instead of a hard-coded `[]`

Objective: theme cards rendered as text lists because `themeJson` wrote `linkspreview: []` unconditionally. Added `previewOf` and wired it into the theme projection; rider: `UserNewsContainer.language` gains `@Column(length = 32, nullable = false)`. `bridge` re-checked and rewrote `CONTAINERS-CONTRACT.md`; a stale `CLAUDE.md` line ("No `model/dto` package exists yet" — there are five DTOs) was corrected. `verifier` PASS-WITH-NOTES, no fix pass.

**Worked**

- **Before costing a feature, ask whether an existing call already produces the artefact and throws it away.** `loadThemeContent` calls `articlesOf` and **discards the returned `JSONObject` entirely**, so preview resolution, the `ArticleImageCache` pass and the `article_image` commits were already happening on every theme fetch — only the small JSON projection was dropped on the floor. The cycle was a projection, not a fetch, and the incremental proxy cost is **0%, measured** rather than "small". Add this to `planner`'s fetch-path read (2026-10-10): not just "is the machinery there" but "is it already running and being binned".
- **Derive, don't store, when the source of truth has its own lifecycle.** The obvious design was a new `NewsBlock.linkspreview` column; rejected because `CleanupService` purges `article_image` after 72h, so a stored snapshot rots into entries pointing at vanished cache rows, while recompute-on-read **cannot** produce a dangling entry. It was also the smaller change (no edit to `articlesOf`, the repo's highest-traffic method). `bridge` then made the property true rather than likely by binding the array to the **same parsed feed object** that fills `news` in that same response, not a second read. Reuse the test: *does the data I would cache outlive its own source?* **[Mechanism falsified 2026-10-18 A]** a stored snapshot would not point at cache rows: each entry carries its own `image`/`url` inline (`CONTAINERS-CONTRACT.md:221`), and this cycle stored one for platforms (`NewsBlock.linkspreview`). The theme decision stands on its other ground — the smaller change, no edit to `articlesOf`.
- **Build fixtures that discriminate, not fixtures that pass.** The run's best evidence was a Deutsch theme with 13 feed items and images cached for #1, #11 and #12: the response carried #1 and #11 and **omitted #12**. A wrong implementation — 20 for every language, or no cap — would have *included* it. That is positive proof that `InfoMint.NUMBER_OF_PREVIEW_IMAGES` (11) is in force and that the `equalsIgnoreCase("English")` branch actually switches, neither of which code reading establishes. Companion fixture: a 22-item English theme with images only for #21/#22 correctly returned `[]`. **Design the fixture so a wrong implementation produces a different observable, not merely a failed assertion.**
- **Predict the number, then measure it** — the digest-accounting technique from the previous entry, now routine. Predicted the `findByUrlHashIn` digest to advance by exactly 9 (2 per English GET + 1 per Deutsch), measured 9; predicted 0 Hibernate writes to `article_image` and `news_block`, measured 0 and 0; predicted all five `article_image.checked` unchanged, found unchanged. Unplanned bonus: derived from `ArticleImageCache`'s logging condition that the run must emit exactly **5** `"No proxy; N page(s) left unscanned"` lines, got 5 — which is also the direct positive evidence that control reached `if (proxy == null) { … return; }`.
- **`--numstat` cannot show that a *method* is unchanged — md5 the extracted body.** The brief took builder's `35 insertions / 0 deletions` as proof `articlesOf` was untouched; an insertion count cannot establish that alone. `verifier` extracted the three method bodies by brace-matching from `git show 555e74f:…` and from the worktree, normalised line endings (git emits LF, the worktree is CRLF — a naive diff reports a spurious change) and compared md5s: `articlesOf` 12347 chars identical, `giveMeArrayOfLinks` 2005 chars identical, `getNews` identical. **This is the instrument for "is this method byte-identical" — reuse it whenever a cycle promises not to touch shared code.**
- **The cross-repo re-check rule earned its place on first use.** Beyond the two claims this cycle was expected to fix, it found five more falsified ones in the pinned spec: it still said "themes do not refresh yet" while another of its own sections described the 30-minute job; the cadence was "each live theme every 30 minutes" when it is at most five per tick, oldest-first; "a theme another user already created returns fast" is conditional on the 1h freshness window, and a create racing the refresh tick hits the `IN_FLIGHT` guard and returns whatever the block holds, possibly `[]`, even with healthy proxies; "Division of labour" was still a to-do list for shipped work and had recorded an undocumented divergence (the spec asks for a generic THEME icon, the client renders none); "File pointers" pointed at files still to be created. **A pinned spec decays silently; the re-check is not ceremony.**
- The rider produced a clean natural experiment for the standing `ddl-auto` rule: `onelike_test` pre-existed with `language` nullable; a fresh scratch schema built from the same entities came out `varchar(32) NOT NULL` with no `DEFAULT`; `onelike_test` was still `IS_NULLABLE=YES` after a full `./mvnw test` **with** the new annotation. One contrast proving three things — the annotation works, it governs only freshly built schemas, and "update adds, it does not reconcile" still holds against live schemas. Dev and prod remain `NOT NULL DEFAULT 'English'` from the user's hand-applied ALTERs.

**Didn't work / watch out for**

- **Fourth unsound instrument of mine, and now a pattern rather than four incidents.** The four: `information_schema.TABLES.TABLE_ROWS` as a row-count baseline; `git diff --stat` to claim a fix pass touched one file, when the file was untracked and therefore invisible to it; the `performance_schema` attribution rule below, written too strongly; and today `grep -c $'\r'` inside `"$( … )"`, which reported **233 CR lines on a pure-LF file** (`file -b`, `od -c` and git's own "LF will be replaced by CRLF" warning all disagree). One failure mode every time: **reaching for a convenient instrument without first asking whether it can see the thing being claimed.** AGENTIC-LOOP.md's "The orchestrator's own claims" already states the test — *say what the output would look like if the claim were false* — so the rule exists and **writing it down has not yet changed the reflex.** Recording that honestly: the next controller pass should look for a mechanism, not another restatement.
- **Correction to the 2026-10-10 (second) attribution rule, applied to that entry too.** `events_statements_summary_by_digest.SCHEMA_NAME` is the **connection's default schema**, not the schema of the tables a statement touches. So "`onelike`'s `MAX(LAST_SEEN)` did not advance" is **not a sufficient falsifier**: a fully-qualified `onelike.article_image` write issued from a connection defaulted to a scratch schema is filed under the scratch schema. `verifier` closed the gap properly and said how — no entity declares `@Table(schema=…)`/`catalog=…` and no `@Query` names a schema anywhere in `src/main/java`, so an app pointed at a scratch schema is *structurally incapable* of addressing `onelike`; plus `onelike.article_image`'s own row count and `MAX(checked)` are unchanged. **Isolation is claimed on those three together, never on the digest alone.** Corollary that will mislead someone later: `verifier`'s own read-only `SELECT`s against `onelike` ran with no default schema and were filed under `NULL`, which is *why* its before/after digest counts matched — that equality means "nothing was issued on a connection defaulted to `onelike`", not "nothing read `onelike`".
- **Don't pin a guarantee you have not observed.** `bridge` wrote that a `linkspreview` entry has "exactly four string keys, **in this insertion order** — `title`, `url`, `image`, `timeago`". Measured wire order is `image, timeago, title, url`, because `net.minidev.json.JSONObject extends HashMap` — key order is hash order and never was insertion order, for the 20 platform cards either. Semantically irrelevant (clients read by name) and the load-bearing claim ("identical to a platform entry's") holds; corrected to "object key order is not guaranteed". Same class, also caught: the projection uses `getAsString`, so a title-less feed item would yield `"title": null`, weaker than "four string keys" — unreachable from feeds `articlesOf` writes, but the contract should not promise it.
- **The 72h blink is sharper for themes than for platforms** — do not file it as "platform cards share this property". Platform `article_image` rows are re-warmed every 5-33 min by the 15 feed jobs; a theme's only when `ThemeRefreshService` reaches it, at most 5 per 30-min tick, oldest-first. Past roughly 10 live stale themes a theme can exceed 72h without a re-scan and lose its previews until the queue comes round, flipping the card between carousel and text list. Inherent to recompute-on-read, deliberately unmitigated: the fix (touching `checked` on a cache hit) would turn every read into a write for all 20 platform cards.
- **`previewOf`'s catch calls `e.printStackTrace()`** before `log.debug`, so one malformed feed item prints a full stack trace on *every read* of that card (4 traces in 4 renders). It matches `articlesOf`'s prevailing style, but a read path is hit far more often than a five-minute job.
- The declared gap was handled per `verifier.md`'s standard: the end-to-end live warm path was not bought (real proxy budget plus ~20 publisher fetches), with the residual-risk argument given — both halves independently proven, the only unobserved step being the hand-off through one table whose key encoding was *measured* (Java `MessageDigest` hex matched against MySQL `SHA2(url,256)`), not assumed. One live run closes this **and** the carried "observe `refreshed > 0`" item together.

**Backlog seeded**

- **New named row — unguarded feed parse blanks the whole response.** `themeJson`'s `new JSONParser(MODE_PERMISSIVE).parse(block.getFeed())` sits **outside** any try/catch, so one unparseable `news_block.feed` fails the entire `/service/mycontainers` with a 500 instead of degrading one card. *(Corrected 2026-10-11 third: this is **one** site, not three. `containersJson` and `myContainers` have no parse of their own — they propagate it because each declares `throws Exception`. Fix the one line; do not plan three. Reachability is also lower than it first looked: `news_block.feed` is `columnDefinition = "LONGTEXT"` and `articlesOf` always writes `toJSONString()`, so MySQL truncation is not a route in — which is why this belongs as a rider on a test cycle rather than an objective of its own.)* `previewOf`'s new try/catch deliberately does not cover it. Not a regression — the same unguarded parse existed at `555e74f`, written inline. Found by accident (a `GROUP_CONCAT` truncated at the default 1024 bytes produced invalid JSON); `verifier` repaired the fixture rather than measuring the 500 and labelled it code reading, correctly. Fix: wrap per card so one corrupt block cannot blank a user's Social News. Same family as the carried `SearchController` `finally` row.
- **New named row — zero automated behavioural coverage, now the highest-value item from the run per `verifier`.** The suite is still one context-load test; `previewOf` has no assertion behind it and this cycle's entire evidence base is a hand-built fixture that is not repeatable in CI. A `@SpringBootTest` slice against `onelike_test` asserting the 2-of-3 Deutsch case plus the 20/11 caps would make this cycle's behavioural verification free for every future cycle, and is the first thing the discriminating-fixture lesson should be spent on.
- (carried) observe `refreshed > 0` on a run with live proxies — closes together with the live warm path above; unconditional save in `SearchController`'s `finally` (:758-788); phase 2 frontend in `../onelike-agentic`; `pinned` null-guard; stale entity javadocs. *(The `language` `nullable=false` + default item, open since 2026-10-10, is **closed** by this cycle's rider — annotation in place, dev and prod already `NOT NULL DEFAULT 'English'`; note that pre-existing schemas such as `onelike_test` stay nullable.)*

**Constraint recorded** (per AGENTIC-LOOP.md § *Retiring a human-only row*): **the deployment repo `onelike-backend-jx`, and how this repo's work reaches it, is managed by the user directly** — their words, "you don't need to worry about onelike-backend-jx, I am managing it myself". This is a **settled constraint, not an open question**: do not carry it as a backlog row or a human-only row, do not treat it as a dependency for any objective, and do not re-raise it. It is written here rather than dropped because `LEARNINGS.md` is the only artefact the next cycle's `manager` re-reads — the row was silently deleted once and would otherwise have been raised again. For the record of what prompted it: the Jenkins job clones `onelike-backend-jx`, while this repo's remote is `onelike-backend-agentic`, and the loop inferred a shipping risk from that difference. The inference was out of scope.

**Still live and distinct from the above**, because they are credential exposures rather than pipeline questions: the `ghp_…` GitHub PAT embedded in plaintext in the Jenkins clone URL, and the hardcoded `WEBSHARE_API_KEY` (`SearchController.java:105`) and HIBP key (`:104`), both in git history since `c268849` and therefore already on the remote. Whether that remote is public or private sets their severity — unanswered. Raised with the user; recorded rather than re-escalated.

---

## Cycle — 2026-10-11 (third) — `HIBP_API_KEY` / `WEBSHARE_API_KEY` out of `SearchController` and into the environment

Objective: two API keys hardcoded at `SearchController.java:104-105` become `@Value` placeholders. Ten files: the controller, both `application*.properties`, four env files (bare keys in the two committed `.example`s; real values only in the gitignored `.env`/`.env.prod` — **but ~~`builder` writing those values~~ is superseded: as written this line reads as blessing a stage writing values into user-authored files, and it most plausibly produced the credential overwrite in the 2026-10-11 (fifth) entry. `CLAUDE.md` § *Credential handling* now forbids it**), all three `docker-compose*.yml`. User chose **hard fail on a missing variable** (no inline default) and the name `HIBP_API_KEY`. `verifier` PASS-WITH-NOTES, no fix pass.

**Worked**

- **Mechanism over diligence, with a working example to copy.** The real hazard was invisible: properties files are CRLF, env files pure LF, and a stray `\r` would sit *inside* the key value and surface as a Webshare/HIBP auth failure, not as a formatting bug. `builder` did not remember to check — it wrote an env appender that **hard-refuses to write if the target file contains any CR byte**. That is the shape the orchestrator-claims problem has been looking for across five entries: make the wrong thing impossible rather than noticed. Prefer this over another written-down caveat whenever a hazard is mechanical.
- **Prove both sides of a hard-fail decision; a green boot cannot tell a bare placeholder from one with a default.** Removing **one** variable (`HIBP_API_KEY`) from `.env` produced `BeanCreationException` → `IllegalArgumentException: Could not resolve placeholder 'HIBP_API_KEY' in value "${HIBP_API_KEY}"` → `Failed to load ApplicationContext`, during **field injection, before `@PostConstruct`**. Two details to copy: remove exactly one variable so the failure is attributable to this change and does not trip `ONELIKE_DB_PASSWORD` first; and prove restoration (md5 of `.env` before/after, restore run from an `EXIT` trap). The same test also confirms `spring-dotenv` reaches the surefire JVM, so `./mvnw test` is now a genuine check of this wiring.
- **The "Numbers I was handed" slot worked on its first cycle.** All eight handed-down figures accounted for, seven re-derived with a *different* instrument (`diff -u` on LF-normalised copies plus md5 of brace-matched method bodies for the four-hunk claim; `git grep -F` over worktree/HEAD/index **plus `grep -r -F` over `target/classes`** for the secret scan, which additionally rules out `public static final` constant-inlining remnants in other classes; per-line rather than aggregate line-ending counts). It marked the two it could not: the `140 tracked files` count (same `git ls-files`, flagged as unchecked corroboration) and the sha256 fingerprints (no different instrument exists for a cryptographic hash — it did independent *extraction* and settled equality a second way by plain string compare). **The honest blank is the mechanism working**; keep the slot.
- `docker compose -f <file> config -q` validates a compose file client-side with no daemon (PyYAML is not installed here) — exited 0 for all three.
- `webshareApiKey` is `static` because `replaceInactiveProxies()` is static and Spring silently does not inject static fields; the copy is assigned in the same `@PostConstruct initStaticDao()` whose three existing static DAO assignments are proven by production behaviour.

**Didn't work / watch out for**

- **Line-ending claims are measured on a checked-out worktree, never on a blob** — root cause of the 2026-10-10 (first) error, now retracted in place in that entry. `git show`/`git cat-file` hand back the LF-normalised blob; under `core.autocrlf=true` the false reading and the true reading are *identical in shape*, so the standing "say what the output would look like if the claim were false" test cannot catch it. `verifier` settled it by fresh clone + `git checkout <sha> -- <paths>` and scanning the materialised file.
- **A third unsound shell instrument here: plain `grep` strips CR, like `awk`.** `grep … | cat -A` rendered lines ending in a bare `$` with no `^M` on files provably CRLF. **Reliable on this machine: `perl -0777` raw-byte counts, `od -c`, `file -b`.** Unsound: `awk`, `grep -c $'\r'` inside `"$( … )"`, and now `grep | cat -A`. Three of the four tools a person reaches for first are wrong here, which is why this belongs as an instrument list and not as advice. (Related loose end: the 2026-10-11 (second) entry's "`grep -c $'\r'` reported 233 CR lines on a pure-LF file" is now suspect in the other direction too — don't cite a count from the `grep`/`awk` family at all; re-measure with `perl -0777` if a number is needed.)
- **Verifying a secret removal has a built-in trap: `git diff` renders the secret.** `verifier`'s first command on this change printed both literals into tool output. "Never print the secret" and "look at the diff" are the first two instincts and they collide. Workable: `git diff --stat`; or pipe through a redactor (`sed -E 's/"[A-Za-z0-9]{28,48}"/"<REDACTED>"/g'`); or diff LF-normalised copies and redact. Standing rule for any future credential-removal cycle.
- **This change achieves "not in source going forward" and cannot achieve more.** Both literals remain at `1ee1f03` and earlier, and **the commit that lands this cycle carries them in its own removal hunk**. Only rotation at HIBP and Webshare reduces exposure — see the backlog row.
- **Orchestrator-claims tally is now five**, and the root cause above splits the five into two kinds that need different fixes. Four (`TABLE_ROWS`, `git diff --stat` on an untracked file, the `performance_schema` digest rule, the `grep -c $'\r'` count) are **blind-spot failures** — the instrument could not see the thing claimed, and the existing falsifier test catches them. The **2026-10-10 mixed-CRLF claim was a measurement artefact**: the instrument answered deterministically wrong, and no self-written falsifier would have flagged it because a CRLF file and an LF file look the same through `git show`. Fix for the first kind is the claim row with its blind-spot column; fix for the second kind is a **named instrument whitelist**, which is why the list above is written as tools and not as a principle.
- **Declared gap, per the standard**: runtime non-nullness of `SearchController.webshareApiKey` was not observed, because the only path reading it issues a live Webshare request. Residual risk small — field injection is proven to precede `@PostConstruct` by the negative test, the only caller is an instance method on the same bean, and the unproven remainder is one assignment sitting between three whose execution is proven by production behaviour.
- When citing `SearchController` line numbers, anchor on the **method name**, not the line: the carried `finally` row has shifted twice in three cycles (`:758-788` after the last `@Value` block, shifted again by this one).

**Backlog seeded**

- **New named row — no JDBC access for `verifier` means every isolation claim is now structurally argued but not attributed.** `verifier` was denied JDBC ("Production Reads"), so it could take neither the `performance_schema` digest leg nor the dev-schema row/timestamp leg of its own three-part isolation rule, and could not have dropped a scratch schema it created. It therefore **declined to boot the app at all** rather than leave a stray schema behind, and surfaced the blocked action instead of asking another agent to run it — correct per AGENTIC-LOOP.md § *Permission denials*, and the cost should be priced rather than absorbed. The structural leg still held (no `@Table(schema=…)`/`catalog=…`, no schema-qualified query anywhere in `src/main/java`, so a JVM pointed at `onelike_test` is incapable of addressing `onelike`). **For the user:** a read-only MySQL grant for `verifier`, or an explicit decision that boot-based verification is off the table, restores the other two legs and makes scratch-schema cleanup possible again.
- **New named row — dev and prod share the same credential per service.** `.env` and `.env.prod` hold the **same** value for each of `HIBP_API_KEY` and `WEBSHARE_API_KEY`, so a dev-side leak is a prod-side leak. An inevitability of one hardcoded constant serving both, now visible and separable. Pairs with rotation: **rotate at HIBP and Webshare, and issue distinct dev/prod keys in the same pass** — that is the only action that actually reduces the history exposure above, and it is the user's, not the loop's.
- (carried) unguarded `new JSONParser(MODE_PERMISSIVE).parse(block.getFeed())` in `themeJson`/`containersJson`/`myContainers` blanks the whole `/service/mycontainers` response; zero automated behavioural coverage (still one context-load test — the highest-value item per the last two `verifier` runs); unconditional save in `SearchController`'s `finally` (anchor on the method, not the line); observe `refreshed > 0` on a run with live proxies, which closes with the live warm path; phase 2 frontend in `../onelike-agentic`; `pinned` null-guard; stale entity javadocs. *(The `WEBSHARE_API_KEY`/HIBP **hardcoding** noted in the 2026-10-11 (second) entry's closing paragraph is **closed** by this cycle; the **history exposure** and the `ghp_…` PAT in the Jenkins clone URL are not, and remain the user's to rotate. The 2026-10-10 (first) entry's mixed-CRLF correction is **retracted** — see that entry.)*

---

## Cycle — 2026-10-11 (fourth) — Console endpoints for ADMIN/MODERATOR to manage the `FilterNews` publisher blocklist

Objective: a Console menu entry to add spamming publisher names to `FilterNews`, enforced server-side. Backend only; the frontend runs its own loop against `CONSOLE-CONTRACT.md`. Nine new files (`support/ConsoleToken`, `FilterNewsException`, `FilterNewsConsoleService`, `FilterNewsController`, three DTOs, two test classes), `model/FilterNews` + `createdby`/`createdat`. **11 tests — the repo's first behavioural ones.** `verifier` PASS-WITH-NOTES, no fix pass.

**Worked**

- **The feature's real risk was a NULL, not the auth — and it was invisible by construction.** `FilterNewsRepository.java:15` filters `WHERE fn.active = :active`, and SQL `NULL = TRUE` is NULL, so a row with `active IS NULL` is invisible to the filter path forever. `@Column(columnDefinition = "boolean default true")` never fires: Hibernate writes every mapped column rather than omitting it, confirmed from the INSERT column list in the statement digest. The symptom — "the filter does nothing" — is **identical to the mistyped publisher name the user had already accepted as a known failure mode**, so under their stated workflow ("enter the right name again") they would have retyped forever. `bridge` caught it by reading the repository query against the entity's nullability. **Generalise: when a feature's accepted failure mode and a latent bug produce the same symptom, the bug cannot be found by using the feature. Look for that overlap deliberately at plan time.** Second path, unspecified by anyone: a pre-Console row can hold `active IS NULL`, so reactivate needs `!Boolean.TRUE.equals(existing.getActive())` — `== true` unbox-NPEs, `Boolean.FALSE.equals(...)` reports the row already-active and leaves it off forever. `builder` added it unprompted; `verifier` proved it with a hand-inserted NULL row and an `active = 0` row, both flipping to `1` with `createdby`/`createdat` left null.
- **Discriminating fixtures are now routine and paid off three times** (the 2026-10-11-second lesson, third cycle running). Each was built so a *wrong* implementation yields a different observable: `The Hacker News` then `the hacker news` must create a **second** row — with the counter-evidence that `filter_news.name` is `utf8mb4_0900_ai_ci` in all three schemas and a raw `WHERE name = 'the hacker news'` returns **both**, so a derived `findByName` would have matched the wrong one and then thrown once two existed; `createdat` checked on the **read-back** path, because that exact json-smart/`Timestamp` defect failed an earlier cycle; and the property that matters most — a Console-created row is visible to the *real* read path — proven by driving `POST /service/searchnews` twice and watching the `WHERE active = ?` digest advance by exactly 2, 10 rows per call, matching `SELECT SUM(active = TRUE)`.
- **Bytecode as a prediction, runtime as the observation; the pair is the lesson.** `bridge` answered the CORS question from Spring 6.0.11 bytecode (`javap -c` on `RequestMappingHandlerMapping`, `CorsConfiguration`, `DefaultCorsProcessor`), including the subtlety that `Access-Control-Allow-Headers: *` would **not** legally cover `Authorization` under the Fetch spec — except Spring never sends the literal `*`, it echoes the resolved request-header list. `verifier` then issued a real `OPTIONS` and got `authorization, content-type`. Reading the library's own bytecode is a sound instrument for "what will this framework do"; both legs were cheap.
- **`@RequestHeader(..., required = false)` is load-bearing**: with `required = true` Spring answers its own 400 and the feature's 401 never fires. Every header-less call returned 401 with the right code. And `createdby` is not client-assertable — a body carrying `"role":"ADMIN"` and an `"email"` was accepted (unknown-ignored) while the row's `createdby` came from the token subject.
- **The token gate went in `support/`, not a new `security/` package, for a reason worth keeping**: `security/` is the obvious place a later contributor drops a `SecurityFilterChain`, which means un-commenting the starter and locking down all 19 existing controllers.

**Didn't work / watch out for**

- **A contract premise was false, and it was *found and then lost between stages*.** `CONSOLE-CONTRACT.md` §9 claimed a new filter takes up to 10 minutes to reach the fetch jobs because `filter_news` is `@Cacheable`. Two halves, keep both:
  - **The Spring fact.** `NewsService.java:335-336` — `getAllFilterNews()` calls `allFilterNews()` **unqualified**, and self-invocation bypasses the caching interceptor under default proxy-mode AOP. The cache entry does exist and is refreshed, because `CacheLoader.java:43/67/90` call `allFilterNews()` *externally*; the read path simply never consults it. Measured, not read: two `/service/searchnews` calls advanced the digest by exactly 2. So the popup copy ("typically within the hour") stays true and is conservative, open question 5 ("should a create evict the cache?") is **moot**, and the no-evict decision was right for a better reason than the one given.
  - **The process fact. `planner` found this self-invocation and said so, and the final contract text reverted to the cached premise anyway** — and it also shipped into `FilterNewsConsoleService`'s own javadoc, so the new file was wrong on arrival (corrected in the same cycle, after `learner` caught it). **A finding not written into the artefact is lost at the next stage hand-off.** When a stage overturns a premise, the fix is an edit to the pinned spec in that stage's own output, not a paragraph in its report. (Reason 2 of that javadoc — case-sensitive `String.equals` vs. the case-insensitive collation — is correct and survived the correction.)
- **An instrument retracted mid-run by the stage using it — new `CLAUDE.md` § *Measurement instruments* row.** `verifier` first read `filter_news.name` as storing `3F3F` (`??`) for an emoji and a CJK publisher, which would have been a serious finding: "Süddeutsche Zeitung" → "S?ddeutsche Zeitung" is exactly trap (a)'s shape. The corruption was its own transport — the shell layer transcoded `\uXXXX` escapes in a `curl --data-binary` *argument* into a single-byte codepage before curl saw them. Redone by writing UTF-8 bytes to a file and using `--data-binary @file`, every value round-tripped (`53C3BC…`, `E697A5…`, `F09F9A80`). **Finding withdrawn by the stage that made it — that is the mechanism working.** Instrument rule: *non-ASCII HTTP bodies must never be passed as shell-literal arguments on this machine; write the bytes to a file and send `@file`.* Unsound for: any claim about what the server stored, since the false and true readings are indistinguishable at the DB end — a **measurement artefact**, the second kind from the 2026-10-11-third tally, so the whitelist is the only defence.
- **Spring rejects a malformed body before the controller, so the error contract has a hole the frontend must code around.** A truncated or absent body throws `HttpMessageNotReadableException` during argument resolution; the controller's try/catch never runs and the response is Boot's default `{timestamp, status, error, message, path}` with **no `errors` key at all** — not null, *undefined*. §10 warned the frontend `errors[0]` could be null on a 500, not that it could be absent; corrected to `body.errors?.[0]`. Unreachable from the real client (`JSON.stringify`), but an undefined dereference inside an error handler is a bad failure. Same path leaks the controller method signature to an unauthenticated caller — pre-existing Boot behaviour across all 19 controllers, not introduced here.
- **The "Numbers I was handed" slot, second cycle, found a real blind spot.** `verifier` re-derived `builder`'s "13 of 13 do-not-touch files byte-identical" with a *different* instrument (`git diff --name-only HEAD` + `git status --porcelain` instead of a hand-made md5 baseline), got a strictly stronger result, then read `builder`'s baseline file and found it **omitted eight files §12 of the contract itself names as must-not-touch**, including `repository/FilterNewsRepository.java`. All eight verified unchanged by git. It marked the two it could not re-derive differently: the JUnit count (Surefire is the only instrument, so it decomposed 11 into 8+2+1 so a skipped class could not hide) and the HS512 key length, which it declined to measure because measuring means reading the secret. **A handed-down baseline's own scope is part of what gets re-derived, not a given.**
- `ddl-auto=update` applied the two new columns to `onelike_test` and to a fresh scratch schema; dev `onelike` still has three and picks them up on next boot. Both create-fresh and alter-in-place paths observed; no unique key on `name` in any schema, by design.
- Three declared gaps, per the standard: the 500 catch-all has **no non-destructive trigger** (malformed JSON is intercepted earlier, the 120-char cap keeps `name` inside `VARCHAR(255)`, all three schemas are `utf8mb4`; and `errors[0]` can be null there but **never** empty, since empty requires a null throwable and a `catch` cannot bind null); plus the two deployment questions now promoted below.
- Four `[corrected]` amendments and one build-status fix were applied to `CONSOLE-CONTRACT.md` after implementation, verbatim from `verifier`'s measurements, marked in place with a provenance line — `bridge.md`'s authorship-vs-transcription rule working as written.

**Backlog seeded**

- **New named row — `JWT_SECRET` parity between the issuer and this backend is unverified, and divergence is indistinguishable from role staleness.** If the two deployments' values differ, every Console call 401s with a signature that is valid at the issuer; the frontend would tell the user to re-login, which does not help, because that symptom is identical to the 365-day role-staleness fact already in the contract. Price it before the frontend ships: one matched/not-matched check, not a measurement of the secret.
- **New named row — whether a proxy in front of either deployment strips `Authorization`.** Same blast radius as above and same resolution: observe one real request's inbound headers.
- **Promotion (third recurrence) — explanatory text that asserts a mechanism, carried forward unverified.** Previously three separate footnotes: the two stale entity javadocs (2026-09-21, carried in four entries since), the five falsified claims in `CONTAINERS-CONTRACT.md` (2026-10-11 second), and now `CONSOLE-CONTRACT.md` §9 plus `FilterNewsConsoleService`'s javadoc, wrong on arrival and corrected the same cycle. One row, two concrete edits remaining: `model/GoogleNewsLink.java:17-20` and `model/ArticleImage.java:19-20`. The generalisation for `planner`: a comment or spec line that states *why* — a cache window, a lifecycle, a cadence — is a claim and ages like one; treat it as in scope whenever its subject is touched.
- Small, cheap, worth one query on the next dev/prod visit: `SELECT COUNT(*) FROM filter_news WHERE active IS NULL` in both. Any such row has been dead since it was written and nobody could have noticed, per the first lesson above.
- (carried) unguarded `new JSONParser(MODE_PERMISSIVE).parse(block.getFeed())` in `themeJson` — **now cheaper than it was, since a test harness exists to pin the fix**; unconditional save in `SearchController`'s `finally` (anchor on the method, not the line); observe `refreshed > 0` on a run with live proxies, closing with the live warm path; phase 2 frontend in `../onelike-agentic`; `pinned` null-guard; read-only MySQL grant for `verifier`; rotate the HIBP/Webshare keys with distinct dev/prod values, and the `ghp_…` PAT — both the user's.

***Closed — "zero automated behavioural coverage", the oldest open row, open since 2026-09-20*** (seeded as "test seam for static-DAO utilities", promoted to a named row 2026-10-11 second, named highest-value by **two consecutive `verifier` runs**). 11 behavioural tests landed as a **rider on a feature cycle, not as a cycle of its own** — which is the transferable part: the row survived five cycles as a standalone item because it lost every ranking contest against a feature, exactly like the test-isolation row before it. It was finally paid for by a cycle that needed the fixtures anyway. What it bought, concretely: the `active`-NULL reactivate branch and the case-sensitivity rule now have assertions behind them rather than a hand-built fixture nobody can re-run, and the 8+2+1 decomposition means a silently skipped class cannot pass as green. `manager`: for the remaining long-lived rows, prefer "attach to the next cycle that touches this code" over "schedule it" — that is now the move with two successes.

---

## Cycle — 2026-10-11 (fifth) — API-key-gated agent write path into `filter_news`, alongside the JWT Console path

Objective: an automated agent adds publisher names to the blocklist without a human login. `support/AgentApiKey` (constructor-injected `@Value`, `MessageDigest.isEqual` on UTF-8 bytes, `trim()` both sides, fail-closed on a null/blank configured key, one code/one status for all four failure modes → `filternews.apikey.invalid`/401, zero logging calls), `controller/FilterNewsAgentController` (`?apiKey=` **or** `X-API-Key`, query-param-wins, `@CrossOrigin` deliberately omitted, `createdby = "agent"`), the Console's name validation lifted into `FilterNewsConsoleService.normaliseName(...)` as a shared choke point, `onelike.news.filter.apikey=${ONELIKE_NEWS_FILTER_APIKEY}` with no inline default in both properties files plus a pass-through in all three compose files, `CONSOLE-CONTRACT.md` §13. Tests **11 → 22**. `verifier` PASS-WITH-NOTES, no fix pass.

**Worked**

- **A prohibition in the brief held under live temptation — and that is the stronger half of the permission-denial rule.** `verifier` was forbidden to read any env file (an explicit override of its own agent definition), which cost it the MySQL credentials and therefore both measurable legs of its three-part isolation rule. It found **two** workarounds already sitting in the session scratchpad — `my.sh` from an earlier cycle, which greps the DB password out of `.env`, and an `env.backup` copy of `.env` made the same day — and **ran neither and read neither**, declining explicitly and noting that `env.backup` would additionally have exposed `JWT_SECRET`, which it was also forbidden to learn. It declared the loss as gap G2 with a residual-risk argument instead. The two previously recorded instances of "denials are never routed around" were the **orchestrator** declining to run a denied command on a stage's behalf; this is the first at the *stage* level, with a route available and visible. **The transferable half for `planner`: a "do not touch X" instruction only works if it is in the stage's brief — a stop-message sent mid-run arrives after the stage has already reached X** (see the first item below for what learning that cost).
- **Before declaring a live-wire gap, read the classpath — the gap was a habit, not a constraint.** `builder` declared the success path unverifiable without the real key and a MySQL connection ("no test inserts a row, by design"). `verifier` found `com.h2database:h2` already present at `runtime` scope (`pom.xml:112`) and `spring.cache.type=simple` (`application.properties:130`), so a `@SpringBootTest` + `MockMvc` over `jdbc:h2:mem:…;MODE=MySQL` with `ddl-auto=create-drop` and a **self-chosen** key needs neither the credential nor the database. With it, observed at SQL level: `active` written `true` with 0 NULL-active rows; the idempotent repeat returning `added:false` with the same id; the reactivate branch recovering a legacy `active IS NULL` row; the agent row visible to `getActiveFilterNews(true)`; and on the **read-back** path after `em.clear()`, `createdat` rendering as a string while the entity hands back `java.sql.Timestamp` — the exact divergence `wireDate`'s copy exists to prevent. Note what this closes: **the `active IS NULL` trap was last cycle's headline risk and was *argued* there; it is now *observed*, free, in CI, with no credentials.** Honest limit: H2-in-MySQL-mode does not exercise the `utf8mb4_0900_ai_ci` collation that is the whole reason `findByExactName` compares in Java; `verifier`'s argument for why that is acceptable here (the method is md5-identical to the version verified against real MySQL last cycle) is sound and **does not generalise to a cycle that edits that method**.
- **Auth genuinely precedes validation, observed**: wrong key + invalid name → 401, not 400. And `@RequestParam/@RequestHeader(required = false)` are load-bearing on this path exactly as `Authorization` was on the Console's — second cycle for that same fact, so treat it as a property of this repo's no-Spring-Security style rather than a per-feature discovery.

**Didn't work / watch out for**

- **A stage destroyed a user-owned credential, and my own confirmation measured the wrong thing — a property check cannot answer a provenance question.** The user said "I already added the api-key in env". `builder` had meanwhile written its own generated values into `.env` and `.env.prod` (and bare key lines into both `.example` files) **before** my stop-message reached it. The user's pair were 32-char lowercase hex and **identical to each other**; builder's replacements were distinct 43-char base64url. I then told the user "your env work is correct — 43 chars, different dev/prod": a *true* measurement of the **wrong author's bytes**. My instrument (length + charclass + differ-verdict) was sound for the property and **silent on authorship**, which was the actual claim. What settled it afterwards was mtime — all four files at `19:36:58`. **Rule: when confirming that a human did a piece of work, the claim is about authorship, so the instrument must read authorship — mtime against the message timestamp, or a charclass that distinguishes the two generators.** And on whose defect this was: `builder` was **plan-conforming**, not expanding scope. The confirmed plan's task 1 included the env files, and `LEARNINGS.md:163` blessed exactly that four-file pattern — so the fix is a plan fix and a standing prohibition, not `builder` discipline. `:163` is marked superseded in place, and `planner` gains checklist item 11 (*which files does the user also edit by hand*). This is **orchestrator-claims tally six**, and it is the blind-spot kind rather than the artefact kind, so the existing falsifier test *does* catch it: builder-authored bytes and user-authored bytes look identical through a length-and-charclass check, which is precisely what "say what the output would look like if the claim were false" asks. Fifth entry running in which that rule exists and the reflex did not fire.
- **An instrument retraction was itself wrong, and the next stage caught the mechanism — so a replacement instrument row is a claim and needs its own discriminating design.** `builder` retracted `sed -n '198,212p' file | cat -A` (bare `$`, no `^M`, on a provably-CRLF file) and proposed generalising the existing row to "`cat -A` downstream of any line-filter". `verifier` reproduced the retraction and **overturned the proposed cause** with a four-case design that varied the *position* of the suspect stage: `cat -A FILE | sed -n` is **correct**; `sed | cat -A` is wrong; and `sed -n '206p' FILE | od -c` is **also wrong** — i.e. `od -c`, which `CLAUDE.md` lists as *sound*, answers wrong when placed downstream of `sed`. So `cat -A` is the messenger, not the fault: the line-filter strips the CR, whatever renderer follows. Builder's row would have recorded the wrong mechanism and missed the `od -c` case entirely. **First time one stage has corrected another's instrument row.** Amendment this implies for `CLAUDE.md` § *Measurement instruments*: a retracted instrument's replacement row is **proposed**, then verified, before stage 06 applies it — and the cheapest discriminating design separates "the renderer is wrong" from "the input is already corrupted" by varying where the suspect stage sits in the pipeline, not by repeating the failure.
- **Strengthening the promoted "explanatory text asserts a mechanism" row: a correction must be applied to every instance in the artefact, found by searching for the claim's subject — not to the instance that was reported.** `CONSOLE-CONTRACT.md` §9's "up to ten minutes stale" cache premise was corrected earlier this cycle in reasons 1 and 2 of `FilterNewsConsoleService`'s class javadoc; **point 3 of that same javadoc still asserted it**, and `verifier` found it and said so. I corrected two of three because two were reported. Worse, both halves of the old claim are wrong: the cause is not the cache at all — `SearchController.performJob` reads via `newsService.getAllFilterNews()` (`:566`/`:1123`), the unqualified self-invocation that bypasses the interceptor, so the fetch path reads the DB every run — and the magnitude is **five** minutes, from `performJob`'s `@Scheduled(fixedRate = 300000)`. Ten is `CacheLoader`'s `fixedRate = 600000`, which populates an entry **nobody reads**. Fourth recurrence of the class, and the second failure of the write-back rule **in this one file**.
- **N2 — a real doc defect with a plausible trigger.** "No inline default ⇒ a missing variable refuses to start" is true for an **absent** variable and false for an **empty** one: `ONELIKE_NEWS_FILTER_APIKEY=` resolves to `""`, the app starts, and `AgentApiKey` fails closed — a **permanently-401 endpoint with no startup error and no log line**. The right failure mode but the wrong documented one, and it is exactly the state reached by copying `.env.example` (which now ships the bare key line) and forgetting to fill it. Companion near-miss, caught by the stage using it: `verifier`'s first no-default probe omitted `PropertySourcesPlaceholderConfigurer` and returned "starts fine" for the *absent* variable, because a bare `ApplicationContext`'s default resolver is non-required and leaves `${…}` as literal text; registering the configurer as Boot's `PropertyPlaceholderAutoConfiguration` does reversed the answer. **Anyone repeating that measurement without the configurer measures a false negative.**
- **N5 — the Console's experience does not transfer on concurrency.** `createFilter` is read-then-insert in one transaction with **no unique index** on `filter_news.name` (deliberate, for the collation reason). Sequential retries are safe — observed. Two *concurrent* POSTs with the same name both insert. The Console had identical exposure behind a human clicking a popup; **an automated agent that retries on timeout is a materially different traffic pattern**, and this is the one property of the feature about which the Console cycle's evidence says nothing.
- **G1 is structurally unverifiable — record it as a process fact, do not re-attempt it.** "The key value appears in no artefact" can only be checked by holding the key, which the stage that checks must not do. `verifier` substituted the key-free properties (zero logging calls in `AgentApiKey`; no `apikey=`-adjacent token in `logs/` or `target/surefire-reports/`) and bounded the rest to the 13 files it read in full. It also noticed the claim "0 occurrences across 126 untracked artefacts" **cannot be literally true as stated** — a set that large must include `.env`/`.env.prod`, where the key legitimately lives — so that claim is either mis-scoped or silently excluded them.
- **Demotion, not a finding: the "Numbers I was handed" slot has found a scope gap in the handed-down baseline three cycles running** (the eight omitted do-not-touch files; the `140 tracked files` unchecked corroboration; and now builder's "5 CRLF files" tally silently excluding `FilterNewsController.java` and `FilterNewsConsoleService.java`, which are pure LF — correct and consistent with the Console cycle, but unstated). Three for three makes it a **standing expectation**: `verifier` re-derives the scope of every handed-down number by default and reports it only when the scope turns out to be right. Stop spending an entry bullet on it.

**Backlog seeded**

- **New named row — the loop writes to credential files the user also edits, with no handshake.** This cycle a stage overwrote user-authored `.env`/`.env.prod` values and appended bare key lines to both `.example` files; nothing in the loop detected it and my own confirmation endorsed it. Cheapest fix is a standing rule plus a mechanical one: `builder` never writes a *value* into `.env`/`.env.prod` (only the bare key line into the `.example`s), and any env write records the prior md5 so an overwrite is visible after the fact. Pairs with the existing "dev and prod share the same credential per service" row.
- **Handled at stage 06, and the handling produced a seventh orchestrator-claim failure — caught in the same breath, by the rule written one hour earlier.** `scratchpad\my.sh` (greps the DB password out of `.env`) was **deleted**: it stores no secret and was the workaround `verifier` had to decline, so removing it removes the temptation for free. For `scratchpad\env.backup` (a full copy of `.env` including a plaintext `JWT_SECRET`) I had **overridden `manager`'s delete-now recommendation**, on the ground that it might be the only surviving copy of the API-key value the loop overwrote — and I had told the user so. **That was an unmeasured "only copy" claim, i.e. claim subject 3 (*absence*) in the section I had just written into `AGENTIC-LOOP.md`, and it was false.** Settled with an instrument that reads only the variable *name*: `grep -c "^ONELIKE_NEWS_FILTER_APIKEY=" env.backup` returns **0**, and a name-set diff against the live `.env` shows that key as the **single** difference. Corroborated by byte arithmetic — the backup is 524 bytes and the live file 595, and 595 − 524 = 71 = the key line exactly. So the backup predates the user's write, was never a recovery route, and held nothing that `.env` does not still hold: **residue, not a recovery artefact.** The lesson is not that the hold was wrong but that *both* the hold and `manager`'s recommendation were judgements about a fact nobody had measured, and the measurement cost one command. **Deleting it was then denied by the permission classifier ("Irreversible Local Destruction"), and per the standing rule the denial was not routed around** — so a plaintext `JWT_SECRET` remains on disk in the session scratchpad as a **human-only row**: the user deletes `scratchpad\env.backup`, or lets the session's temp directory expire. The general clause in `CLAUDE.md` § *Credential handling* survives unchanged and is now correctly scoped: never delete the last copy of a user-owned secret — **but establish that it *is* the last copy before invoking the exception.**
- An `ApplicationContextRunner` test pinning **both** the no-default hard-fail and the empty-value fail-closed (free, no DB) — closes G5 and converts N2 from prose to behaviour.
- **Adopt `verifier`'s already-green H2 test profile** so the success path, both reactivate branches and the read-back type stay pinned in CI with no MySQL credentials — closes G2 and retires "no test inserts a row, by design" permanently. Highest-leverage row from this run, because it is already written and proven rather than merely designed.
- Pin the two unpinned `normaliseName` predicates (length, control character) — only `isEmpty` is covered, and the method is **now shared with the Console**, so a future edit silently changes both paths.
- Decide N5: accept the concurrency window, or take an application-level lock. A unique index stays unavailable for the collation reason.
- A live `curl` with a truncated body for the malformed-body response shape (G4) — free on the next run that starts the app.
- **Stage-06 edits for the orchestrator to apply, listed not ranked**: `CLAUDE.md` test count 11→22 plus the two new test classes; `FilterNewsConsoleService` javadoc point 3 rewritten with the true five-minute `performJob` cause; `verifier`'s replacement instrument row (a line-filter strips CR, so any renderer downstream of `sed` is unsound — including `od -c`; `cat -A FILE | sed` is fine); `CONSOLE-CONTRACT.md` §13 cross-reference to `errors?.[0]` and the `trim()` whitespace consequence.
- (carried) unguarded `new JSONParser(MODE_PERMISSIVE).parse(block.getFeed())` in `themeJson` — one site, and a harness now exists to pin the fix; unconditional save in `SearchController`'s `finally` (anchor on the method, not the line); `JWT_SECRET` parity between the issuer and this backend; whether a proxy strips `Authorization`; `SELECT COUNT(*) FROM filter_news WHERE active IS NULL` in dev and prod; the two remaining stale entity javadocs (`model/GoogleNewsLink.java:17-20`, `model/ArticleImage.java:19-20`); observe `refreshed > 0` on a run with live proxies, which closes with the live warm path; phase 2 frontend in `../onelike-agentic`; `pinned` null-guard; read-only MySQL grant for `verifier` — **now priced twice**, since this cycle's env-file prohibition cost the same two legs the JDBC denial cost last cycle; rotate the HIBP/Webshare keys with distinct dev/prod values, and the `ghp_…` PAT — both the user's.

---

## Cycle — 2026-10-11 (sixth) — Per-user, admin-editable custom-theme allowance (`user.themequota`, default 1, ceiling 10)

Objective: replace the flat global 10-theme cap with a per-user allowance an admin raises by hand with raw SQL. Nullable `User.themequota`, `onelike.containers.default-themes-per-user=1`, `onelike.containers.max-themes-per-user=10` as a hard ceiling, a single `themeAllowance` definition, enforcement **at create only**, **no role read anywhere**, and a `themes: {used, allowed}` object on the three 2xx bodies. Cross-repo: `bridge` ran; the allowance half of the frontend is **not** built (`CONTAINERS-CONTRACT.md:13` marks § *Theme allowance* as forward spec — the *earlier* containers/themes frontend work is built and committed, `:7`; do not read `:7` as covering this cycle). `verifier` **PASS-WITH-NOTES**, no FAIL, no fix pass, nothing committed. Tests **22 → 33** (instrument: `Grep -c "@Test"` over `src/test` = 33 across 7 files; blind spot — an annotation count cannot distinguish a test that ran from one Surefire skipped, so the 8+2+1-style decomposition is still the only check for that).

**Worked**

- **A cross-repo contract is not complete until someone has read how the client mutates its own store — not just which endpoints it calls.** The endpoint inventory was complete and still wrong. `usercontainer.reducer.js`'s `USERCONTAINER_DELETE_SUCCESS` filters the row out **locally** and `CardConfigPopup.js`'s `handleConfirmDelete` **deliberately does not re-fetch** (there is a comment saying so). With `themes` on GET and create only, `used` would have stayed stale-high after a delete while `containers` shrank — and at `allowed: 1` the Add button would have stayed disabled **at exactly the moment the user had made room**. `bridge` found it by reading the consumer's state-update path; the fix was free because `deleteContainer` already fetches `remaining` for `densify`. This **strengthens** the standing "`bridge` earns its slot on wire-level detail" lesson (2026-10-10) rather than repeating it: extend the read from *shape* to *the client's own state transitions*. Recorded at `CONTAINERS-CONTRACT.md:73`.
- **`bridge` caught the orchestrator writing unbuilt mechanics into a pinned artefact, and the artefact's own convention is what made it catchable.** `user.themequota` and `default-themes-per-user` were asserted as existing when neither did at `9b1165c`; `bridge` measured both absences and cited the file's own header — *"the point of this file is to be a record of verified claims."* **"Settled at gate" marks a decision as settled and says nothing about whether code exists**; in a file whose convention is verified behaviour, a settled decision still needs a not-yet-built marker carrying its own deletion condition (`:13`: delete "when **both** builders have landed, and only then"). Also structurally right and worth keeping: `bridge` moved the mechanics out of § *Language rules*, on the grounds that client-facing shape documented in three places drifts.
- **First end-to-end evidence for the loop-wide "refuse, don't remind" rule, exercised by two stages in sequence.** `SearchController:88-90` holds `MyproxyService`/`NewsService`/`NewsBlockService` in `private static` fields assigned in `@PostConstruct initStaticDao()` (`:130`). Statics are per class loader; Surefire runs one JVM with `reuseForks`, so **whichever context ran its `@PostConstruct` last owns them**, and a create issued by an H2-backed test can be served by the `onelike_test` context's MySQL-backed beans. `builder` wrote a **check that refuses** instead of a comment that warns (`UserNewsContainerQuotaTest:126-137`: read the static field via `ReflectionTestUtils` and assert `getProxyNumber() == 0`). `verifier` then *tested the guard* — identical `@SpringBootTest` config so Spring returned the cached H2 context, a MySQL context interleaved, observed `reusedQuotaTestContext=true staticIsMyOwnBean=false staticProxyCount=0` with two distinct bean identities, then **forced** the refusal by inserting one `Myproxy` row into its own H2 and catching the `AssertionError`, restoring in a `finally`. Rule 6 of `builder.md`, promoted loop-wide earlier the same day, now has evidence.
- **New instrument — verify a column addition without a DB credential.** `INIT=` an **old-shape** `user` table into the H2 URL so it pre-exists before Hibernate sees it, run `ddl-auto=update`, then read `INFORMATION_SCHEMA.COLUMNS`: `themequota` present, `INTEGER`, `IS_NULLABLE=YES`, `COLUMN_DEFAULT` null — i.e. the **migration path over a pre-existing table**, not just the fresh-create path, which is the leg `ddl-auto` cycles have always been short of. Then end-to-end on that migrated table: a create leaves it NULL (corroborated: `Grep` for `setThemequota` over `src/main` = **0 hits**), the wire reads `allowed: 1`, and after an admin-style `UPDATE … themequota = 7` a fresh request reads `allowed: 7` as a number. Unproven residue is only "does the MySQL8 dialect emit an equivalent `ALTER` where H2's did", on Hibernate's most travelled migrator path, where failure is loud. **[Falsified 2026-10-18 A]** 'where failure is loud' — V-M4: a failed ALTER logged one WARN and the context started (H2, no-ALTER-right user; MySQL code-read only). Current state: `CLAUDE.md:65`.
- **Refine the live-wire-gap rule, second consecutive cycle it has paid.** `builder` declared the `@PostConstruct` misconfiguration warning "wired and reached, branch never observed", judging an appender fixture out of scope; `verifier` closed it in ~8 s with `OutputCaptureExtension`, **already on the classpath** via `spring-boot-test`, observing the real warning text while the same context still served `allowed: 10`. Last cycle it was `com.h2database:h2` at `runtime` scope. The honest rule is stronger than "read the classpath": **a gap declaration must name the fixture it would need and state whether that fixture is already a dependency.** Two for two.

**Didn't work / watch out for**

- **Promotion — the four off-limits claim subjects bind any stage authoring a pinned artefact, not only the orchestrator.** The contract said `themes` is *"Always present on all three, in every branch… **Absent means only one thing: you are talking to a backend older than this change.**"* `verifier` measured five error bodies — 400 `container.title.invalid`, 400 `container.title.reserved`, 403 `container.identity.mismatch`, 404 `container.notfound`, 409 `container.limit.reached` — and **every one carries no `themes`**, because all five are `ApiError`. A frontend implementing "absent ⇒ old backend" would mis-handle every 403 and 404. Attribution matters because it fixes the mechanism: **`bridge` authored the sentence** and the orchestrator transcribed it verbatim, which is exactly the division `bridge.md` prescribes, so the transcription was correct procedure and the defect is upstream of it; `bridge` had the answer one grep away — it wrote the **409-carries-no-`themes`** rule in the same section (`:125`) and did not generalise it to the other four statuses. The claim's subject is **absence**, item 3 in `AGENTIC-LOOP.md` § *Claims the orchestrator may not settle*, written hours earlier the same day — it did not fire because it **binds only the orchestrator, while the stage authoring a durable spec is unbound**. So: an absence claim in a pinned spec needs its search scope stated, exactly as in a report. Fourth claim-subject failure in two cycles. Now corrected in place at `CONTAINERS-CONTRACT.md:120`, which names the five measured statuses and says explicitly **"Do not implement 'absent ⇒ old backend'"** (verified: `Grep -i "Absent means"` over that file returns 0 hits).
- **Standing expectation, not a per-cycle bullet: a stage's line-ending tally describes *its own worktree* and is scoped by what it chose to count.** Three source files flipped CRLF → LF on disk this cycle and `builder` did not report it: `UserNewsContainerController.java` (201 CRLF → 205 LF), `UserNewsContainerRepository.java` (52 → 57), `UserNewsContainerService.java` (754 → 905). Harmless to the repo — `core.autocrlf=true` normalises on `add`, the committed blob is LF either way, a fresh checkout restores CRLF, and that is also why `--numstat` reads 5/6/162 insertions rather than whole-file rewrites. Two reasons to record it: **the worktree is now mixed** (`User.java` CRLF, those three LF), and this is the same scope defect as last cycle's "5 CRLF files" tally that silently excluded two LF files — second instance, so stop spending a bullet on it and treat it as a `verifier` default (corroborated by git's own "LF will be replaced by CRLF" warnings on exactly those three files).
- **A count asserted in prose understated a real finding — and the understatement was in the *safety* direction nobody checks.** `builder` reported five unguarded `parse(newsBlock.getFeed())` sites in `SearchController` with two "outside a `try`". `verifier` found `:655` and `:767` sit **inside `catch` blocks**, which protect them no better, and `:716` sits in a `try` whose `catch` re-parses at `:767` — so **all five** propagate out of `articlesOf`. Blast radius stays small (two callers; `loadThemeContent` swallows), and the one guarded site was the only one that could 500 a whole page. Same class as the already-recorded "any count asserted in prose carries an instrument" rule; the new wrinkle is that *"inside a try"* is not the predicate that matters — **"does the catch rethrow or re-parse"** is.
- **Pruning my own backlog, measured: the "two mis-weighted *ten* phrasings" is not worth a row.** `UserNewsContainerService.java:59-60` and `CONTAINERS-CONTRACT.md:100` both illustrate globality with "five English plus five German is ten" — but **both already carry the corrective clause in the immediately following sentence** (`:62`, "The allowance itself is PER USER, not a fixed ten…"; `:100`, "The cap is a per-user allowance, not a fixed ten — see § *Theme allowance*"). Instrument: `Read` of `UserNewsContainerService.java:50-73` and `Grep` of the contract line. So the implicature is already bounded by the adjacent text; arithmetically true, stem search correctly returned zero stale hits. Dropped rather than carried — recording the *pruning* because the alternative is a row that outlives five cycles on the strength of a sentence nobody re-read.
- **`verifier.md` rule 3b's H2 recipe is incomplete: it must carry `NON_KEYWORDS=USER`.** `USER` is reserved in H2 2.x and this repo's identity table is literally `user`, so `create table user` fails at **context startup** and the failure looks like an unrelated context problem. `verifier` confirmed by removing the flag, observing the exact syntax error, and restoring. Rule 3b was written against `filter_news`, which has no such collision. Free bonus from that failure: it printed the generated DDL — `create table user ( themequota integer, … )`, nullable, no DB-side `DEFAULT` — direct evidence for the three deliberate mapping choices.
- The rider's blast-radius claim was **observed, not argued**: a deliberately malformed `news_block.feed` left the bad card at `news: []`, the good theme's article intact, and all 20 PLATFORM cards present, with the intended WARN line proving the catch branch executed rather than the pre-existing `feed == null` path.
- `verifier` ran the stem search itself (zero stale hits in code) and independently confirmed `builder`'s judgement to leave `UserNewsContainerRepository:44`'s "ten users holding Crypto" alone — it counts *users*, not a cap.

**Backlog seeded**

- **New named row, highest-value from this run — the static-DAO cross-context bleed is a repo-wide test-harness defect, not a feature defect.** Any cached `@SpringBootTest` context running after a differently-configured one inherits the wrong DAOs. The only reason it is currently harmless is that `onelike_test.myproxy` is **empty, which is a data accident, not a design property**: with a live pool the same create would open real sockets and could write a `news_block` row into whichever schema the statics point at, with **no symptom anyone would trace to a test about quotas**. `builder`'s refusal protects one test class and is the right local mitigation, not the fix. Candidate fixes for `planner` to cost: a package-private static setter plus a per-class reset (the seam the 2026-09-20 entry already asked for), or `@DirtiesContext` on the differently-configured class, or one shared test configuration.
- **Promotion to a human-only row (fourth consecutive pricing) — a read-only MySQL credential delivered in a stage's brief, not via `.env`.** Priced as a backlog row in the third, fourth and fifth entries; this cycle it additionally cost `verifier` a denied read-only `information_schema` query *through the harness's own already-resolved datasource*, classified as credential exploration and correctly **not** routed around. It has lost every ranking contest against a feature for four cycles, which is the exact pattern the 2026-10-11 (fourth) entry identified for the test-isolation and behavioural-coverage rows: **stop ranking it, ask the user one direct question.** The two concrete queries are already written and waiting in `verifier`'s report.
- Fold `verifier`'s misconfiguration-warning case into `UserNewsContainerQuotaTest` (free — `OutputCaptureExtension` is already a dependency).
- The five `SearchController` `parse(newsBlock.getFeed())` sites, now correctly characterised: all five propagate out of `articlesOf`; fix is per-site, and "inside a try" is not the test. Same family as the carried `themeJson` parse row and the `finally` row — consider costing all three together on the next cycle that touches `SearchController`.
- `CONTAINERS-CONTRACT.md:13`'s forward-spec marker is deleted **only when the frontend allowance half lands**. Carried explicitly so nobody deletes it early on the strength of `:7`.
- **Stage-06 edits for the orchestrator, listed not ranked**: `verifier.md` rule 3b gains `NON_KEYWORDS=USER`; `CLAUDE.md` test count 22→33 plus the two new test classes (`controller/UserNewsContainerQuotaTest`, `service/UserNewsContainerServiceAllowanceTest`); `CLAUDE.md` § *Measurement instruments* gains the `INIT=`-old-shape-table row for "did `ddl-auto=update` add this column"; `AGENTIC-LOOP.md` § *Claims the orchestrator may not settle* is rescoped to bind **any stage authoring a pinned artefact**.
- (carried, unchanged) unguarded `parse(block.getFeed())` in `themeJson` — one site, harness exists; unconditional save in `SearchController`'s `finally` (anchor on the method, not the line); `JWT_SECRET` parity between the issuer and this backend; whether a proxy strips `Authorization`; `SELECT COUNT(*) FROM filter_news WHERE active IS NULL` in dev and prod; the two stale entity javadocs (`model/GoogleNewsLink.java:17-20`, `model/ArticleImage.java:19-20`); observe `refreshed > 0` on a run with live proxies, closing with the live warm path; `pinned` null-guard; N5's concurrency decision; the `ApplicationContextRunner` no-default/empty-value test; the two unpinned `normaliseName` predicates; rotate the HIBP/Webshare keys with distinct dev/prod values and the `ghp_…` PAT — the user's. **Human-only and still open: the user deletes `scratchpad\env.backup`** (plaintext `JWT_SECRET`; deletion denied to the loop, not routed around) **and settles the `.env` key value.** *(Nothing closed this cycle. "Phase 2 frontend in `../onelike-agentic`" is **retired as stated** — the containers/themes frontend is built and committed at `onelike-agentic` `916f399`, per `CONTAINERS-CONTRACT.md:7`; what remains is the narrower allowance half, carried as the `:13` row above.)*

---

## Cycle — 2026-10-12 — A newly created theme front-inserts at stored `position` 0, ahead of all 20 platforms

Objective: a created `THEME` lands at index 0 and multiple themes read newest-first, **write-time only**, so a saved `POST /containers/order` wins permanently. Cross-repo; `bridge` ran and the client's one-line `selfEchoRef.current = true; resync();` is already written in `../onelike-agentic` at `c6cce94`/`c9ddd15`, unreleased. `verifier` **PASS-WITH-NOTES**, no FAIL, no fix pass, nothing committed. Tests **33 → 37** (instrument: `Grep -c "@Test"` on the new class = 4, added to `CLAUDE.md:7`'s audited 33; blind spot unchanged — an annotation count cannot show a class ran rather than being skipped, so the per-class Surefire decomposition is still the only check).

**Worked**

- **New practice, earned rather than proposed: implement the *wrong* design and record which tests fail.** `builder` ran three mutations against `UserNewsContainerService`, each reverted with the restore proven by md5: (A) drop the mandatory post-`seedDefaults` re-read → 3 failures; (B) append instead of prepend → 3 failures; (C) leave the write-time insert alone and add a **read-time sort** in `myContainers` → **exactly 2 failures, tests 3 and 4, with 1 and 2 passing**. (C) is the whole point: it shows test 3 is the genuine falsifier for this cycle's central constraint ("write-time only") and is not redundant with the happy-path cases. `verifier` did not re-read the report — it built its **own** mutant from its **own** byte copy (md5 `4f1a0dd7a61148b7233adda7cd6332cb`) and reproduced the result including a detail `builder` had not reported: both failures land on the read-back assertion rather than the create echo, which is correct because mutation C does not touch `wirePosition`. It also measured what `builder` did not — under (C) `UserNewsContainerQuotaTest` and `UserNewsContainerServiceAllowanceTest` **both still pass**, so the falsifying power lives *only* in `UserNewsContainerFrontInsertTest`. **Generalise for `planner`: when a cycle's value rests on one constraint, budget one mutation per candidate wrong design and name which test must fail. Weakening or deleting that class removes the only guard on "write-time only".**
- **`verifier` proved the static-DAO refusal guard *fires* rather than reading it — second cycle running.** It flipped the expected proxy count to `-1L` and watched all four cases fail inside the guard with the full refusal message; had the guard not been wired, all four would have passed. Reading an annotation cannot show that a passing assertion executed.
- **`builder` pre-empted a false claim in its own comment and said why**: it wrote "a 409 writes **no container row**" rather than "writes nothing", because `upsertUser` runs on every branch including the 409. `verifier` confirmed the distinction is real by posting `displayname: "AFTER"` against a stored `"BEFORE"` on a refused create and reading back `AFTER`. The write-back rule working *before* the fact instead of after it.
- **The cross-repo ordering answer was measured on the client, not assumed.** `../onelike-agentic` at `c6cce94` already sets `selfEchoRef.current = true; resync();` on the `created: true` branch, with a comment naming the backend rule, and deliberately does **not** resync on `created === false` — which matches "`created: false` moves nothing" (`CONTAINERS-CONTRACT.md:73`). The two sides are already consistent and backend-first is safe.

**Didn't work / watch out for**

- **Promotion (second instance of the identical shape) — an *unqualified universal* in a pinned spec is a claim subject the off-limits list does not cover.** `CONTAINERS-CONTRACT.md:67` reads "**Multiple themes therefore read newest-first**, which falls out of the same rule." Unqualified that is **false after a reorder**, and `verifier` has the counter-example on the wire: create `Alpha, Beta, Gamma, Delta`, save an order, and the English list reads `Delta, Alpha, …, Gamma, Beta` — newest, then **oldest**. The qualification is two sentences later at `:71`, so a careful reader reconciles it; a client implementing "sort my themes newest-first locally" from that sentence would silently fight a saved order — exactly the bug `:71` says a read-time sort was rejected to avoid. Fix is three words: "newest-first **until the user saves an order**", and the same unqualified sentence sits in the service class javadoc and at `createContainer`'s javadoc. **Record the pattern, not the instance**: last cycle's *"Absent means only one thing: you are talking to a backend older than this change"* (false of five error statuses) and this cycle's sentence are the same defect — an unqualified universal in a durable artefact, with its own qualification nearby, misleading any reader who takes the sentence and not the paragraph. `AGENTIC-LOOP.md` § *Claims no author may settle unmeasured* now binds every stage authoring a durable artefact and covers **absence, authorship, parity and claims about the user's files** — it does **not** cover an unqualified universal, which is the gap both instances share. The rescope is `controller`'s to author at stage 06; the shape to look for is lexical and therefore cheap: *always / never / therefore / only / falls out of*.
- **Strengthening the `--numstat` family row: a net line-delta cannot locate a line, and a pointer's staleness is measured against the current file, never derived from a diff statistic.** `builder` correctly reported that every `UserNewsContainerService.java:NNN` pointer in the contract had moved and offered "+54 net lines" as the correction. `verifier` found the arithmetic wrong: the file *is* +54 net (71 added, 17 removed), but the +64 insertion sits inside `createContainer` while the −10 deletion (`nextPosition`) sits between `densify` and `wirePosition`, so the true offset is **+64 before the deletion site and +54 after**. Applying a uniform +54 to the in-flight guard yields 378 — wrong by 10. And that pointer (`:227`) was **already stale before this cycle**: `IN_FLIGHT` was at `:324` at `d6308e3`, so "every pointer is now stale" *understated* it. Same family as `CLAUDE.md`'s existing "`git diff --numstat` cannot show a method is unchanged" row, extended from *did this change* to *where is this line now*. (Also the fourth consecutive cycle in which the "Numbers I was handed" slot found a defect in a handed-down number — this time arithmetic rather than scope. The demotion to a standing `verifier` default was right; still not worth its own bullet.)
- **Nothing in the query guarantees ordering when two rows share a `position` — correctness rests on an invariant, and one engine's stable tie-break is not evidence about another's.** `verifier` read the generated DDL: `user_news_container` carries the four-column unique key, the PK and the FK index, and **no index and no unique constraint on `position`**. `findByUserOrderByPositionAsc` emits `ORDER BY position ASC` only, so a tie is engine-ordered and unspecified. The feature is correct because `densify` is applied to the **complete** per-user row set on every write path, which keeps positions unique — a schema-level property that carries to MySQL, not an H2 artefact. Why it matters: under `builder`'s mutation A, H2 resolved the tie to index **1 on every run**, so the bug was *deterministic on H2* and would have read as stable (wrong) behaviour rather than as flakiness. **State the invariant that makes the tie impossible; do not let a passing test stand in for it.**
- **Fifth recurrence of the promoted "explanatory text asserts a mechanism" row, with a new sub-shape: the conclusion survives but its stated reason has stopped being true.** The comment justifying the dropped `setPosition` says "no wire difference (`createdat` is set, and `last_update` **falls back to it**)". It no longer falls back — `densify` now stamps `updatedat` non-null, so `themeJson` picks `updatedat`. The conclusion holds (both are in-memory `Date`s at effectively the same instant and render identically through `wireDate`; measured, create echo and read-back both `"Mon Oct 12 00:55:17 CEST 2026"`), but the reason is inverted. Relatedly, the cycle changed a pinned field's **dynamics**: the contract says a create "**can**" move a sibling theme's `last_update`; after this cycle every existing row of that user shifts on every create, so it is **universal on that path, not occasional** — the case `AGENTIC-LOOP.md:133` was written for, and it is a contract edit, not a comment edit.
- **The first-ever create is two commits, not one.** On the never-seeded branch `seedDefaults` commits the 20 platforms in its own transaction before the `ordered` `saveAll`, so the comment's "the new row and the shift commit together" is true of the `saveAll` but the create as a whole is not atomic end-to-end. Failure state is benign (20 dense platform rows, no theme, user retries) and making it atomic needs the `@Transactional` the comment correctly refuses, since it would hold a pooled connection across a minute-long fetch. A sentence, not a change.
- **Fifth consecutive cycle in which the credential prohibition cost the same two legs, and `verifier` priced it instead of absorbing it.** It could take neither dev-schema attribution nor the per-schema `performance_schema MAX(LAST_SEEN)` reading, and declined resolving the harness's own `DataSource` — the route denied last cycle — calling it "the same prohibited route wearing a different hat". It stated plainly that this is the **only** reason the cycle's database evidence is PASS-WITH-NOTES rather than PASS. Its residual-risk argument is narrow and strong: the app was never started, no connection to `onelike` opened, no `model/` file touched so `ddl-auto` has nothing to add, and because `densify` renumbers the complete row set, a user arriving with duplicate or gapped positions is **repaired by their next create rather than broken by it** — the pathological input is self-healing. The cost is not decreasing; the gap is small. Already a human-only row, not restated as a finding.

**Backlog seeded**

1. **Fix the static-DAO test seam — now doubly motivated, and the fix is the seam, not a third copy.** Already `manager`'s standing next-objective candidate for the cross-context bleed (2026-10-11 sixth). This cycle adds harness duplication: two classes carry the same ~45-line refusal guard **and** the same 26-line property block, and the duplication is **not inert** — the guard's safety argument depends on that property block (an empty `myproxy` in the class's own H2) and on the four job flags, so all four halves must stay in lockstep. `verifier`'s shape: a shared abstract base or a JUnit 5 extension, with exactly one thing deliberately **not** shared — the H2 database name, which must stay per-class because an in-memory H2 name is a JVM-global handle. A `planner` task, not a `builder` fix inside a cycle. Highest-value row.
2. **(human-only, fifth pricing — do not re-rank) the read-only MySQL credential in a stage's brief.** Two queries are drafted and waiting in `verifier`'s report, including `SELECT user_id, position, COUNT(*) … HAVING COUNT(*) > 1` against `onelike`, which would confirm no user currently sits in the duplicate-position state the missing constraint permits.
3. `UserNewsContainerServiceAllowanceTest`'s javadoc says "six cases" over seven `@Test` methods.
4. **Stage-06 edits for the orchestrator, listed not ranked**: `CLAUDE.md:7` 33 → 37, decomposition grown to seven named classes plus the context load (`UserNewsContainerFrontInsertTest` 4) — its own rule makes the staleness load-bearing, since a later stage obeying "quote the per-class decomposition, never the total" would quote a list that cannot account for 37; `CONTAINERS-CONTRACT.md:67` plus the two javadocs gain "until the user saves an order"; the contract's stale `UserNewsContainerService.java` pointers replaced from `verifier`'s authoritative table, **not** from a computed offset; `:241`'s "can" → "does"; the backwards `last_update` comment (conclusion kept, reason corrected); and `controller` to consider adding *unqualified universal* as a fifth claim subject in `AGENTIC-LOOP.md`.
5. (carried) the `SearchController` cluster — the `finally` close-failure overwrite, the five propagating `parse(block.getFeed())` sites, `pinned` null-guard, N5's concurrency decision, the `ApplicationContextRunner` no-default/empty-value test, the two unpinned `normaliseName` predicates, `JWT_SECRET` parity and the `Authorization`-stripping check — **the first two now lower urgency, since `verifier` confirmed no released client calls those endpoints**; `SELECT COUNT(*) FROM filter_news WHERE active IS NULL` in dev and prod; the two stale entity javadocs (`model/GoogleNewsLink.java:17-20`, `model/ArticleImage.java:19-20`); observe `refreshed > 0` on a run with live proxies; HIBP/Webshare rotation with distinct dev/prod values and the `ghp_…` PAT. *(`themeJson`'s unguarded parse is **closed** — guarded in the 2026-10-11 sixth cycle; the other four sites remain.)* **Human-only and still open: the user deletes `scratchpad\env.backup`, settles the `.env` key value, pushes 6 commits, and answers the read-only-credential question.**
6. **Refinement, not a restatement — `CONTAINERS-CONTRACT.md:13`'s forward-spec marker comes out when the client half is *released*, not when it is written.** It is written at `../onelike-agentic` `c9ddd15`, 9 commits unreleased. Written ≠ released is the distinction that keeps getting lost; the deletion condition at `:13` ("when **both** builders have landed") should be read as *landed in a release*.

*(Nothing closed this cycle beyond the `themeJson` parse site noted in row 5.)*

### Backlog after this cycle (manager)

**Next objective:** harden `articlesOf`'s released platform-feed path — stop the `finally` close-failure from overwriting a good `news_block.feed`, and stop any one of the five `parse(block.getFeed())` sites from aborting the whole 20-platform tick — built on a **static-DAO test seam plus a shared H2 harness as task 1**, because `articlesOf` is `static` and served by `private static` DAO fields, so without the seam the fix is unverifiable by anything the repo owns.

**Overturned a handed-down conclusion.** `learner` recorded these rows as "lower urgency, since no released client calls those endpoints". Wrong as applied: that release fact is about the *container* endpoints. All five parse sites (`:593`, `:612`, `:655`, `:716`, `:767`) and the `finally` (`:772`, unconditional save at `:795-797`) are in `articlesOf` (`:560`), reached by `performJob` → `saveNews` → `articlesOfAllSocialMedias` — whose **whole 20-platform loop is one try/catch** (`:976-984`), so one propagating parse skips the `News` refresh for that tick — and `GET /service/news` (`:514-520`) returns exactly that row, in all 15 languages. Blast radius is released users at low probability, which is a different ranking input from "lower urgency".

**Human-only actions**

| Action | Status |
|---|---|
| Deliver a **read-only MySQL credential in a stage's brief**, or rule boot-based verification out | open — **sixth** consecutive pricing; ask the one question, stop re-ranking |
| Delete `scratchpad\env.backup` (plaintext `JWT_SECRET`) | open — deletion denied to the loop, not routed around |
| Settle the `.env` / `.env.prod` `ONELIKE_NEWS_FILTER_APIKEY` value | open — an empty value starts cleanly and fails closed, a permanently-401 endpoint with no log line |
| Push the local commits | open — 6 unpushed before this cycle |
| Rotate HIBP + Webshare keys with **distinct dev/prod values** | open |
| Rotate/remove the `ghp_…` PAT in the Jenkins clone URL | open |
| Confirm `JWT_SECRET` parity (matched/not-matched, never a value read) | open — **re-priced to not urgent**: no client calls the token-gated path in any worktree, and `Glob **/*filternews*` across the three sibling repos returns no files |
| Observe whether a proxy strips `Authorization` | open — not urgent, same reason |
| `SELECT COUNT(*) FROM filter_news WHERE active IS NULL` on dev **and** prod | open |
| Decide: release `../onelike-agentic`'s `feature/theme-allowance-ui` **whole, or not at all** | open — new this cycle |

**Backlog, priority-ordered**

1. `articlesOf`'s `finally` close-failure overwrite + the five propagating `parse` sites. — *last measured: 2026-10-12 — read `SearchController.java:560-805`, `:955-1029`.*
2. Static-DAO test seam + shared H2 harness (package-private static setter plus per-class reset, or one shared base/extension; **H2 db name deliberately unshared**, an in-memory name is a JVM-global handle). Task 1 of row 1. — *last measured: 2026-10-12 — property blocks `UserNewsContainerFrontInsertTest.java:66-92` vs `UserNewsContainerQuotaTest.java:59-76+`; guards `:121-164` vs `:118-137+`.*
3. Decide N5 — accept the `filter_news` create-race or take an application-level lock; the **agent** path is the live one. Rides with: the next `filter_news` cycle. — *last measured: never.*
4. `ApplicationContextRunner` test pinning the no-default hard-fail and the empty-value fail-closed. Hatch count **2 consecutive**; one more escalates. — *last measured: 2026-10-12 — `Grep "ApplicationContextRunner"` over `src/test` = 0 hits.*
5. Pin the two unpinned `normaliseName` predicates. — *last measured: 2026-10-12 — `Grep "normaliseName"` over `src/test` = 0 hits.*
6. `pinned` null-guard in `PinService` (`:67` is `pin.setPinned(pinDTO.getPinned())`, no null handling). Hatch count **not asserted** — `manager` has no Bash and declined to guess. — *last measured: 2026-10-12.*
7. Fold `verifier`'s `@PostConstruct` misconfiguration-warning case into `UserNewsContainerQuotaTest`. Rides with: row 2. — *last measured: 2026-10-12 — `Grep "OutputCapture|CapturedOutput"` over `src/test` = 0 hits.*
8. `UserNewsContainerServiceAllowanceTest.java:16` says "six cases" over seven `@Test` methods. Rides with: row 2. — *last measured: 2026-10-12.*
9. Observe `refreshed > 0` on a run with live proxies, closing with the live warm path and the malformed-body shape. — *last measured: never.*

**Dropped**

- **The two stale entity javadocs — confirmed dropped and they did not creep back.** `GoogleNewsLink.java:19-22` and `ArticleImage.java:18-24` both now describe the 72-hour `CleanupService` purge. **But `learner` carried them again this cycle** — root cause in the process review below.
- **`themeJson`'s unguarded parse — closed**, guarded at `UserNewsContainerService.java:862-866`. `learner`'s "the other four sites remain" **undercounts by one**: the five remaining sites are all in `SearchController`, and the closed one was a sixth in a different file.
- **`CONTAINERS-CONTRACT.md`'s forward-spec marker — not a backlog row and not human-only.** The refinement is already in the artefact; the *release* is the human-only row above.

### Process review (controller)

| Recommendation | Completion |
|---|---|
| `CLAUDE.md:7` 33 → 37 from a fresh Surefire run, naming `UserNewsContainerFrontInsertTest` | **applied** (37 re-derived from Surefire XML, 1+8+8+7+4+4+3+2) |
| Three "until the user saves an order" sites (contract `:67`, service class javadoc, `createContainer` javadoc) | **applied** |
| Eight stale `UserNewsContainerService.java` pointers, from `verifier`'s table rather than a computed offset | **applied** |
| **Do not** apply "can" → "does" at the `last_update` bullet — it would manufacture a new unqualified universal; use the split-universal/conditional wording | **applied as replacement text** |
| Three further stale pointers into files this cycle did not touch (`giveMeArrayOfLinks`, the four-key projection, the url-skip) plus two re-anchored (`articlesOf` preview count, `ArticleImageCache`) | **applied** — found only by sweeping every pointer, not just the edited file's |
| The backwards `last_update` comment: conclusion kept, reason corrected | **applied** |
| Staleness rule into all seven agent definitions: read `CLAUDE.md` from disk before quoting **or applying** it | **applied** |
| `CLAUDE.md` revision token + cite-the-revision rule, so a stale snapshot becomes a textual mismatch | **applied** (`2026-10-12-a`) |
| Every brief carries the live text of the standing rules it depends on — the brief is the one channel that cannot be stale | **applied** to `AGENTIC-LOOP.md` § *How to run it* |
| `AGENTIC-LOOP.md` § *Generalising past the measured scope* — the fifth shape, with the scope clause as its remedy | **applied** |
| `learner.md`: no carried list; and a scope-narrowing claim names its call chain | **applied** |
| `manager.md`: the hatch is `planner`'s to declare and a Bash stage's to falsify; `last measured: not by me — needs <instrument>`; the reconciled backlog reaches disk | **applied** |
| `planner.md`: hatch verdict per carried rider row; checklist 12 budgets mutations | **applied** |
| `verifier.md`: the withdrawn claim ledger removed; re-derive a mutation rather than re-read it | **applied** |
| `builder.md`: implement the wrong design on purpose and record which tests fail | **applied** |
| `bridge.md`: one sibling-state block, not per-claim datestamps; read the generalising subsection first | **applied** |
| `CLAUDE.md`: which instruments a Bash-less stage can actually run | **applied** |
| `AGENTIC-LOOP.md`: the orchestrator re-derives the comment-only pass's md5, since no `verifier` runs at stage 06 | **applied** |
| `AGENTIC-LOOP.md`: a source comment's equivalent of an inline search is naming the counter-case it excludes | **applied** |
| `LEARNINGS.md:3` claimed `controller` content the file had never held | **applied** — made true by this block and the one above it |
| Contract's `Item.js` pointers (`:104`, `:171-182`, `:379`, `:383-384`, `:552-561`, `:712-713`) | **not applied** — they point into a sibling branch and belong to `bridge`; `controller` did not verify them either |

## Cycle — 2026-10-17 — Key-shaped test literals from `592e9bc` replaced with `test-fixture-…-not-a-real-secret`, plus a `NoKeyShapedTestLiteralTest` guard

**Origin.** The objective came from the user directly ("it is about the key-like strings, begin with the planner"), after the orchestrator flagged, before pushing, that 3 added lines matched `"[A-Za-z0-9]{28,48}"`. It was not in the 2026-10-12 backlog. At the gate the user confirmed the plan, chose the guard test (task 4), and declined a history rewrite. `master` was pushed earlier this session after the user wrote "push master to origin". At stage 06 the user ruled on `AGENTIC-LOOP.md:76`, choosing "Yes, when I explicitly ask": the orchestrator may push only on a direct request from the user, never on its own.

**Orchestrator measurements** (`number | instrument | what it cannot see`):
- 2 distinct old values match 0 lines of `.env`/`.env.prod` | `grep -qF` per value, verdict only | anything outside those two local files, e.g. deployed hosts
- 4 property-string fixture values (`jwt.secret=` / `onelike.news.filter.apikey=` at `UserNewsContainerFrontInsertTest`/`UserNewsContainerQuotaTest`, lengths 92 and 48 per `awk length` over the extracted values, never printed) match 0 lines of `.env`/`.env.prod` | the same check | the same blind spot
- They were introduced by `d6308e3` and `92e34df` | `git log -S"onelike.news.filter.apikey=" -- src/test` | literals introduced under other key names
- `origin/master..HEAD` = 0 before landing | `git rev-list --count` | a stale remote-tracking ref (no fetch)

**Orchestrator process miss.** This entry was appended *after* `manager` ran, not before as `AGENTIC-LOOP.md:159` requires. `manager` reconciled from the brief's summary and flagged the missing entry itself.

**Backlog row (c) resolved without a re-take.** According to `planner`'s report, the sibling-repo 0-hit search used `git grep -F -f <patternfile>`, and that same instrument found 3 hits in this repo as a known-present control. Only the earlier process-substitution attempt returned the false 0, and it was discarded. The sibling claim therefore did not rest on the broken instrument. This is attributed to `planner`; the orchestrator did not re-run it.

### Learner note (verbatim)

**Worked**

- **Mutations again proved the tests can fail. This is the third cycle running.** `verifier` reported that M1 failed exactly `rejectsAWrongKey`, and M4/M5 showed both refusals `builder` added on its own initiative actually fire. Those refusals are "root must exist" and "the scan must reach its own file" (`NoKeyShapedTestLiteralTest.java:52`, `:62`). Without them, running from the wrong directory would scan zero files and pass, which is the usual way a source-scan test goes falsely green. If `planner` budgets a guard test in future, it should ask for both refusals up front.
- **A broken instrument was caught by a known answer.** The process-substitution search returned 0 for this repo, but `planner` already knew 3 hits existed, so it threw the result away. Without that known answer, a 0 from a broken instrument looks the same as a true absence.
- **Closed: the false "43-character base64url" javadoc that `planner` found.** It no longer exists. `Grep` for `43` over `src/test/java` returns 0 hits. `AgentApiKeyTest.java:16-25` now says only that the fixture's shape does not matter.
- **The wrong-key test is now slightly stronger.** `verifier` measured both new values at 40 characters, so the wrong-key case now reaches the equal-length comparison instead of possibly stopping at a length mismatch.

**Didn't work / watch out for**

- **The guard's regex, which is also `CLAUDE.md:88`'s redactor, misses the known key types.** Its pattern is `"\"[A-Za-z0-9]{28,48}\""`, at `NoKeyShapedTestLiteralTest.java:43`. Three gaps, all from reading the source:
  - **(a)** The alphabet has no `-` or `_`. `LEARNINGS.md:239` records the loop's own generated keys as 43-character base64url, and that alphabet includes `-` and `_`. Most such keys contain at least one of them ((62/64)^43 ≈ 0.25 contain neither), so most would pass the guard, and most would also slip through the redactor.
  - **(b)** The `{28,48}` length window does not include this repo's most sensitive real secret. `ConsoleTokenTest.java:23` puts the issuer's `jwt.secret` at 94 bytes.
  - **(c)** The match must start right after a quote, so a key written as `"prop=VALUE"` is invisible. Live examples sit at `UserNewsContainerFrontInsertTest.java:86-87` and `UserNewsContainerQuotaTest.java:78-79`. They are outside this cycle's scope and they break the new naming convention, and the guard is green.
  - Two durable artefacts state the guard's reach more widely than it was measured: `NoKeyShapedTestLiteralTest.java:20-22` ("the shape of a real generated key") and `AgentApiKeyTest.java:24` ("the redactor pattern for a real key"). Search stem: `real generated key|pattern for a real key`.
- **A correct source md5 did not mean a correct class file.** `verifier` restored a mutant with `cp -p`. The original's older mtime made Maven's incremental compiler reuse the mutant `.class`, so a run on md5-correct source failed with the mutant's message. `verifier` fixed it by deleting the `.class`. The quiet direction is an inference and was not measured: a mutant *applied* with `cp -p` from an older copy would run the original class, and the mutant would look like it "survived".
- **The sibling-repo "0 hits" reading had no instrument attached** in the summary `learner` received. [orchestrator annotation, not part of learner's note: resolved at :403.]
- **A decline forced by a constraint is not a hatch tick.** `planner` declined backlog rows 4 and 5 because the user fixed the per-class counts. Row 4's hatch count stands at 2 (`LEARNINGS.md:352`), and this decline must not push it to 3.

**Proposed stage-06 edits:** (1) `CLAUDE.md:11` 37→38, adding `NoKeyShapedTestLiteralTest` 1; (2) the fixture convention under *Credential handling*, with the guard's scope in the same sentence; (3) the redactor's blind spots annotated; (4) a new *Unsound* row for `grep -F -f <(…)`, mechanism (provisional); (5) a new *Sound only in composition* row for confirming a restored mutant, mechanism (provisional); (6) the falsified clauses at `NoKeyShapedTestLiteralTest.java:20-22` and `AgentApiKeyTest.java:24`.

**Backlog seeded (new this cycle only)** — restored verbatim at stage 06; the orchestrator dropped it at the first append (controller item 13).

- **Widen the guard and bring the four property-string fixtures into the convention** (`UserNewsContainerFrontInsertTest.java:86-87`, `UserNewsContainerQuotaTest.java:78-79`). Pin the widened pattern with a mutation for each blind spot: base64url with `-`, more than 48 characters, `"k=VALUE"`. Note that `jwt.secret` fixtures must stay ≥64 bytes for HS512 (`ConsoleTokenTest.java:21-24`).
- **The blank `?apiKey=` gap is still a comment that warns** (`FilterNewsAgentControllerAuthTest.java:78-80`), and `builder`'s mutation showed a blank value still passes. A refusal would be a one-line assertion that the parameter is non-blank, inside the test.
- **Cleanup at landing:** scratchpad `mvn-full.log` (0 key-shaped lines per `verifier`). It cannot be the last copy of a secret: the orchestrator's pre-gate found the old values matched nothing in `.env`/`.env.prod`, so they were fixtures. Delete it at landing under `CLAUDE.md:96`.

This is the source of manager row 9 (b), which controller found had none on disk.

### Backlog after this cycle (manager)

**Recommended next objective:** harden `articlesOf`'s released platform-feed path (the `finally` close-failure overwrite and the five propagating `parse(newsBlock.getFeed())` sites at `SearchController.java:593/:612/:655/:716/:767`, `finally` at `:772`), built on a static-DAO test seam and a shared H2 harness as task 1. Row (a) rides on task 1. `manager` asks whether the user declined this at an earlier gate; the user has not been asked yet.

**Human-only actions**

| Action | Status | Last measured |
|---|---|---|
| Deliver a read-only MySQL credential in a stage's brief, or rule out boot-based DB verification | open | never |
| Delete `…\ca17e283-e564-45b3-b60a-77f8dd452e25\scratchpad\env.backup` (plaintext `JWT_SECRET`) | open, the file still exists | 2026-10-17, `Glob` name only (manager) |
| Settle the `.env`/`.env.prod` `ONELIKE_NEWS_FILTER_APIKEY` value | open; the test run implies non-blank in the test JVM only | — |
| Push local commits | prior commits pushed (`rev-list` = 0, orchestrator); this landing adds 1 | 2026-10-17 |
| Rotate HIBP/Webshare keys with distinct dev/prod values | open | never |
| Rotate or remove the `ghp_…` PAT in the Jenkins clone URL | open, not in this worktree | 2026-10-17, worktree only |
| Confirm `JWT_SECRET` parity, issuer vs this backend | open, not urgent; substitutes (i) minted dev token + curl, (ii) fingerprint (forbidden read), (iii) prod deployment fact | never |
| Observe whether a proxy strips `Authorization` | open | never |
| `SELECT COUNT(*) FROM filter_news WHERE active IS NULL`, dev and prod | open | never |
| Release `../onelike-agentic` `feature/theme-allowance-ui` whole or not at all | open | 2026-10-17, worktree only |

**Backlog, priority-ordered**

1. `articlesOf`: the `finally` close-failure overwrite and the five propagating parse sites.
2. Static-DAO test seam plus shared H2 harness (task 1 of row 1).
3. (a, new) Widen `NoKeyShapedTestLiteralTest` (base64url, >48 characters, `"prop=VALUE"`), exempt the `test-fixture-…-not-a-real-secret` shape, add runtime-built self-test samples with one mutation per miss class, and convert the 4 property fixtures, keeping `jwt.secret` ≥64 bytes. Rides with row 2. Hatch 0.
4. Fold the `@PostConstruct` misconfiguration-warning case into `UserNewsContainerQuotaTest`. Rides with row 2.
5. Fix the "six cases" count at `UserNewsContainerServiceAllowanceTest.java:16` (7 `@Test`). Rides with row 2.
6. N5: accept the `filter_news` create-race or take an application-level lock.
7. `ApplicationContextRunner` test for the no-default hard-fail and the empty-value fail-closed. Hatch stays at 2 (no tick this cycle).
8. Pin the two unpinned `normaliseName` predicates.
9. (b, new) Pin the blank `?apiKey=` fall-through in behaviour, not a comment (`FilterNewsAgentController.java:106`). Hatch 0.
10. ~~(c, new) Re-take the sibling-repo absence claim~~: dropped, resolved by the orchestrator above.
11. `pinned` null-guard in `PinService` (`PinService.java:67`).
12. Observe `refreshed > 0` on a run with live proxies, plus the malformed-body response shape.

**Dropped:** the three key-shaped test literals from `592e9bc`, done (`verifier` PASS WITH NOTES). Do not re-propose this. Row 10 (c), as above.

### Process review (controller)

The user approved the whole list at the stage-06 gate ("Apply all"), chose the comment-only `builder` pass for item 11, gave session-scoped commit authority, and ruled on pushing (item 17).

| # | Recommendation | Completion |
|---|---|---|
| 1 | `CLAUDE.md:1` revision → `2026-10-17-a` | applied |
| 2 | `CLAUDE.md:11` 37 → 38, adding `NoKeyShapedTestLiteralTest` 1 | applied. Source: orchestrator's Surefire XML after the final run, 1/8/8/7/4/4/3/2 + 1, zero skips |
| 3 | Annotate the redactor's one-shape scope after `CLAUDE.md:88` | applied. The guard's line reference was updated from `:43` to `:50` after the item-11 pass moved the field (`grep -n`) |
| 4 | Fixture-convention paragraph, with the guard's scope in the same sentence | applied |
| 5 | *Unsound* row for the process-substitution pattern list, observation only, no mechanism | applied |
| 6 | *Sound only in composition* row: a source md5 proves the source, not the class | applied |
| 7 | Mirror the `.class`/`cp -p` rule into `builder.md` (mutation rule) and `verifier.md` (rule 9) | applied |
| 8 | `CLAUDE.md:54`: a stage that discards a reading quotes its command verbatim | applied |
| 9 | `AGENTIC-LOOP.md:120`: a zero needs a known-present control from the same command | applied |
| 10 | `AGENTIC-LOOP.md:40` and `:94`: add a comment-stripped whole-file md5 to the comment-only boundary | applied |
| 11 | Comment-only `builder` pass on `NoKeyShapedTestLiteralTest.java:19-25` and `AgentApiKeyTest.java:22-25` (four false clauses) | applied. `builder` reported matching before/after md5s for every method body and both comment-stripped files. It also changed one extra clause in range: "enforces" → "refuses in src/test/java". Orchestrator re-derivation with its own stripper: `AgentApiKeyTest`'s comment-stripped code differs from HEAD only on the 2 literal lines `verifier` verified, and `NoKeyShapedTestLiteralTest`'s stripped md5 `b9a5edf7…` equals `builder`'s *after* value. That is corroborated, not verified: no pre-edit copy of the untracked file existed to re-hash. Stem searches (`real generated key`, `real key`, `enforced here`) found 0 (`builder`) |
| 12 | `UserNewsContainerServiceAllowanceTest.java:16` "six cases" → "seven cases"; close manager row 5 | applied. Comment-stripped md5 is equal at HEAD and in the worktree (`949920ba…`, orchestrator). Row 5 is closed |
| 13 | `LEARNINGS.md:422` annotation marked as orchestrator's; restore `learner`'s "Backlog seeded" section | applied. The section existed in `learner`'s output and was dropped by the orchestrator's first append; it is now restored verbatim and names the source of manager row 9 (b) |
| 14 | `manager.md`: refuse to reconcile when there is no on-disk entry | applied (inserted after the staleness paragraph) |
| 15 | `planner.md:44` checklist 12: guard tests budget scans-nothing refusals and one known positive per real credential format | applied |
| 16 | For the next `manager`: re-print `last measured:` fields; source or drop row (b); row (a) widens the redactor in step with the guard; restore the lost `Item.js` pointer row (owner `bridge`, last measured never); optional `andDo(print` scan into row (a) | not applied: these are manager-authored rows, carried here so they are on disk. Row (b) is now sourced by item 13 |
| 17 | User ruling on push vs `AGENTIC-LOOP.md:76` | applied. The user chose "Yes, when I explicitly ask": the orchestrator pushes only on a direct user request, never on its own. Recorded at `:393`. `AGENTIC-LOOP.md:76` itself is not edited |
| 18 | At landing: delete `planner`'s pattern file; stage only this cycle's files by path | applied. `planner` reported its pattern file deleted. Scratchpad residue (`mvn-full.log`, `mvn.log`, `md5s.pl`, `before.txt`, `after.txt`) deleted at landing; none is the last copy of a user secret |

## Cycle — 2026-10-18 A — Platform-feed job hardened: per-platform fallback serving the saved card, `NewsBlock.linkspreview`, placeholder-free `@IsolatedH2Test` harness, keep-the-last-good-refresh guard

**Origin.** The user asked: "fix what you didn't fix yet … and fix about the platform-feed job". `planner` split the work into A (feed job) and B (guard widening, blank `?apiKey=` pin, rows 7 and 8). At the gate the user chose A first and kept step 5, and put rows 7 and 8 into B. Three later user decisions shaped the cycle:
- Mid-build: "it can give the empty card, but it could also return what was previously fetched from that card".
- After rev 0's verifier: "add the column, so that we can store preview images per platform … and fix about the test with the in-memory database". This produced rev 1, where the user chose N5 in, harness option A, and N1 in.
- After rev 1's verifier: "Keep the last good refresh". This produced the fix pass.

The user's request also counts as the answer to `manager`'s 2026-10-17 question of whether `articlesOf` had been declined earlier.

**Orchestrator notes.**
- `master` was pushed to origin at the user's explicit request ("push master to origin…"), `92e34df..18442a0`, under the 2026-10-17 push ruling.
- The 2026-10-17 human-only row for `…\ca17e283-…\scratchpad\env.backup` is **closed**: the file was deleted at the user's request ("you can delete env.backup").
  - Before deleting, the orchestrator checked that it was not the last copy of anything. Instrument: `md5` fingerprint of each of the 9 keys' values, compared per key against `.env`/`.env.prod`, with only the match verdict printed. Result: all 9 matched `.env`, and 8 of 9 matched `.env.prod` (`ONELIKE_DB_PASSWORD` differs there). Blind spot: copies outside those two files.
  - Existence after `rm`: absent.
- This entry is appended **before** `manager` runs, per `AGENTIC-LOOP.md:159` and the new `manager.md` refusal.
- Builder's Allowance +2 cases (verifier's query) were task 8 of the confirmed rev-0 plan.

**Final state.** 13 files in scope, uncommitted until landing.

Per-class test counts (Surefire XML, `verifier` re-check, total 63):

| Test class | Tests |
|---|---|
| context load | 1 |
| FilterNewsAgentControllerAuthTest | 3 |
| FilterNewsControllerAuthTest | 2 |
| IsolatedH2HarnessTest | 6 |
| SearchControllerFeedJobTest | 17 |
| UserNewsContainerFrontInsertTest | 4 |
| UserNewsContainerQuotaTest | 4 |
| UserNewsContainerServiceAllowanceTest | 9 |
| AgentApiKeyTest | 8 |
| ConsoleTokenTest | 8 |
| NoKeyShapedTestLiteralTest | 1 |

Verifier verdicts: PASS WITH NOTES three times (rev 0, rev 1, fix pass).

### Learner note (verbatim)

**Correction to the brief.** The brief says `CLAUDE.md:11` has a count to update. The file on disk reads **38**, not 37, and already lists `NoKeyShapedTestLiteralTest` 1. The 37 in my injected copy is stale, which is the snapshot defect the file's own line 5 describes, seen again.

**Worked**

- **The migration was tested in both directions, and the rollback check is worth reusing.** V-M1 (H2 with an old-shape table loaded by `INIT=`) showed the column is added, nullable, and the old row survives and serves `[]`. V-M2 showed that **an old entity neither drops the column nor its value**, so rolling back the jar is safe. V-M3 measured the MySQL `ALTER` on `onelike_test`: the column comes out `longtext`, nullable, at the last ordinal. When a cycle adds a column, `planner` should budget V-M2 by default. It costs nothing and is the only check that answers "can we roll back".
- **Verifier threw out two of its own checks because they could not fail.** First, every md5 came out as `d41d8cd9…`, the md5 of empty input, because of a perl syntax error, so every comparison read SAME. Second, V-M4 as briefed (a VIEW) was void because Hibernate skipped the view. Verifier redesigned it as a non-owner user with no ALTER right. Both catches came from verifier's own suspicion, not from any rule.
- **Comparing structure fixed a long-standing bug.** N-B: the property-2 guard compared text against `"{\"news\" : []}"` (with spaces), but json-smart writes `{"news":[]}`, so the guard never matched. The fix pass replaced it with `hasNews` / `anyCardHasNews` (`SearchController.java:1073`, `:1090`). **Never decide anything by comparing json-smart's serialised text.**
- **The harness refuses instead of warning.** Builder found that the placeholder resolves leniently: a missing name started H2 on a database literally named `${…}`. The final design has no placeholder at all. A `ContextCustomizerFactory` in `src/test/resources/META-INF/spring.factories:2` builds a URL per class, and the initializer and extension refusals were kept.

**Didn't work / watch out for**

- **Making each unit fail soft removed the failure that used to protect the aggregate (N-A).** At HEAD, a throw from a `news_block` lookup aborted `saveNews`, which by accident protected the last good refresh. Rev 0's per-platform `fallbackCard` turned that abort into a successful save of 20 empty cards. Two plans did not price this, and only verifier found it. Combined with V-M4 (a failed ALTER boots anyway), it meant every card would be emptied every 5 minutes. **`planner` checklist addition:** for any per-unit guard, ask "what is written when *every* unit degrades, and what did the old hard failure prevent?" The fix pass now guards this at `SearchController.java:1143-1153`.
- **A user change mid-build that needed a column went to `builder` as a workaround.** Asked to "return what was previously fetched", `builder` took previews from the property-1 row because `NewsBlock` had no column. Rev 1 deleted those helpers. This change passes `AGENTIC-LOOP.md:47`'s test, since it changes wire content on degraded cards and the right answer was an entity column. It should have gone back to `planner` at the moment it arrived.
- **md5 readings need an empty-input refusal, a must-differ control, and a named extractor.** The empty-input hash `d41d8cd98f00b204e9800998ecf8427e` reads as a perfect match. Builder's HEAD md5s also differed from the earlier values only because the normalisation was different, and verifier's matcher reproduced the earlier values. Proposed amendment to the `CLAUDE.md:62` row:
  - refuse zero-length bodies or that hash;
  - include one deliberately changed method that must read DIFFERENT;
  - md5s are comparable only from the same extractor and normaliser, so name it.
- **A failure-injection test needs evidence that the failure path ran.** The VIEW version of V-M4 "passed" because nothing was attempted. The redesign's proof was the WARN from `ExceptionHandlerLoggedImpl`, 1 of 27 WARNs.
- **The leniency mechanism was tested on one axis, not two.** Both property sources were `MapPropertySource`, so the source axis was never varied. The reader axis is confirmed: `Binder.bind` returns the literal and `Environment.getProperty` throws. "The source kind made no difference" is **unmeasured** and stays `(provisional)` in the row.
- **The brief quoted the control "`${` = 1" with no instrument; builder found 4.** A control count in a brief needs its instrument, the same as any baseline.
- **The all-empty guard has exactly one falsifier.** Verifier found T15 is the only test that fails under M1 and M7, and catches M3 only through the return value. NOTE-4 adds that FeedJob shares `news` rows across tests, so that one falsifier depends on test order.

**Rulings on the stage-06 candidates**

1. **`CLAUDE.md:11`, 38 → 63** — apply it, using verifier's Surefire decomposition and adding `IsolatedH2HarnessTest` 6 and `SearchControllerFeedJobTest` 17. Name both groups: `@ActiveProfiles("test")` (`OnelikeBackendApplicationTests`, `FilterNewsAgentControllerAuthTest`, `FilterNewsControllerAuthTest`) and `@IsolatedH2Test` (Quota, FrontInsert, FeedJob; Harness has no context).
2. **`CLAUDE.md:65` "where failure is loud"** — falsified (V-M4). Transcribe verifier's measurement: a failed ALTER logs one WARN and the context **starts**, measured on H2, with MySQL by code-read only. The same row's "unproven residue" (MySQL8 emits the ALTER) is closed **for `onelike_test` only**. Builder's before-reading there was `COUNT(*)` = 0, so an ALTER **against a populated MySQL table** is still unmeasured, and the row should say so. Stem `loud`: `CLAUDE.md:65` and `LEARNINGS.md:270`, with "Cloudflare" as the control. Annotate `:270` as falsified.
3. **`CLAUDE.md:92` stale sentence** — delete "Not every fixture follows the convention yet … the guard passes over them". The fixtures now live only at `IsolatedH2Test.java:86-87`, both `test-fixture-…-not-a-real-secret`. Keep blind spot (c).
4. **The `INIT=` backslash row** — add it as an observation, verbatim: in `@SpringBootTest(properties=…)`, `\\;` failed and `\\\\;` worked. Mechanism `(provisional)`. Attach it to the `:65` row.
5. **The leniency row** — observation as-is; reader-axis mechanism confirmed; source-kind clause `(provisional)`.
6. **`CONTAINERS-CONTRACT.md:232`** — delete "and returned with that fetch's response". This is a falsified claim to fix at stage 06, not a backlog row.
7. **`LEARNINGS.md:133`** — annotate, don't delete. The "entries pointing at vanished cache rows" mechanism is false, because each entry carries its own `image`/`url` inline. The theme decision stands on its other ground.
8. **Orchestrator note** — endorsed, as a `planner.md` checklist item.

**Backlog seeded (new this cycle only)**

- **Promote to a named row (third time): a Hibernate `java.sql.Timestamp` put into a json-smart object serialises as `{"nanos":N}`.** It has been fixed locally three times: `LEARNINGS.md:65` / `CONTAINERS-CONTRACT.md:244`, `FilterNewsConsoleService.java:263`, `SearchController.java:705`. Add one line under `CLAUDE.md` § *Conventions → Response bodies*.
- **New human-only row: confirm `news_block.linkspreview` exists on dev and prod after deploy** (`SHOW COLUMNS FROM news_block LIKE 'linkspreview'`). A failed ALTER logs one WARN and boots. The fix-pass guard keeps the old cards, but then every refresh is skipped indefinitely, and the only signal is the `:1147` WARN. Last measured: never.
- **NOTE-1: an NPE in `performSearchNews` when the property-2 row is missing** (`POST /searchnews` `:1265` → `:1280` → `:1287`). It is mitigated only by `NewsTableSeeder.java:106`, whose `catch` at `:116` swallows a seeding failure. Do not rank this below the seeder's own failure modes.
- **Make T15 own its rows** (per-test `news` reset, plus a mutation run with T15 executed alone).
- **The `:716` mutation was caught only by a log-tag assertion.** Give it a behavioural observable; nobody re-measured this after the fix pass.
- **Corrections to manager's carried rows (2026-10-17):**
  - Row 2 is closed for the `@IsolatedH2Test` classes. What remains: the three `@ActiveProfiles("test")` classes get no static install, and whether they read a static left behind by an H2 context is unmeasured.
  - Row 3 (a) is half done: the fixture conversion is done, the widening is not (`NoKeyShapedTestLiteralTest.java:50` is unchanged).
  - Row 1 is closed by this cycle.
  - N-B is closed by the fix pass.

### Backlog after this cycle (manager)

**Recommended next objective: cycle B** (user-decided at the 2026-10-18 gate). B covers:
- widen `NoKeyShapedTestLiteralTest` (base64url, more than 48 characters, `"prop=VALUE"`) with runtime-built self-test samples;
- convert `ConsoleTokenTest`'s 2 fixtures and fix its "96 characters" javadoc (`:21`; measured at 93/92);
- pin the blank `?apiKey=` fall-through in behaviour;
- row 7 (`ApplicationContextRunner`) and row 8 (`normaliseName`).

Riders for `planner` to include or decline:
- an `.andDo(print` source scan that exempts comments (the known-present control is the prohibition comment at `FilterNewsAgentControllerAuthTest.java:30`);
- row-2 remainder: do the three `@ActiveProfiles("test")` classes read a static left by an H2 context? B modifies `FilterNewsAgentControllerAuthTest`, so B can measure this.

B also owes `CONSOLE-CONTRACT.md:385` and `:406` a scope clause: a blank query param loses to the header (`FilterNewsAgentController.java:106`). B measures it; stage 06 or `bridge` applies it.

**Human-only actions**

| # | Action | Status | Last measured |
|---|---|---|---|
| H1 | Read-only MySQL credential in a brief, or rule out boot-based DB verification | open | never |
| H2 | Settle `ONELIKE_NEWS_FILTER_APIKEY` in `.env`/`.env.prod` | open; non-blank in the test JVM only | 2026-10-18 A (attributed to verifier) |
| H3 | Push local commits | `92e34df..18442a0` pushed at user request; A and B landings add more; push only on explicit request | 2026-10-18 A |
| H4 | **NEW.** Confirm `news_block.linkspreview` on dev and prod after deploy (`SHOW COLUMNS FROM news_block LIKE 'linkspreview'`). A failed ALTER boots anyway; every refresh is then skipped and the cards freeze; the only signal is the WARN at `SearchController.java:1147` | open | never |
| H5 | Rotate HIBP/Webshare keys with distinct dev/prod values | open | never |
| H6 | Rotate or remove the `ghp_…` PAT in the Jenkins clone URL | open; not in this worktree | 2026-10-18 A, worktree only |
| H7 | `JWT_SECRET` parity, issuer vs backend | open, not urgent; substitutes considered: (i) a minted token plus curl needs a running pair, (ii) a fingerprint means a forbidden read, (iii) prod is a deployment fact | never |
| H8 | Does a proxy strip `Authorization` | open | never |
| H9 | `SELECT COUNT(*) FROM filter_news WHERE active IS NULL`, dev and prod | open (blocked by H1) | never |
| H10 | Release `../onelike-agentic` `feature/theme-allowance-ui` whole or not at all | open; branch not checked out (`main`) | 2026-10-18 A |

**Backlog after B, priority-ordered**

1. **NPE in `performSearchNews` when the property-2 row is missing.** `SearchController.java:1286-1287`. Released callers exist in `onelike-agentic`. The only mitigation is `NewsTableSeeder.java:106`, whose catch at `:116` only WARNs. Strongest candidate for cycle C. Last measured: 2026-10-18 A (manager read).
2. **T15 owns its rows** (per-test `news` reset, plus a mutation run with T15 alone). Rides with the next feed-job cycle. Last measured: never.
3. **Give the old `:716` mutation a behavioural observable.** The line number has drifted (`:716` is now inside `fallbackCard`'s javadoc), so re-anchor it by method name. Last measured: never.
4. **Row-2 remainder:** the `@ActiveProfiles("test")` classes get no static install. Unmeasured whether they read an H2 context's leftover static. B is a plausible host. Hatch 0. Last measured: never.
5. **N5 (2026-10-11 series):** accept the `filter_news` create-race or take an app-level lock. Rides with the next `filter_news` write-path cycle. This is not the N5 of A's rev-1 plan, which was a different, now-done item. Last measured: never.
6. **`pinned` null-guard in `PinService`** (`PinService.java:67`). Last measured: 2026-10-18 A.
7. **Observe `refreshed > 0` with live proxies**, plus the malformed-body response shape. Last measured: never.
8. **The `Item.js` pointer row** (owner `bridge`; the pointers live in `feature/theme-allowance-ui`). Last measured: not measured.

**Stage-06 items** (falsified claims and convention lines, not ranked):
- the Timestamp `{"nanos"}` convention line (sites: `UserNewsContainerService.java:732`, `FilterNewsConsoleService.java:263`, `SearchController.java:688`);
- `CLAUDE.md:11` 38 → 63 (manager's `@Test` grep: 63 across 11 files);
- `CLAUDE.md:92`'s stale fixture sentence;
- `CLAUDE.md:65` "loud";
- `CONTAINERS-CONTRACT.md:232`, plus stale line pointers at `:221`/`:234`/`:235`, and `:233` "can dangle" to re-check (owner `bridge`);
- `CONSOLE-CONTRACT.md:385`/`:406` (measured by B).

**Hatch:** there is no `planner` checklist-10 declaration on disk for A, so nothing is ticked. Row 7 stays at 2 and is hosted in B.

**Dropped:**
- row 1 (`articlesOf`), done;
- row 2 for the `@IsolatedH2Test` classes, done;
- row 4 (`@PostConstruct` warning case), done in AllowanceTest:140-153;
- human-only `env.backup`, done;
- N-B, done (it was a rev-0 note, not a carried row).

Do not re-propose these.

### Process review (controller)

The first `controller` run stalled with no output (stream watchdog). The retry produced the list below. The user approved it at the stage-06 gate, choosing "Apply all, plus the short bridge review".

**Controller's orchestrator findings.**
- (a) Appending the learner note before `manager`: proportionate, and required.
- (b) Routing "return what was previously fetched" to the running `builder`: **not proportionate**. `AGENTIC-LOOP.md:46-47` already sent it to `planner`. It also missed `:38`, the settled answer never written into the spec (now written; see item 9).
- (c) The env.backup per-key fingerprint check: proportionate. Small gap: "absent after `rm`" named no instrument; the instrument was a `[ -e ]` test.
- The orchestrator also missed two things itself:
  - the `learner` brief carried the stale 37, against `:155`;
  - a brief carried "`${` = 1" with no instrument, against `:108`.
- A `bridge` pass was owed at rev 1 and none ran; stage 06 ran it (item 9).
- **The orchestrator's stage-06 `bridge` brief carried a false premise:** "You own this pinned contract file and may edit it". `bridge.md` § *The durable contract file* says `bridge` authors and the orchestrator transcribes. `bridge` refused correctly and authored the text, which the orchestrator transcribed verbatim.

| # | Recommendation | Completion |
|---|---|---|
| 1 | `CLAUDE.md:1` → `2026-10-18-a` | applied |
| 2 | `CLAUDE.md:11` 38 → 63 per class; two database groups named; `@IsolatedH2Test` replaces "a harness now exists"; `:12` "(MySQL group only)" | applied. "Zero skips" re-read by the orchestrator from the 11 Surefire XMLs (skipped=0 in each; written 2026-10-18 22:30:57–22:31:03) |
| 3 | `CLAUDE.md:65`: residue (empty-table MySQL ALTER measured), "a failed ALTER is not loud", `INIT=` backslash observation | applied. The command is marked "abridged, not kept verbatim; no mechanism stated" |
| 4 | `LEARNINGS.md:270` falsified annotation; re-run stem `loud` | applied. `git grep -n -w -i loud` over `*.md`/`src` hits only `CLAUDE.md:65` (the corrected "not loud"), `:270` (the annotation), and `:560`/`:625` (cycle-entry references). Control: `cloudflare` = 1 in `CONTAINERS-CONTRACT.md` |
| 5 | `CLAUDE.md:92`: replace the four-fixtures clause with the `ConsoleTokenTest.java:28-29` one (not a deletion) | applied |
| 6 | New *Unsound* row: `Binder.bind` as evidence that a placeholder resolved | applied. "Command not kept verbatim"; the source axis is (provisional) |
| 7 | `CLAUDE.md:62` md5 row: named extractor, empty-body refusal, must-differ control; mirror into `AGENTIC-LOOP.md:40` and `:94` | applied to all three |
| 8 | `CLAUDE.md:36` Timestamp `{"nanos"}` convention line | applied |
| 9 | `CONTAINERS-CONTRACT.md:232` clause deletion, plus a `bridge` pass on `:221-235` | applied. `bridge` authored six edits; the orchestrator transcribed them verbatim: re-anchored `:221`, `:234`, `:235`; `:232` deletion; new settled-at-gate bullet with the user's three quotes; `:233` "can dangle" corrected. File md5 `c7be19f1…` → `0135f5e1…`; LF only (297). `bridge` left `:236` (72h blink) for next cycle |
| 10 | `LEARNINGS.md:133` mechanism-falsified annotation | applied |
| 11 | `planner.md` checklist 13: every unit degrades | applied |
| 12 | `planner.md:38` checklist 8: V-M2 plus `INFORMATION_SCHEMA.COLUMNS` default budget | applied |
| 13 | `verifier.md:23` (`@IsolatedH2Test` touches no schema) and `:26` (use the harness, not a hand-built `${…}` URL) | applied |
| 14 | No new AGENTIC-LOOP rule. Sharpen `:47` ("what the change *needs*") and add a `builder.md` refusal for workarounds over a missing column | applied to both |
| 15 | `CLAUDE.md:94`: a pipeline fingerprint that never prints or writes the value is allowed | applied |
| 16 | For the next `planner`/orchestrator: hatch verdicts on disk; every count in a brief carries its instrument; B flags "needs bridge" for `linkspreview` dynamics | not applied: guidance for the next cycle, carried into cycle B's brief |
| 17 | At landing, stage `src/test/resources/META-INF/spring.factories` by path | applied at landing |

## Cycle — 2026-10-19 B — Key-shaped-literal guard widened, ConsoleTokenTest fixtures converted, blank `?apiKey=` / property boundary / `normaliseName` pinned, `.andDo(print` scan

**Origin.** At the 2026-10-18 gate the user chose "two cycles A then B" and put rows 7 and 8 into B. Later they said "after that, do cycle B and push". Cycle A landed as `c8d844b` and is not yet pushed.

At B's gate the user approved the plan with:
- **D1:** the `{1,16}` segment cap.
- **D2:** the stricter redactor, applied at stage 06.
- **4b:** comments-only, this cycle.

**Orchestrator notes.**
- `builder`'s first run stalled (stream watchdog, 600s). Before resuming it, the orchestrator checked the worktree:
  - `git status --porcelain --untracked-files=all` showed 7 test paths;
  - `git diff --stat HEAD -- src/main` and `-- src/main/resources` were empty;
  - the comment-stripped md5 of each changed `src/main` file matched HEAD — the list was empty, so nothing was compared;
  - must-differ control: `SearchController` HEAD~1 vs HEAD, `9f65753c` vs `27e98eac`.
- Per-class counts (`verifier`, Surefire XML plus two corroborating instruments; zero skips; 84 tests across 14 classes):

| Class | Tests |
|---|---|
| context load | 1 |
| ConsoleToken | 9 |
| AgentApiKey | 8 |
| Allowance | 9 |
| Quota | 4 |
| FrontInsert | 4 |
| FeedJob | 17 |
| IsolatedH2Harness | 6 |
| FilterNewsAgent | 4 |
| FilterNewsConsole | 2 |
| NoKeyShaped | 9 |
| NoMockMvcPrint | 2 |
| AgentApiKeyProperty | 3 |
| FilterNewsNameNormalise | 6 |

- Verifier's verdict: PASS WITH NOTES.

### Learner note (verbatim)

**Correction to the brief.** The brief's stage-06 list leaves out one item that B owed: `CONSOLE-CONTRACT.md:385`/`:406` (2026-10-18 A, manager's backlog). B has now measured it (ruling 7).

**Worked**

- **A plan-time mechanism claim was falsified by a budgeted mutant together with a stop condition.** `planner` read `AgentApiKey.java:77` as having "no observable effect". M7-c (remove `:77`) killed `rejectsEverythingWhenTheConfiguredKeyIsNull`, because a null key passes `:80` and then NPEs on `.trim()` at `:84`. `builder` stopped and reverted 4b as briefed. `verifier`'s M7-b′ (drop only the blank half) survived 84/84, so `planner` was right about one half and wrong about the other. **Rule for `planner`: a compound guard (`a == null || a.trim().isEmpty()`) gets one mutant per disjunct.** One mutant on the whole line cannot tell you which half carries the weight.
- **A positive control caught a broken zero (F1).** `grep -roiF … | wc -l` returned 0 against a control that also returned 0. The cause was a SIGABRT (rc=134) that `wc -l` turned into a clean 0. This is the second time the absence rule's "pair every zero with a known-present control" has caught a broken instrument (the first was 2026-10-17). **Watch: any count taken through `| wc -l` (or any pipe) discards the producer's exit status.** Check rc or use `pipefail`.
- **The test orders were varied, not assumed.** V4 ran alphabetical and reversealphabetical with identical counts. That closed the row-2 remainder by measurement: `@PostConstruct` re-installs the statics, and no MySQL-group path reads them (`verifier`).
- **Resuming a stalled stage after a worktree check worked.** This is the second stream-watchdog stall in two entries (controller, 2026-10-18 A; builder, this cycle). The resume check should name its instrument in the entry (done above).

**Didn't work / watch out for**

- **F3: the guard the user confirmed at the gate misses the most common key encoding.** At `NoKeyShapedTestLiteralTest.java:72` the pattern requires the closing quote directly after the run. A padded `=` is outside the class, so a 44-char `openssl rand -base64 32` / `Base64.getEncoder()` key is not matched. The positive samples at `:165` and `:174` both call `.withoutPadding()`, and so did P12's synthetic set. Root cause is in `planner.md:44` (checklist 12): it budgets samples for formats the loop "has generated or holds". **No stage may read the live key, so its format is unknown by rule, and the sample set must cover the default output of common generators**: padded base64 with one `=` (32 bytes → 44 chars) and with `==` (64 bytes → 88), plus 64-char hex. The gate confirmed a pattern and never saw a padded sample, so the user could not catch this.
- **D2's redactor very probably shares F3.** Its 0/8 was measured only on unpadded samples. Do not write it into `CLAUDE.md:91` as covering base64 until a padded sample has been run through it.
- **A masking command printed the literals it was meant to mask.** The command was `perl -ne 'if ($.==28||$.==29) { s/"[^"]*"/"<MASKED len=".(length($&)-2).">"/g } print "$.\t$_"'`. Without `/e` the replacement is an interpolated string, so `$&` (the match) was printed. The structural fault is that the command prints `$_`, the input line after substitution, so any substitution that fails to match or to evaluate passes the value straight through. Only old fake fixtures leaked, and only to tool output. **A masking command prints only derived fields (line number, length, md5), never the input line. It then fails closed, not open.**
- **Mutation programs were not kept on disk, so `verifier` re-derived its own.** In this cycle that made the evidence stronger (M7-b′ was independent). But nobody can now reproduce builder's M7-c/M7-d as run, and `v/vmut.sh` in the shared scratchpad may have been overwritten. This is the third cycle with "command not kept verbatim", so it is promoted (ruling 5).

**Rulings on the stage-06 candidates**

1. **`CLAUDE.md:11`:** apply 63 → 84 over 14 classes, using `verifier`'s decomposition. Correct the NoKeyShaped description (1 scan plus 8 runtime-built samples). Add the new classes and their groups: NoMockMvcPrint, Property and Normalise have no context and no DB. Bump `:1`.
2. **`CLAUDE.md:91`:** apply, but not as briefed.
   - The "28–48 letters and digits" / "`NoKeyShapedTestLiteralTest:50` uses the same pattern" text and the `ConsoleTokenTest.java:23` pointer are stale.
   - Write the D2 redactor in verbatim, with its measured scope in the same sentence, *unless* the orchestrator first runs padded samples (44- and 88-char) through it and reports the leak count.
   - Add the guard's blind spots by pointer (`NoKeyShapedTestLiteralTest.java:42-52`).
3. **`CLAUDE.md:93`:** apply. "28–48" and "the two `ConsoleTokenTest.java:28-29` secrets predate it" are false now. "It does not require the convention" stays.
4. **F1:** add the *Unsound* row now.
   - The observation goes in verbatim, with the mechanism (provisional).
   - Substitutes: `grep -i -e`, `git grep -i -F`.
   - Same row: "a pipe's count discards the producer's exit status".
5. **Mutation persistence:** promote to a rule.
   - Every mutant and every discarded or masking command is written to a file under `<scratchpad>/<stage>-<cycle>/` and executed from that file.
   - The report cites path plus md5.
   - Files are kept until landing.
6. **The `/e` slip:** add a sentence to § *Credential handling*. A masking or inspection command prints only derived fields, never the input line. Run it first on a synthetic canary file.
7. **The precedence scope clause, now measured.** `FilterNewsAgentControllerAuthTest.java:138` shows that a blank `?apiKey=` loses to the header. There are three unscoped instances:
   - `CONSOLE-CONTRACT.md:385` (`bridge` authors, the orchestrator transcribes; `:406` "blank → 401" also needs "blank in both transports");
   - `FilterNewsAgentController.java:81` and `:104` (transcription, citing the scoped `FilterNewsAgentControllerAuthTest.java:127-128` verbatim).
8. **F2:** a comment-only `builder` pass on `AgentApiKey.java:75-76` and `AgentApiKeyTest.java:98-101`. The text carries three facts:
   - the null half of `:77` stops an NPE at `:84`;
   - a null key comes only from direct construction;
   - the blank half is redundant with `:80` (M7-b′).
   The orchestrator re-derives the md5s.
9. **F3:** fix before landing, as a `builder` fix pass plus a scoped `verifier` re-run. The user decides, because the pattern was user-confirmed.
   - Scope: `:72` → `{28,}={0,2}\"`; padded samples (44 and 88 characters); a raw-hit false-positive count; one mutant (drop `={0,2}`); padded samples added to D2's check; generator-default samples added to `planner.md:44`.
   - If the user declines, `:91`/`:93` and the javadoc must name padded base64 as a blind spot.

**Backlog seeded (new this cycle only)**

- **`IsolatedH2Extension.beforeEach` snapshots the statics *after* the H2 context's `@PostConstruct`** (`:72-77`), so `afterEach` (`:96-101`) restores H2 beans. `verifier` says this is not reachable today. Fix: snapshot before context load, or assert the restored values are not H2 beans.
- **F5: `AgentApiKeyPropertyTest` reads only `application.properties`.** Parameterise it over `application.prod.properties` too; both read `${ONELIKE_NEWS_FILTER_APIKEY}` at `:207` today.
- **planner.md checklist 12 amendment:** generator-default formats in the sample set.
- **Recurrence watch: stream-watchdog stalls, 2 of 3.** The next occurrence promotes to a resume protocol with a named worktree-state instrument.
- **Cross-repo, not this repo's row:** `../onelike-backend-jx2-agentic/.../PublicApiController.java:234` publishes the same unscoped "query parameter wins" to API clients.
- **Closed:** row 7 (`AgentApiKeyPropertyTest`, 3), row 8 (`FilterNewsNameNormaliseTest`, 6), the `.andDo(print` rider (`NoMockMvcPrintTest`, 2), the row-2 remainder (V4), the ConsoleTokenTest fixture conversion and its "96 characters" javadoc. Row 3 (a) is closed except for F3. `CONSOLE-CONTRACT.md:385`/`:406` closes only when ruling 7 is applied.

### Backlog after this cycle (manager)

**Since the entry was written:** at the stage-06 gate the user chose "Fix it now" for F3. A `builder` fix pass covers F3 and F2. Both are treated as in flight here, not as backlog.

**Recommended next objective (C), not chosen by the user:** make `performSearchNews` survive a missing or empty property-2 `News` row.
- The bug: `SearchController.java:1286-1287` dereferences without a null check, and the `:1266` handler has no try/catch. This is the only open row that the released client reaches: 15 call sites, plus 15 frontend dispatchers in `SearchBox.js:129-171`.
- The only mitigation is `NewsTableSeeder.java:106-118`, which only WARNs. A row whose feed column is null is a second NPE path; it was read, not run.
- **Gate decision:** empty `{"news":[]}` or an `ApiError`. The `ApiError` option needs `bridge`.
- The dependent pinned spec is `CONSOLE-CONTRACT.md:283`, at least one backend cycle old.

**Human-only actions**

| # | Action | Status | Last measured |
|---|---|---|---|
| H1 | Read-only MySQL credential in a brief, or rule out boot-based DB verification | open | never |
| H2 | Settle `ONELIKE_NEWS_FILTER_APIKEY` in `.env`/`.env.prod` | open; non-blank in the test JVM only | 2026-10-19 B (attributed) |
| H3 | Push local commits | user-requested ("after that, do cycle B and push"); A `c8d844b` and B land first | not by manager |
| H4 | Confirm `news_block.linkspreview` on dev and prod after deploy | open | never |
| H5 | Rotate HIBP/Webshare keys with distinct dev/prod values | open | never |
| H6 | Rotate or remove the `ghp_…` PAT in the Jenkins clone URL | open | 2026-10-19 B, worktree only (`ghp_[A-Za-z0-9]{10,}` = 0; control: `ghp_` = 9, all prose) |
| H7 | `JWT_SECRET` parity, issuer vs backend | open, not urgent. Substitutes: (i) minted token plus curl needs a running pair; (ii) a fingerprint means a forbidden read; (iii) prod is a deployment fact | never |
| H8 | Does a proxy strip `Authorization` | open | never |
| H9 | `SELECT COUNT(*) FROM filter_news WHERE active IS NULL`, dev and prod | open, blocked by H1 | never |
| H10 | Release `feature/theme-allowance-ui` whole or not at all | open | 2026-10-19 B (`main` checked out; branch ref exists) |

**Backlog, priority-ordered**

1. **The `performSearchNews` NPE**, the recommended objective C. Last measured: 2026-10-19 B, read.
2. **F5:** parameterise `AgentApiKeyPropertyTest` over `application.prod.properties` (`AgentApiKeyPropertyTest.java:61`; both files at `:207`). Hatch 0. Last measured: 2026-10-19 B.
3. **T15 owns its rows** (`SearchControllerFeedJobTest.java:734`). Rides with the next feed-job cycle. Hatch 0. Last measured: never.
4. **Behavioural observable for the old `:716` mutation.** `fallbackCard` is now at `:721`, with its call at `:556`. Rides with row 3. Last measured: never.
5. **`IsolatedH2Extension` snapshot timing** (`:60` context, `:72-77` snapshot, `:96-103` restore). Rides with the next harness change. Last measured: 2026-10-19 B, read.
6. **N5 (2026-10-11 series):** the `filter_news` create-race. Rides with the next `filter_news` write-path cycle. Last measured: 2026-10-19 B (weak zero, no control).
7. **`pinned` null-guard in `PinService`** (`:67`). Last measured: 2026-10-19 B.
8. **Observe `refreshed > 0` with live proxies**, plus the malformed-body response shape. Last measured: never.
9. **`Item.js` pointer row** (owner `bridge`). Last measured: not measured.

**Process rows (stage 06):**
- Stream-watchdog recurrence watch, 2 of 3. Owner: `controller`.
- `planner.md:44` checklist-12 generator-default samples: a process edit for stage 06, not carried.

**Stage-06 items owed by B:**
- ruling 1, `CLAUDE.md:11`;
- ruling 7, precedence scope at `CONSOLE-CONTRACT.md:385`/`:406` (`bridge` authors) and at `FilterNewsAgentController.java:81`/`:104` (transcription);
- rulings 2–6. Rulings 2 and 3 wait on the F3 outcome.

**Cross-repo handoff:** `../onelike-backend-jx2-agentic` `PublicApiController.java:103` and `:234` publish the unscoped "query param wins" while its `:104` falls back on blank. Give that repo's loop B's measurement (`FilterNewsAgentControllerAuthTest.java:127-128`, `:138`).

**Hatch:** there is no checklist-10 declaration in B's entry (the plan declared its riders in the report only). Nothing is ticked.

**Dropped** (do not re-propose):
- row 7, `AgentApiKeyPropertyTest`, 3 tests (hatch retires);
- row 8, `FilterNewsNameNormaliseTest`, 6 tests;
- the `.andDo(print` rider, `NoMockMvcPrintTest`, 2 tests;
- the row-2 remainder (V4);
- the ConsoleTokenTest fixture conversion and its javadoc;
- 3(a), closed except for F3, which is in flight.

### Gate record (transcribed at stage 06 from the orchestrator's context and `planner`'s report, not written at the gate)

The gate answers below are verbatim.

| Decision | Answer | What it approved |
|---|---|---|
| Plan | "Approve (Recommended)" | |
| D1 | "Yes, add the limit (Recommended)" | the `{1,16}` segment cap |
| D2 | "Stricter filter (Recommended)" | the redactor `sed -E 's/(^|[-+"'\''=:[:space:]])[A-Za-z0-9+\/_.-]{28,}/\1<REDACTED>/g'` |
| 4b | "Yes, comments only, in this cycle (Recommended)" | |
| Post-verifier F3 | "Fix it now (Recommended)" | |
| Stage 06 | "Apply all, commit, push (Recommended)" | |

F2 is 4b re-done with measured text, so it falls inside the 4b approval.

`planner`'s checklist-10 riders:

| Row | Host file | Include/decline | Hatch verdict |
|---|---|---|---|
| `.andDo(print` scan | `support/` guard package | included (task 6) | host in change list |
| Row-2 remainder | `FilterNewsAgentControllerAuthTest` | included as a measurement (V4) | host in change list; closed |
| `CONSOLE-CONTRACT.md:385`/`:406` | the contract | included (task 8) | host in change list; applied at stage 06 |
| Row 7 `ApplicationContextRunner` | `AgentApiKeyPropertyTest` (new) | included (task 4) | closed; hatch retires |
| Row 8 `normaliseName` | `FilterNewsNameNormaliseTest` (new) | included (task 5) | closed |
| N5 `filter_news` create-race | `FilterNewsConsoleService` | declined | read-only this cycle: tick |

Fix-pass re-check (`verifier`, PASS WITH NOTES). Per-class Surefire XML, 14 files, zero skips, total 87:

| Class | Tests |
|---|---|
| context load | 1 |
| FilterNewsAgent | 4 |
| FilterNewsConsole | 2 |
| IsolatedH2Harness | 6 |
| FeedJob | 17 |
| FrontInsert | 4 |
| Quota | 4 |
| FilterNewsNameNormalise | 6 |
| Allowance | 9 |
| AgentApiKeyProperty | 3 |
| AgentApiKey | 8 |
| ConsoleToken | 9 |
| NoKeyShaped | 12 |
| NoMockMvcPrint | 2 |

- **F3:** F3-M fails exactly samples I and J, while the other 13 classes pass. The raw hits are 16, with 0 unexempt, on two instruments.
- **F2:** the change is comment-only by `verifier`'s own stripper and extractor, with refusals and a must-differ control.
- **D2:** 0/200 padded keys leaked.
- **Wording nit:** `verifier` noted the "500" in the `AgentApiKey` comment holds only on the controller path. Left as is; a directly constructed caller gets the raw NPE.

### Process review (controller)

The controller ran without stalling, on a targeted-reads brief. The stream-watchdog watch stays at 2 of 3. If it is promoted, the resume protocol is:
1. `git status --porcelain --untracked-files=all`
2. `git diff --stat HEAD -- src/main`
3. comment-stripped md5 per changed file, with the extractor and normaliser named
4. one must-differ control
5. delete `target/classes` for the touched classes, or `./mvnw clean`, before resuming

**Controller's orchestrator findings:**
- (a) Resume check: proportionate. Gaps: the extractor was not named beside the hashes, `target/classes` was not cleared, and the 7 test paths were not hashed at the stall.
- (b) Learner and manager sections were on time. Two misses: D2's verbatim text was not on disk until now, and the "two corroborating instruments" were unnamed. They were the Surefire console lines and a perl `<testcase>` count (`verifier`), plus an `@Test` source grep.
- (c) Asking the user about F3: proportionate, and required.
- (d) Combining F3 and F2: acceptable only because they touched different files. F2's before-baseline for `AgentApiKeyTest.java` was not HEAD; `verifier` re-derived it against its own previous-run copy and HEAD and found it comment-only.

| # | Recommendation | Completion |
|---|---|---|
| 1 | `CLAUDE.md:1` → `2026-10-19-a`; `:11` from `verifier`'s re-check decomposition (87, 14 classes) | applied |
| 2 | `CLAUDE.md:91` redactor: D2 verbatim, with its measured scope; padded result | applied. N = 0/200, so "covers padded base64". The guard is described separately |
| 3 | `CLAUDE.md:93` guard description (`:78` pattern, `:45-58` blind spots) | applied. The blind-spot range is `:45-58`, not the controller's `:45-56`, after a re-read showed the list ends at `:58` |
| 4 | Mutation persistence: `CLAUDE.md:54`, plus pointers in `builder.md` and `verifier.md` | applied |
| 5 | `planner.md:44` generator-default samples | applied |
| 6 | One mutant per disjunct (`planner.md` checklist 12) | applied |
| 7 | F1 *Unsound* row, and the pipe-exit-status *composition* row | applied as two rows |
| 8 | Masking prints derived fields only (`CLAUDE.md` § Credential handling) | applied |
| 9 | Gate record rule at `AGENTIC-LOOP.md:38`; `planner.md:40` emits checklist 10 as a table; B's table transcribed | applied (table above) |
| 10 | `AGENTIC-LOOP.md:94` (ii): the before-baseline is the bytes before the pass; never in the same file as an executable fix | applied |
| 11 | Ruling 7: `FilterNewsAgentController.java:80-81`/`:104` transcription; `CONSOLE-CONTRACT.md:385`/`:392`/`:406` via `bridge` | applied. Controller comments are comment-only by the orchestrator's `orch-B/strip.pl` (md5 `e4c896a8…`): stripped `30bb865d…` before = after; must-differ `isEmpty` control `d7566f82…`; empty input refused (rc 255). `bridge` authored the 3 contract edits, transcribed verbatim; its stem search found no other unscoped hit in the contract. The jx2 `PublicApiController.java:234` remains a cross-repo handoff |
| 12 | Stream-watchdog: hold at 2 of 3, protocol text on disk | applied (above) |
| 13 | Guidance for the next `planner` (objective C) | not applied: carried to the next brief, and recorded here so it is on disk |

**User question during stage 06, answered in conversation and not a cycle:** thread configuration at about 1000 users/day. Orchestrator code-read findings, not measured at runtime:
- `server.tomcat.max-threads=2500` uses the pre-Boot-3 key name and is very likely ignored (Boot 3.1 reads `server.tomcat.threads.max`; Tomcat's default is 200).
- Hikari `maximumPoolSize=20` is the real concurrency bound.
- `spring.task.scheduling.pool.size=10` runs 19 `@Scheduled` methods.
- `onelikeTaskExecutor` is unused (0 `@Async`/`@EnableAsync`).
- The `spring.quartz.*` properties have no starter.
- The Tomcat access log is off.

Candidate cycle, if the user asks: tune `server.tomcat.threads.max`/`accept-count`, remove the dead config, enable the access log. It must go through `planner`, which should measure the effective Tomcat thread count rather than infer it.

## Cycle — 2026-10-19 C — Prod access log with request time (no query string, no IP), dead thread/Quartz config cleaned up, `AgentApiKeyPropertyTest` over prod properties

**Origin.** The user asked: "we have actually 1000 users pro day, what would you suggest about the thread configuration?", then "The refresh process takes sometime 1 min at frontend, that's why I would like to config threads, or looking for other optimization", then "it's about the Social News page load".

**Planner's diagnosis.** Thread settings cannot explain a 1-minute load at this traffic.
- `server.tomcat.max-threads` is **ignored**. Instrument: `spring-configuration-metadata.json` in `spring-boot-autoconfigure-3.1.3.jar` has 0 `max-threads` keys out of 1558, and `javap` shows `ServerProperties$Tomcat` has `getThreads()` and no `setMaxThreads`; control: `getConnectionTimeout` found. So the effective value is the 200 default.
- Each `/news` returns in 0.11–0.28 s; 15 concurrent requests all finished within 1.19 s. Instrument: `curl -w`, anonymous public prod GETs, 2026-10-19 12:36–12:38 UTC.
- **A page load downloads 2,744,007 bytes gzip / 10,170,834 bytes JSON**, because the frontend fetches all 15 languages (`Home.js:396-419`). Instrument: sum of `curl %{size_download}`.
- **Orchestrator note:** `planner` sent those prod requests on its own initiative: 15 + 15 + 30 + 6 anonymous GETs, about four page loads. They were harmless, but an outward-facing action is confirmed first. Future briefs say so.

**Gate answers (verbatim, recorded before `builder`):**
- Plan C: "Approve (Recommended)".
- Access log: "Prod only, no IPs (Recommended)". The planner's recommended pattern is `%t %m %U %s %b %{ms}T` in `application.prod.properties`, with `directory=/logs` and `max-days=14`. Never `%r`, `%q`, `common`, `combined`, or a header that carries a key.
- Frontend single-language cycle: "Not yet". Not started; carried as the measured fix for the 1-minute load.
- Threads: "threads.max=200, keep executor (Recommended)". `server.tomcat.threads.max=200` replaces the dead line in both files; `onelikeTaskExecutor` stays; the `spring.quartz.*` lines are removed.

**`planner`'s checklist-10 table (verbatim):**

| Row | Host file | Include/decline | Hatch verdict |
|---|---|---|---|
| F5, prod properties in `AgentApiKeyPropertyTest` | `AgentApiKeyPropertyTest.java` | include (task 5) | host in change list |
| 1, `performSearchNews` NPE | `SearchController.java` | decline (search out of scope) | read-only this cycle: tick |
| 3, T15 owns its rows | `SearchControllerFeedJobTest.java` | decline | read-only: tick |
| 4, `fallbackCard` observable | `SearchController.java` | decline | read-only: tick |
| 5, `IsolatedH2Extension` snapshot timing | `IsolatedH2Extension.java` | decline (used, not changed) | read-only: tick |
| 6, N5 `filter_news` create-race | `FilterNewsConsoleService.java` | decline | no plausible host: tick |
| 7, `PinService` null-guard | `PinService.java` | decline | no plausible host: tick |
| 8, `refreshed > 0` live proxies | — | decline | no plausible host: tick |
| 9, `Item.js` pointer | frontend | decline | no plausible host: tick |

**Post-build gate answer (verbatim):** `builder` found that nothing in the repo shows how prod loads `application.prod.properties`: Boot does not auto-load that name; 0 hits for `SPRING_CONFIG`/`SPRING_PROFILES`/`spring.config`/`profiles.active`; control: `application.prod` in 4 files. Asked how production uses it, the user answered: "Copied over application.properties at deploy". So the prod access-log lines take effect after deploy. This is a deployment fact, held on the user's word; the repo cannot show it.

**Verifier (stage 03): PASS WITH NOTES.** 118 tests over 16 classes, zero skips (Surefire XML plus a `<testcase>` count).
- `%{ms}T` was confirmed as milliseconds from a real Tomcat log line, with `%D`/`%T` must-differ controls.
- Leak check: 0 hits for the synthetic `apiKey`, `uuid`, `X-API-Key` and IP values, while the control pattern caught each one.
- Notes N1–N3 are below.

### Learner note (verbatim)

**Worked**

- **The diagnosis changed the objective before any code was written.** The user asked about threads. `planner` measured instead of inferring, as stage 06 of 2026-10-19 B had asked (`LEARNINGS.md:913`). It found that `max-threads` is dead (metadata 0/1558, `javap` shows no setter, control `getConnectionTimeout`), that `/news` takes 0.11–0.28 s, and that one page load downloads 10.2 MB of JSON (`LEARNINGS.md:920-922`). **Rule for `planner`: when a user names a cause ("threads"), measure the effect they reported (the 1-minute load) end to end before planning the named cause.** This cycle's real fix is the cross-repo row below, not anything that landed.
- **Must-differ controls kept the runtime claims honest.** `TomcatThreadsMaxTest` reads the real connector executor and uses 37 as a control value. The `%{ms}T` check used a real Tomcat log line with `%D`/`%T` controls. The leak check's control pattern caught all of its synthetic values (`verifier`). Each of these would have shown a different output if its claim were false, which a check without a control would not.
- **The deployment blocker was asked, not assumed.** `builder` searched for how prod loads its config and found 0 hits for `SPRING_CONFIG`/`SPRING_PROFILES`/`spring.config`/`profiles.active`, with the control `application.prod` found in 4 files. It then asked, and the user answered.

**Didn't work / watch out for**

- **Mutation persistence failed in its first cycle as a rule.** `builder` cited `mutate.pl` and its md5, but `verifier` could not find (ix)'s definition on disk. That is the fourth cycle running in which a command could not be reproduced, and the first since the rule was written at `CLAUDE.md:54`. The rule produces a *citation* and nothing checks what the citation points to. `AGENTIC-LOOP.md:82` forbids a third restatement, so the fix has to be a visibility mechanism (ruling 5).
- **`builder`'s "(viii) is killed only by the guard" was stated wider than it was measured.** `verifier`'s dev-file (viii) is also killed by `TomcatThreadsMaxTest[1]`. This is the fourth instance of `AGENTIC-LOOP.md` § *Generalising past the measured scope*, triggered by the word "only", and the subject that was widened was *a file*, not a method. **When a test is parameterised over both property files, a mutant's kill set is reported per file.**
- **"`CLAUDE.md:54`" did match.** The mutation-persistence rule is the last three sentences of the :54 paragraph. `builder`'s citation was correct but hard to find. Do not split the paragraph: it would shift every later `CLAUDE.md:NN` citation.
- **The substitute in `CLAUDE.md:83` is unsound for the question that created the row.** F1 was a leak count over `target/surefire-reports`. `git grep` searches only tracked files, and `target/` is ignored (`.gitignore:5`). So `git grep -i -F` over that directory returns a silent 0, and only `--no-index` would make it search there. `verifier`'s discarded `git grep -i -F` run fits that explanation, but it is not confirmed.
- **Outward calls without consent.** `planner` sent 66 anonymous GETs to prod. The measurement was the most valuable one this cycle took. What was missing was consent. The rule is "propose and confirm", not "never" (ruling 4).
- **Correction to an earlier entry.** `LEARNINGS.md:906` said `max-threads` was "very likely ignored". It is now measured as ignored, by metadata, `javap` and runtime binding. `LEARNINGS.md:911` ("the Tomcat access log is off") was true then and is now false for `application.prod.properties:10`.

**Rulings on the stage-06 candidates**

1. **`CLAUDE.md:11` test count: apply.** Change 87 → 118 and 14 → 16 classes, from `verifier`'s Surefire XML. Add `ServerPropertiesGuardTest` 24 and `TomcatThreadsMaxTest` 4 (a real embedded Tomcat on 127.0.0.1:0, no DB). Change `AgentApiKeyPropertyTest` 3 → 6, "over both property files". Bump `:1`. The `@Test`-file corroboration counts *files*, never tests, and the sentence should say so.
2. **`CLAUDE.md:83`, `grep -i`: apply, in two parts.**
   - Observation: `grep -i -e A -e Z` aborts with rc=134 (`builder`). Narrow the substitute to "`grep -i -e PATTERN` with exactly **one** `-e`".
   - Scope `git grep -i -F` to tracked files. For ignored paths, `git grep --no-index -i -F` is unmeasured here.
   - The mechanism stays (provisional).
3. **New *Sound* row, "does this Spring Boot property take effect":** read the consuming component's runtime value in a booted context, with a must-differ control value (`TomcatThreadsMaxTest`: 200, and 37 as the control). Metadata and `javap` are supporting absence claims, each needing a known-present control. Cross-reference `:81`. Boot does not warn on unknown keys, so a clean boot is not evidence.
4. **The outward-network rule: put it in `CLAUDE.md`, with a pointer in `planner.md`.**
   - No stage sends a request to a host other than localhost/127.0.0.1 without the user's confirmation. That covers prod, the communo backend and third-party APIs, and covers anonymous GETs too. Propose the exact command, host and request count as a gate question.
   - Any report that made outward requests lists host + count.
   - A permission-settings deny is the user's configuration to change; offer it, do not apply it.
5. **Mutation persistence, the visibility mechanism:** add to `verifier.md` rule 9: "Before re-deriving, hash each mutant file `builder` cited and list which mutant IDs it defines. A cited path that is missing, has a mismatched md5, or lacks an ID is a finding."
6. **Deployment fact in `CLAUDE.md` § Commands:** prod copies `application.prod.properties` over `application.properties` at deploy (user's word, 2026-10-19 C; the repo cannot show it).

**Backlog seeded (new this cycle only)**

- **N1: the access-log guard is a denylist.** It misses `%{…}c`, `%S`, `%{…}s`, `%u` and `%{remote}p`, and mutant own2 survives. Fix: an allowlist that refuses any token outside `%t %m %U %s %b %{ms}T`, which kills own2. own3 is inherent residue. Hatch: `ServerPropertiesGuardTest`.
- **N2: game usernames reach `/logs` (user decision).** `%U` logs `/roosbreaker/{username}` (`RoosBreakerController.java:44`). It is the only path-variable mapping under `src/main/java` (Grep `Mapping\(.*\{` = 1; that hit is the control). Options: accept it; mask that path; or move the username to a parameter, which changes the wire and needs `bridge`.
- **N3: the 64 KB request-header limit has never taken effect (user decision; predates this cycle).** `server.tomcat.max-http-header-size=65536` is ignored in both files (dev `:10`, prod `:14`); the effective limit is 8192 (`verifier`, runtime binding). Options: rename it to `server.max-http-request-header-size`, which is a behaviour change, or delete the line, which is zero change.
- **Two more dead keys, `server.tomcat.accesslog.delay` and `server.tomcat.accesslog.buffer-size`**, at `:8-9` in both files. Confirm they are dead with the ruling-3 instrument before deleting either.
- **Cross-repo, frontend: load one language on the Social News page.** Parked; the user said "Not yet". This is the measured fix for the 1-minute load. It belongs to `../onelike-agentic`'s loop; a backend `/news?lang=` change would need `bridge`.
- **Closed:** F5 (`AgentApiKeyPropertyTest` over prod properties, 6 tests); the 2026-10-19 B thread-configuration question.

### Backlog after this cycle (manager)

**Recommended next objective:** make `performSearchNews` survive a missing property-2 row or a null feed column (`SearchController.java:1286-1287`; the `:1266` handler has no try/catch). It is the only open backend row the released client reaches. It is **not** the fix for the 1-minute page load.

**Gate questions (user decisions):**
- **G1 (N2):** usernames in `/logs` via `/roosbreaker/{username}` (`RoosBreakerController.java:44`; the only path variable).
- **G2 (N3):** the 64 KB header limit has never taken effect (dev `:10`, prod `:14`). Options: rename the key, which changes behaviour, or delete the line, which changes nothing.
- **G3 (M1, manager-found):** `logging.level.org.springframework.web=DEBUG` (dev `:184`, prod `:188`) logs the query string, `?apiKey=` included, to `logs/onelike.log` (`CONSOLE-CONTRACT.md:394`, from a code read; not measured in a booted context). In addition, 32 `log.(debug|info)(…getRemoteAddr` lines in 15 `SearchController*` files log caller IPs. This undercuts C's "no query string, no IP".
- **G4 (H13):** is the frontend single-language cycle's "Not yet" about timing, or is something blocking it?

**Human-only actions**

| # | Action | Status | Last measured |
|---|---|---|---|
| H1 | Read-only MySQL credential, or rule out boot-based DB verification | open | never |
| H2 | Settle `ONELIKE_NEWS_FILTER_APIKEY` | open; non-blank in the test JVM | 2026-10-19 C (attributed) |
| H3 | Push local commits | A and B pushed (refs equal `799a181`); C not landed; push only on explicit request | 2026-10-19 C |
| H4 | Confirm `news_block.linkspreview` on dev and prod | open | never |
| H5 | Rotate HIBP/Webshare keys | open | never |
| H6 | Rotate the `ghp_…` PAT on Jenkins | open | 2026-10-19 C, worktree only (0; control 10, prose) |
| H7 | `JWT_SECRET` parity | open, not urgent. Substitutes: (i) minted token plus curl needs a running pair; (ii) a fingerprint means a forbidden read; (iii) prod is a deployment fact | never |
| H8 | Does a proxy strip `Authorization` | open | never |
| H9 | `filter_news` rows with NULL `active` | open, blocked by H1 | never |
| H10 | Release `feature/theme-allowance-ui` whole or not at all | open | 2026-10-19 C |
| H11 | After deploy, confirm the prod access log appears under `/logs` in the `%t %m %U %s %b %{ms}T` shape, and that `/logs` is persisted and writable | open | never |
| H12 | Decide G1–G3 | open | 2026-10-19 C |
| H13 | Frontend single-language cycle | parked ("Not yet"); ask G4 | 2026-10-19 C (`onelike-agentic/src/components/Home.js:400-414`, 15 dispatches) |

**Backlog, priority-ordered**

1. The `performSearchNews` NPE (the recommendation). Standalone. Last measured: 2026-10-19 C.
2. **N1:** make the access-log guard an allowlist (`ServerPropertiesGuardTest.java:75`). Rides with the next properties or guard change, or with G2/G3. Hatch 0. Last measured: 2026-10-19 C.
3. **Dead keys `accesslog.buffer-size` / `.delay`** (`:8-9`, both files). Confirm with the runtime-value instrument first. Rides with row 2. Hatch 0. Last measured: not measured.
4. **N5:** the `filter_news` create-race. **Hatch 2 of 3** (B, C). Last measured: never.
5. **T15 owns its rows.** Hatch 1. Last measured: never.
6. **`:716` observable** (`fallbackCard` at `:721`, called at `:556`). Hatch 1. Last measured: 2026-10-19 C.
7. **`IsolatedH2Extension` snapshot timing.** Hatch 1. Last measured: 2026-10-19 C.
8. **`PinService` null-guard** (`:67`). Rides with the next `PinService.java` change. Hatch 1. Last measured: 2026-10-19 C.
9. **Observe `refreshed > 0` with live proxies.** Now consent-gated (outward requests); a gate question for the next feed-job cycle. Last measured: never.

**Cross-repo handoffs:**
- Frontend single-language (H13). There is no pinned spec for `/service/news*`; a backend `?lang=` change would need `bridge`.
- `Item.js` pointer row (owner `bridge`).
- jx2 `PublicApiController.java:103`/`:234` still unscoped.

**Process rows:** the stream-watchdog watch stays at 2 of 3; no stall in C. C's rulings 1–6 are owed at stage 06. Controller flag: if G3(b), the guard could also refuse `org.springframework.web=DEBUG` in the prod file.

**Hatch ticks (from C's table):**
- T15 1; `fallbackCard` 1; `IsolatedH2Extension` 1; **N5 2**; `PinService` 1; `refreshed>0` 1 (reclassified as consent-gated).
- Not counted: the NPE row (not a rider) and `Item.js` (not hostable here).

**Dropped:** F5 (done); the thread-configuration question (done).

### Stage-06 gate answers (verbatim)

- Land C: "Apply all, commit, push (Recommended)"
- **M1** (DEBUG `org.springframework.web` probably writes `?apiKey=` to `logs/onelike.log`; 32 debug lines log IPs): "Next cycle: measure, then fix (Recommended)". This is the next objective: prove it with a local booted test, set the prod Spring-web log level to INFO, extend the guard, and consider the IP lines.
- **N3** (64 KB header limit never took effect): "Delete the line, keep 8 KB (Recommended)". A zero-change property edit; it rides with the M1 cycle.
- **N2** (usernames in `/logs` via `/roosbreaker/{username}`): "Accept (Recommended)". Settled; do not re-raise.

### Process review (controller)

**Orchestrator miss found by `controller`: I mojibaked `.claude/agents/planner.md` in cycle B's stage 06.**
- A byte-mode `perl -0777 -i` substitution under `LC_ALL=C` contained the wide character `\x{2032}`. Perl upgraded the whole string and re-encoded every existing UTF-8 byte as Latin-1, giving 42 double-encoded sequences.
- It was committed and pushed in `799a181`. The same count in `18442a0`/`c8d844b` is 0.
- **Repaired:** `orch-C/undouble.pl` (md5 `39b94a16…`) decodes UTF-8, maps chars < 256 back to bytes, and re-encodes chars ≥ 256.
- Result: 0 double-encoded sequences across all tracked `*.md`. The em-dash count is 32, versus 30 at `c8d844b`; the +2 come from cycle B's inserted text. A diff against `c8d844b` shows only the intended `:40` and `:44` changes.
- **Rule for the orchestrator:** never put a non-ASCII escape into a byte-mode `perl -i` edit. Use the Edit tool for text containing non-ASCII, and check `\xC3[\x80-\xBF]\xC2[\x80-\xBF]` = 0 afterwards.

**Controller's orchestrator findings:**
- (a) proportionate; the standing fix is item 1.
- (b) proportionate; the plan's task titles were missing (item 11).
- (c) proportionate.
- (d) the "code-read" qualifier on the Hikari claim did not travel into `ServerPropertiesGuardTest.java:50-52` (fixed by item 9).

| # | Recommendation | Completion |
|---|---|---|
| 1 | Outward-request rule in `CLAUDE.md` (before § Agentic loop), plus a `planner.md` pointer | applied |
| 2 | `CLAUDE.md` `grep -i` substitutes: single `-e`; `git grep` tracked files only | applied |
| 3 | New *Sound* row: "does this Spring Boot property take effect" | applied (cross-referenced by row name) |
| 4 | Production-config fact under § Commands | applied |
| 5 | `CLAUDE.md:11` count 118 over 16 classes; revision `2026-10-19-b` | applied. Per-class counts re-read by the orchestrator from the 16 Surefire XMLs before landing |
| 6 | `verifier.md` rule 9 mutant-file audit, plus an output-format row | applied |
| 7 | Repair `planner.md` encoding | applied (above) |
| 8 | Stale citations: `verifier.md:26` `:130`→`:131`; `CONSOLE-CONTRACT.md:148` → dev `:173-174` / prod `:177-178`; `:283` `:99,101`→`:100,102` | applied. Each line re-read by key (`jwt.secret`/`jwt.expiration`; `fixed-delay-ms`/`max-per-tick`; `spring.cache.type`). Ask the user later whether citations should name property keys instead of line numbers |
| 9 | Comment-only pass on `ServerPropertiesGuardTest.java` `:30-31` and `:50-52` | applied by `builder`. Method-body and comment-stripped md5s unchanged (`25eab226…`), must-differ `1b181889…`. Orchestrator re-derivation with its own naive stripper `orch-C/strip.pl` (md5 `387749d1…`): before = after `25eab226…`, must-differ differs. Corroborated. 24/24 |
| 10 | `AGENTIC-LOOP.md:40` garbled sentence fixed; `:125` fourth instance plus the per-file kill-set rule; `builder.md` pointer | applied |
| 11 | `AGENTIC-LOOP.md:38` gate record includes the plan's task titles | applied |
| 12 | `planner.md` checklist 14 (measure the reported symptom before the named cause); checklist 6 sub-clause (how the target environment loads a config file) | applied ("Apply all") |
| 13 | Next `planner` brief: M1 plus N3 (user-chosen), N1 allowlist rider, dead keys via the new *Sound* row | carried to the next brief |

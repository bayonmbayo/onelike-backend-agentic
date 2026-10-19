# Console — publisher filter, wire contract

The contract the backend implements and the `onelike-agentic` frontend builds against, for the ADMIN/MODERATOR-only "Console" menu entry that adds a spamming publisher's name to `FilterNews`.

**Backend: built and verified** (four endpoints, the token gate, and two test classes; `verifier` PASS-WITH-NOTES). A later cycle added a fifth endpoint which is **not** part of the Console and is not token-gated — see §13, and read §5 as Console-only. **Frontend: not built.** So this is a forward spec for the frontend builder and a description of shipped behaviour on the backend side — where the two disagree, the backend code is what runs, and that is a defect in this file.

Every claim below was read out of real code, not relayed from the planning stage. On the backend: `model/FilterNews.java`, `repository/FilterNewsRepository.java`, `service/NewsService.java` (`allFilterNews`, `getAllFilterNews`), `service/CacheService.java`, `service/CacheLoader.java`, `controller/SearchController.java` (`isGoodNews`, `articlesOf`, `performJob`), `util/OtherTool.java` (`endsWith`), `controller/UserNewsContainerController.java`, `service/UserNewsContainerService.java` (`wireDate`), `service/ErrorService.java`, `error/ApiError.java`, `exception/ContainerException.java`, `model/dto/*`, `pom.xml`, `src/main/resources/application.properties`, `OnelikeBackendApplication.java`, plus the 15 `SearchController*` language variants. On the frontend, `onelike-agentic` at **`916f399`** (re-checked with `git rev-parse HEAD`, not taken from the handoff; working tree has one unstaged modification, `src/components/Item.js`): `src/helpers/auth-header.js`, `src/services/usercontainer.service.js`, `src/services/newspin.service.js`, `src/services/outofscope.service.js`, `src/components/Menu.js`, `src/components/Message.js`, `src/components/ProfilItem.js`, `src/components/SpaceItem.js`, `src/constants/worker.constants.js`, `.env`, and `node_modules/react-burger-menu/lib/menuFactory.js`. In the issuer repo `onelike-backend-jx2-agentic`: `src/main/java/com/onelike/app/security/JWT.java`, `properties/JWTProperties.java`, `pom.xml`. The CORS answer in §7 was resolved from Spring 6.0.11 **bytecode** (`javap -c` on `RequestMappingHandlerMapping`, `CorsConfiguration`, `DefaultCorsProcessor` out of `~/.m2`), not from a runtime observation.

Items corrected after implementation are sourced from measurements rather than re-authored: the cache premise in §9 and §11-q5, the sort key in §3, the NULL-`active` reactivate case in §4(a), the `sub` guard in §5, the undefined-`errors` case in §10, the settled questions in §11-q3 and §11-q4, and — on 2026-10-11, against the frontend source rather than `verifier` — the role-check count and the third cloned prop in §8. Each is marked **[corrected]** where it appears, with a parenthetical saying what was wrong; no running total is given here, because the one this sentence used to carry was itself wrong.

This file does not touch `CONTAINERS-CONTRACT.md`, which covers a different feature.

Frontend work runs that repo's own six-stage loop: start at its `planner`, apply its confirmation gate, then `builder`.

---

## 0. Two handed-down premises that are false

Both came in as settled background. Correcting them changes no decision, but a builder who trusts either will write the wrong code.

**"Every existing use of `authHeaderWithJSON()` is a POST with a JSON body."** It is not. Three existing **GET**s already use it, two of them with no body at all: `usercontainer.service.js:101-106` (`GET /mycontainers`), `newspin.service.js:60-64` (`GET /mypinnednews`), `newspin.service.js:73-77` (`GET /pincount`). So the helper's shape on a bodyless GET is not a new question — it is live in production code against this same backend. See §6.

**"The security starter is on the classpath."** It is not. `pom.xml:87-97` has both `spring-boot-starter-security` and `spring-boot-starter-oauth2-resource-server` inside an XML comment. `./mvnw -o dependency:list` resolves **zero** spring-security artifacts, and no file under `src/main/java` imports `org.springframework.security`. `OnelikeBackendApplication` excludes only `SqlInitializationAutoConfiguration`.

The settled decision — a plain `@Component` helper, no `SecurityFilterChain` — is **unchanged and in fact stronger** than its stated reason. The type `SecurityFilterChain` is not on the compile classpath, so writing one would first require un-commenting the starter; that would activate Boot's default `@ConditionalOnDefaultWebSecurity` chain, which authenticates every request and so would lock down all 19 existing controllers. The conclusion holds; only the reasoning needed repair. `jjwt 0.12.5` **is** on the classpath (`jjwt-api` compile, `jjwt-impl`/`jjwt-jackson` runtime), so token verification needs no new dependency.

---

## 1. Base URL and the cross-host trust assumption

The client calls the **unsuffixed** `process.env.REACT_APP_ONELIKE_BACKEND_API`, which is `https://api.onelike.app/service` (`onelike-agentic/.env:31`). The per-language variables point at `/dienst`, `/servicefr` … which are different controllers without these endpoints. Mirror `usercontainer.service.js`; do not copy `socialmedia.service.js`'s 15-language-copies pattern.

All four endpoints live on a new controller with class-level `@RequestMapping("/service/*")`, so `@GetMapping("/filternews")` resolves to `/service/filternews` and `@PostMapping("/filternews/active")` to `/service/filternews/active`. Two-segment method paths under `/service/*` already work — `/service/containers/order` is live.

**The token is issued by a different host than the one that verifies it.** The login response comes from `workerConstants.WORKER` = `https://communo.onelike.app/servicespace/` (`src/constants/worker.constants.js:2`; the Google exchange is `Menu.js:593`, `COMMUNO + "api/auth/google"`), and the Console endpoints go to `api.onelike.app`. So `api.onelike.app`'s deployed `JWT_SECRET` must hold byte-identical the value `communo.onelike.app` signs with. An HMAC-SHA512 check against this repo's local `.env` value passed, which proves the **local dev** secret matches the token's issuer — it proves nothing about either deployment's environment. See §11, open question 1.

All mutations are POST. This repo has no PUT and no DELETE anywhere, including the hard delete.

---

## 2. What `FilterNews.name` is

The publisher string **exactly as Google News writes it after `- ` at the end of a headline**. Not a domain, not a hostname.

`SearchController.isGoodNews` (`:1354-1361`) is unchanged:

```java
for(FilterNews filterNews : filterNewsList){
    if(OtherTool.endsWith(news, "- " + filterNews.getName())){
        return false;
    }
}
```

`OtherTool.endsWith` (`:151-156`) is a null-guarded `String.endsWith`. Therefore matching is **case-sensitive, exact, and anchored to the end of the headline**. `The Hacker News` filters `… - The Hacker News`; `thehackernews.com` and `the hacker news` filter nothing. There is no feedback mechanism, no dry run and no match count: a mistyped name silently filters nothing and is corrected by entering the right name. That is accepted, and it is why the popup copy in §9 is load-bearing rather than decorative.

**The filter list is global across all 15 languages.** Every language's scheduled job calls `SearchController.saveNews(...)` (e.g. `SearchControllerGR.java:69-74` → `saveNews("Greek")`) → `articlesOf`, which loads the single shared list at `SearchController.java:566`. One row affects every feed.

---

## 3. Endpoints

Bodies are hand-built with `net.minidev.json` and returned as a String with `produces = "application/json"`, matching `UserNewsContainerController`. **No identity fields in any request body** — identity comes only from the token (§5).

### `GET /service/filternews`

No parameters, no body. Returns every row, **active and inactive**, newest first — **ordered by `id` descending** [corrected]. Not by `createdat`: that column is null for every row predating this feature, so a comparator on it would throw rather than merely scatter them, whereas `id` is a primitive `long` under `GenerationType.IDENTITY` and so is monotonic and null-free. Neither key is bumped by a reactivate, so the two differ only in the null problem.

```json
{ "filters": [
    { "id": 42, "name": "The Hacker News", "active": true,
      "createdby": "someone@example.com",
      "createdat": "Sat Oct 11 17:06:51 CEST 2026" }
  ] }
```

`createdby` and `createdat` are `null` for rows that predate this feature. `active` is `true`/`false`, never null on the wire (§4 explains why it can be null in the database and what to do about it).

**Do not implement this by calling `newsService.getAllFilterNews()`.** That method is `@Cacheable(value = "filter_news")` (`NewsService.java:338-342`) and delegates to `getActiveFilterNews(true)`, so it would return a list with every inactive row missing — i.e. the Console would not show the rows it exists to manage, and a just-added row might not appear in the list the admin is looking at. Read `filterNewsRepository.findAll()` directly and sort in the service.

### `POST /service/filternews`

```json
{ "name": "The Hacker News" }
```

Trimmed server-side. A name that already exists **case-sensitively** and is inactive is **reactivated** rather than duplicated.

```json
{ "added": true, "reactivated": false,
  "filter": { "id": 42, "name": "The Hacker News", "active": true,
              "createdby": "someone@example.com",
              "createdat": "Sat Oct 11 17:06:51 CEST 2026" } }
```

A name that already exists and is **already active** is an idempotent success, not an error: `200` with `"added": false, "reactivated": false` and the existing row. A double-submitting popup is therefore safe. `reactivated: true` means the row existed inactive and is now on.

`createdby`/`createdat` are set on insert only; a reactivate leaves the original values alone.

### `POST /service/filternews/active`

```json
{ "id": 42, "active": false }
```

Returns `{ "updated": true, "filter": { … } }` with the row in its new state.

### `POST /service/filternews/delete`

```json
{ "id": 42 }
```

Returns `{ "deleted": true }`. Hard delete, no soft-delete fallback — the `active` toggle is the soft option and both exist deliberately.

---

## 4. Four backend traps

**(a) `active` must be set explicitly to `true` on insert, or the filter never applies.** `FilterNewsRepository.getActiveFilterNews` is `WHERE fn.active = :active`, and SQL `NULL = TRUE` is NULL, not true — a row with `active IS NULL` is invisible to the read path forever. `FilterNews.active` is a nullable `Boolean`, and the `@Column(columnDefinition = "boolean default true")` DB default only applies when the column is **omitted from the INSERT**, which Hibernate does not do: it writes all mapped columns, so an unset field is written as NULL. Call `setActive(true)` in the create path. This failure mode is indistinguishable from a mistyped name — the symptom is "the filter does nothing" — which makes it exactly the bug that will eat a cycle.

**The same NULL applies on the *reactivate* path, and the comparison must tolerate it** [corrected]. A row predating this feature can hold `active IS NULL`, so the test for "already on" is `!Boolean.TRUE.equals(existing.getActive())`. `existing.getActive() == true` would unbox-NPE on such a row, and `Boolean.FALSE.equals(...)` would report it as already active and leave it off forever. Verified behaviourally: a hand-inserted `active = NULL` row and an `active = 0` row both answer `200 {"added":false,"reactivated":true}` and flip to `1`, keeping their original null `createdby`/`createdat`.

**(b) The name lookup must be case-sensitive in Java, not in SQL.** Matching is case-sensitive (§2), but MySQL's default collation is case-insensitive, so a derived `findByName("the hacker news")` would return the `The Hacker News` row. Reactivating that row makes the admin believe a lowercase filter is live when it filters nothing. Do the comparison in Java with `String.equals` over `findAll()`.

**(c) `createdat` is type-unstable unless normalised at one choke point.** json-smart special-cases `java.util.Date` **exactly** and writes it with `toString()`. Hibernate hands back `java.sql.Timestamp`, a subclass, which misses that branch and falls through to bean reflection, serialising as `{"nanos":63000000}`. So without normalisation the create response returns a string (in-memory `Date`) and every later read returns an object (`Timestamp` from the DB). Copy the millis into a plain `java.util.Date` on the way to the wire — the copy is required, `Timestamp.getTime()` alone does not help, because the **declared type** is what json-smart dispatches on. The precedent to copy is `UserNewsContainerService.wireDate` (`:552-554`) and its comment above it.

The client consequence: **`createdat` is a Java `Date.toString()` string, not ISO-8601** — `"Sat Oct 11 17:06:51 CEST 2026"`. Do not `new Date(...)`-parse it. Render as-is or not at all.

**(d) `errors[0]` comes from the throwable, not from the message.** `ErrorService.createApiError(status, message, error)` builds `ApiError(status, message, error.getMessage())` and `ApiError`'s three-arg constructor does `errors = Arrays.asList(error)`. So if you pass `null` as the throwable, **`errors[0]` is the empty string** and the client's whole error branch collapses. Follow `UserNewsContainerController` exactly: throw a `FilterNewsException` (new, a one-line copy of `exception/ContainerException.java`) whose **message is the machine-readable code**, then derive the HTTP status and the human message from that code in the controller — the pattern at `UserNewsContainerController.java:166-200` (`createContainerError` / `statusOf` / `messageOf`). `exception/InsufficientPrivilegesException.java` already exists and is imported by `GDPRDataRequest.java`; it carries no code-to-status mapping, so prefer the `ContainerException` shape for consistency with the newer code.

---

## 5. Identity and auth

The caller's identity travels **only** in the `Authorization: Bearer <jwt>` header. **No request body carries uuid, email, firstname, name or displayname** — this is the one place this feature deliberately departs from the `Pin` and `Container` precedent, because the role must not be client-assertable.

This section describes **the Console's four endpoints only**. A fifth endpoint on the same table authenticates with a shared API key and no token at all — see §13. Nothing in §5 applies to it.

A small `@Component` helper, called as the first statement of all four controller methods:

1. Read `Authorization`. Missing, or not starting with `Bearer `, → `filternews.unauthorized` / 401.
2. Verify the signature and expiry with `jwt.secret`. jjwt 0.12.5 is on the classpath. Copy `onelike-backend-jx2-agentic`'s `security/JWT.java:60-62` verbatim — `Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes())).build().parseSignedClaims(token).getPayload()` — and **drop its Spring Security imports** (`UserDetails`, used only by `generateToken(UserDetails)` and `validateToken`), which will not compile here (§0). Any `JwtException` → `filternews.unauthorized` / 401.
3. Read the `role` claim. Not exactly `ADMIN` or `MODERATOR` → `filternews.forbidden` / 403.
4. The caller's email is `Claims::getSubject`. That string, and nothing else, is what goes into `createdby`.
5. **A signature-valid token whose `sub` is absent or blank is rejected with 401** [corrected]. Unreachable through the real issuer, which always sets `sub` — but without this a valid token could write `createdby = NULL`, making a Console-created row indistinguishable from a pre-Console one, which defeats the attribution the column exists for. 401 rather than 403 because it is a defect in the token, not in the caller's privileges. Do not remove this as dead code.

Config: `jwt.secret=${JWT_SECRET}` and `jwt.expiration=31536000` already exist in both `application.properties:173-174` and `application.prod.properties:177-178`. This repo has no `@ConfigurationProperties` anywhere, so bind with `@Value("${jwt.secret}")` rather than copying jx2's `JWTProperties` class.

Token facts, confirmed against the issuer: alg HS512 (jjwt selects it because the 94-byte secret exceeds 64 bytes, `JWT.java:38`), claims are exactly `{role, sub, iat, exp}` with `sub` = email, `role` is the `Role` enum serialised by name (`JWT.java:24`), the enum is exactly `{USER, MODERATOR, ADMIN}`, and `jwt.expiration` is 31536000 s = **365 days**.

No `User` row is read, written or upserted. `model/User.java` is untouched and there is no schema change to `user`. `createdby` is a plain string column on `FilterNews`.

### The 365-day role-staleness fact

**A user granted ADMIN or MODERATOR after their current token was issued keeps `role: USER` until they sign out and back in.** The role claim is baked into the token at login and the token lives a year. This is not a bug, and it will look like one.

It is reachable through real UI, which is why it matters: the frontend's role source and the backend's are two different stores. The menu gate reads `user.role` from the Redux `authentication.user` slice, which is populated from the login response body and persisted to `sessionStorage` (`Menu.js:601-604`), and the ADMIN-only `ProfilRole` radio group (`Menu.js:2416-2421`, `:2790-2814`) writes a role onto the profile record. So `user.role` can say ADMIN while the token still says USER — the entry renders and every call 403s.

**What the frontend must do on a 401 or 403:** close the popup, stop rendering the Console entry for the rest of the session, and show a message naming the remedy — sign out and sign back in. Do not retry, do not silently swallow it, and do not leave the entry visible: a visible entry that always fails is worse than an absent one. There is no refresh-token flow in this codebase to automate this.

---

## 6. `authHeaderWithJSON()` on a GET with no body

Resolved from code, not assumed. `src/helpers/auth-header.js:12-21`:

```js
export function authHeaderWithJSON() {
    let user = JSON.parse(sessionStorage.getItem('user'));
    if (user && user.jwt) {
        return { Authorization: `Bearer ${user.jwt}`, 'Content-Type': 'application/json' };
    } else {
        return { 'Content-Type': 'application/json' };
    }
}
```

It returns a flat header object with no reference to the method or the body, so **it sends `Authorization` on a GET exactly as it does on a POST**, and its shape is valid for a request with no body. Three existing GETs prove this in production (§0).

The only wrinkle is cosmetic: it also sends `Content-Type: application/json` on a bodyless GET. Harmless — Spring ignores `Content-Type` on a mapping with no `consumes`. **Do not add `consumes` to the `@GetMapping`**, and do not add it to the POST mappings either (none of the existing controllers do), or this breaks. Note the token is read from `sessionStorage`, not from Redux, so it survives a reload within the tab and dies with the tab.

Reuse the helper as-is. Do not write a new header helper.

---

## 7. CORS — confirmed from code

Copy the repo convention, `@CrossOrigin(exposedHeaders = "last")`, which all 19 existing controllers carry verbatim. No `origins`, no `allowedHeaders`, no `allowCredentials`.

No controller in this repo reads a request header today, so an `Authorization` preflight has never happened here. **This is confirmed from Spring 6.0.11's own bytecode, so it does not need to be observed at runtime:**

- `RequestMappingHandlerMapping.initCorsConfiguration` calls `CorsConfiguration.applyPermitDefaultValues()` unconditionally at offset 139, immediately before its `areturn`. The only early exit is the `aconst_null` at offset 50, taken when neither a type-level nor a method-level `@CrossOrigin` is present — not our case.
- `applyPermitDefaultValues` offsets 62-76: `if (allowedHeaders == null) allowedHeaders = DEFAULT_PERMIT_ALL`, i.e. `["*"]`. So `Authorization` is permitted in preflight.
- The Fetch-spec trap that `Access-Control-Allow-Headers: *` does **not** cover `Authorization` does not apply, because Spring never sends the literal `*`. `CorsConfiguration.checkHeaders` offsets 43-143: when `allowedHeaders` contains `*` it iterates the **requested** headers and returns each trimmed name, and `DefaultCorsProcessor` writes that resolved list via `setAccessControlAllowHeaders` (offset 188). The response therefore echoes `authorization, content-type`.
- Methods are covered twice over. `initCorsConfiguration` offsets 79-134: if `allowedMethods` is still empty after the annotation is applied, Spring adds the mapping's own declared HTTP methods from `RequestMappingInfo.getMethodsCondition()`. `DEFAULT_PERMIT_METHODS` is GET/HEAD/POST in any case.
- `allowedOrigins` becomes `["*"]` and `allowCredentials` stays null, i.e. false. That is fine because the client sends no cookies: no `fetch` call in `usercontainer.service.js` or `newspin.service.js` passes `credentials`, and none should here.

So the preflight succeeds with the plain annotation and nothing extra is needed. One residual that code cannot settle: if a reverse proxy in front of `api.onelike.app` strips or fails to pass `Authorization`, or answers `OPTIONS` itself, none of the above applies. That is infrastructure, not code. See §11, open question 2.

---

## 8. The menu entry

### Where it goes

`Menu.js:775-899`, inside `<Burger left width={'310px'} styles={styles}>`. Put the Console entry **immediately after the "API for AI Agents" entry at `:861-862`** and before "Social News" at `:864`. That groups the two signed-in-only tool entries together at the top of the list, above the public navigation and well above the legal block.

Note that `Menu.js:2416` is **not** a menu-entry precedent despite being in the same file — it is inside the profile dialog, 1500 lines from the burger.

### Which role check to copy

**`SpaceItem.js:2726-2730`** **[corrected]**. It is the only gate in the frontend that combines `authenticated &&` with both role checks and a popup-opening `onClick` **and has no owner-identity disjunct**, which is exactly this shape:

```js
{authenticated && !outofscopespace &&
    ((user && user.role && user.role.includes("MODERATOR")) ||
        (user && user.role && user.role.includes("ADMIN"))) ?
    <NumbersSharpIcon … onClick={(e) => handleOpenOutOfScopePopup()} />
    : null}
```

Drop the `!outofscopespace` term, keep the rest. The near-misses: `SpaceItem.js:2732-2737` and `:2742-2747` look identical and *do* open popups, but each adds `(user && createdby && user.uuid === createdby.uuid)` as a first disjunct — copy one of those and a row's own author gets the Console, whoever they are; `Message.js:617` is the same two role checks in ternary form but without `authenticated`; `ProfilItem.js:642` and `:753` are the same minus the popup; `SpaceItem.js:637-638` is the negated form inside a handler, not a render gate; `Menu.js:2416` is ADMIN-only. (This section said `SpaceItem.js:2726-2730` was "the only one of **the six** role checks" with that shape. The structural reason above is the real one and needs no count; the count itself did not reproduce, and neither did the "ten across five files" offered as its replacement. The reproducible figure, measured 2026-10-11 with `rg "role\.includes\(" src/` in `onelike-agentic`, is **16 matching lines across 4 files** — `SpaceItem.js` 9, `Menu.js` 3, `Message.js` 2, `ProfilItem.js` 2. Corrected on 2026-10-11 against the frontend source.) In `Menu` the two bindings already exist: `authenticated` at `:161` (`loggedIn && user`), and `user` is local state kept in sync with Redux by `useEffect(() => setUser(u), [u])` at `:195-197`.

`user.role` is a **string**, not an array — `ProfilRole`'s `defaultValue={user && user.role ? user.role : "USER"}` (`:2807`) feeds a `RadioGroup`. `.includes()` is therefore substring matching, which is safe here: `"ADMIN"` is not a substring of `"MODERATOR"` or `"USER"`.

### The react-burger-menu trap

The comment at `Menu.js:860` — "p and br must be direct Burger children (no fragment) so they get the same bm-item inline-block style as the other entries" — is enforced by `node_modules/react-burger-menu/lib/menuFactory.js:403-415`:

```js
React.Children.map(props.children, function (item, index) {
  if (item) {
    var classList = ['bm-item', props.itemClassName, item.props.className]…
    var extraProps = { key: index, className: classList,
                       style: getStyles('item', index, item.props.style),
                       tabIndex: isOpen ? 0 : -1 };
    return React.cloneElement(item, extraProps);
  }
})
```

Two consequences. The `if (item)` guard means a conditional yielding `null` is safely skipped, so `{cond ? <p>…</p> : null}` is correct. But a conditional yielding a **Fragment** is one child: the fragment gets cloned with `key`, `className`, `style` **and `tabIndex`**, a Fragment ignores all of them, and the `<p>`/`<br/>` inside it silently lose their `bm-item` styling **and their `tabIndex: isOpen ? 0 : -1` — so a fragment-gated entry also stops being keyboard-focusable** **[corrected]**, which is an accessibility regression, not only a cosmetic one. It does not throw — it just renders wrong. (This passage named only `className` and `style`; `tabIndex` is the third cloned prop and was missing. Verified 2026-10-11 in `onelike-agentic/node_modules/react-burger-menu/lib/menuFactory.js` at v3.0.8.) So gate with **two separate ternaries**, one returning the `<p>` and one returning the `<br />`, or with a keyed array (`React.Children.map` flattens arrays); never with `<>…</>`.

### The popup trigger

Copy `handleApiMenuClick` (`Menu.js:282-289`) and its `useEffect` at `:273-279` that force-closes the dialog when `authenticated` goes false. For Console, extend that effect to also close when the role check stops passing, and to close on a 401/403 per §5.

### Service file

New `src/services/filternews.service.js`, modelled on `usercontainer.service.js` — specifically its `toResult(res)` (`:61-71`) and `networkResult()` (`:75-77`), which resolve every call to `{ ok, status, body }` and report a rejected fetch as `status: 0` rather than throwing. The error branch in §10 needs the status code and the parsed body together, which `newspin.service.js`'s bare `res.json()` cannot give you. Do **not** model it on `outofscope.service.js`: that is the right precedent for the *gating*, but it posts to `workerConstants.WORKER`, a different backend.

---

## 9. Popup copy — pinned

Load-bearing, because there is no feedback mechanism: these strings are the only thing standing between the admin and a silently useless filter. Use them as written.

Dialog title:

> Filter a publisher

Body, above the input:

> Enter the publisher's name **exactly as it appears after the dash at the end of a Google News headline**.
>
> For a headline ending `… - The Hacker News`, type `The Hacker News` — not `thehackernews.com`.
>
> Matching is case-sensitive and must match exactly. `the hacker news` will not filter anything.

Below the input, as helper text:

> The filter applies as each news card refreshes — typically within the hour, not immediately.

Input placeholder:

> The Hacker News

Confirm button:

> Add filter

The timing sentence is accurate, not hedging — and is in fact **conservative** [corrected]. The chain is: the row is written to the database immediately, and the fetch path then sees it on its **very next run**, with no cache delay at all. An earlier draft of this section claimed up to 10 minutes because `filter_news` carries `@Cacheable`; that was wrong. `NewsService.getAllFilterNews()` calls `allFilterNews()` **unqualified**, and a self-invocation bypasses Spring's caching interceptor under the default proxy-mode AOP, so the annotation never applies on the read path. `SearchController` calls `getAllFilterNews()` at both of its call sites, so every feed fetch queries the database directly. Measured, not merely read: two `POST /service/searchnews` calls advanced the `WHERE active = ?` statement digest by exactly 2. So the only delay is that each language's own job must run before its feed is re-filtered, on a ladder of odd-minute `fixedRate`s from **5 minutes** (English, `SearchController.java:954`) to **33 minutes** (Greek, `SearchControllerGR.java:69`) — 7 ES, 9 DE, 11 FR, 13 PT, 15 HI, 17 INDO, 19 RU, 21 CH, 23 JP, 25 AR, 27 IT, 29 TR, 31 NL in between. Worst case for a built-in platform card is about 43 minutes. User theme cards are slower still: `ThemeRefreshService` runs on a 30-minute `fixedDelay` and refreshes **at most five themes per tick** (`application.properties:100,102`), so with many stale themes a theme card can lag past the hour. "Typically within the hour" is the honest summary; do not promise instant and do not promise a hard ceiling.

Already-filtered articles are not retroactively removed — `isGoodNews` runs at fetch time, so a card clears only when its own feed is next re-fetched. The copy says "as each news card refreshes" for exactly that reason; do not simplify it to "within the hour".

---

## 10. Errors

Always this shape, from `errorService.createApiError`:

```json
{ "status": "BAD_REQUEST",
  "message": "[Error number: 7f3a9c] Bad Request : Invalid publisher name",
  "errors": ["filternews.name.invalid"], "warnings": null }
```

**Branch on the HTTP status code and `errors[0]` only.** Never on `message` — `ErrorService:20-21` prefixes it at runtime with a random `[Error number: …]`. Never on `body.status` — `ApiError.status` is an `HttpStatus` enum and serialises as the **name** string `"BAD_REQUEST"`, not a number.

| HTTP | `errors[0]` | What the popup does |
|---|---|---|
| 400 | `filternews.name.invalid` | inline error on the input; popup stays open |
| 400 | `filternews.id.missing` | client bug; log it |
| 400 | `filternews.active.missing` | client bug; log it |
| 401 | `filternews.unauthorized` | close the popup, stop rendering the entry, tell the user to sign out and back in (§5) |
| 403 | `filternews.forbidden` | identical handling to 401 — same message, same remedy |
| 404 | `filternews.notfound` | treat as already-deleted: drop it from the local list, no error UI, then re-fetch the list |
| 500 | varies, **may be `null`** | generic failure; re-fetch the list |
| 0 | — | `networkResult()`; network, DNS or CORS. Treat the write as outcome-unknown and re-fetch |

`errors[0]` can be `null` on a 500: that branch passes an arbitrary caught exception whose `getMessage()` may be null, and `Arrays.asList(null)` yields a one-element list holding null. Guard the read.

**And `errors` can be absent entirely, not merely null** [corrected] — so **read `body.errors?.[0]`, never `body.errors[0]`**. A malformed or missing request body is rejected by Spring during argument resolution, *before* the controller method runs, so the try/catch never sees it and the response is Boot's default error body: `{timestamp, status, error, message, path}` with **no `errors` key at all**. Observed for truncated JSON, an absent body, and a type-coercion failure such as `{"id":"abc"}`. Not reachable from the real client, which sends `JSON.stringify` output — but a defensive read costs nothing and an undefined dereference would throw inside the error handler itself.

Validation worth pre-enforcing client-side to save a round trip: trimmed, 1-120 characters, no control characters. The server must re-check all three — the 120 ceiling is a bridge choice, see §11 question 3.

One operational note for the backend builder: `ErrorService` calls `log.error` and `error.printStackTrace()` on every error with a non-null throwable. Since 401 and 403 flow through the same path, a scanner hitting these public paths will print stack traces. Passing the throwable is non-negotiable (§4d), so accept the noise this cycle rather than inventing a second error shape.

---

## 11. Open contract questions

Neither builder can settle these alone. Listed rather than guessed.

1. **Does `api.onelike.app`'s deployed `JWT_SECRET` equal `communo.onelike.app`'s?** The HMAC-SHA512 check passed against this repo's local `.env`, which is evidence about dev only (§1). If the two deployments differ, every Console call returns 401 with a *valid* token, and the symptom will look like the role-staleness fact in §5. **This is the one open question that is undiagnosable from the client, and it is a deploy-environment check rather than a code question — do not schedule code work for it.** Measured 2026-10-11: `Bearer not.a.real.token` returns a byte-identical response to sending no `Authorization` header at all — same 401, same `filternews.unauthorized`, differing only in the random error number — so "header stripped in transit", "signature rejected" and "no header sent" cannot be told apart from outside. Confirm it in the deployment environment before the frontend builder starts debugging. (The same probe shows the four endpoints are live on `api.onelike.app`: all of them answer the app's own `{"errors":["filternews.unauthorized"]}` where a bogus sibling path under `/service` answers Boot's generic 404 with no `errors` key, so both the controller and the token gate are deployed and no local backend is needed to exercise them. Deployment state is a measurement with a shelf life, not a standing fact — re-measure rather than trust this line.)
2. **Does anything in front of `api.onelike.app` strip `Authorization` or answer `OPTIONS` itself?** §7 settles Spring's behaviour from bytecode. It cannot settle a proxy's. This is the one part of the CORS question that genuinely must be observed rather than read.
3. ~~**`name` maximum length.**~~ **Settled by the user at the gate: 120** [corrected]. Bridge proposed it as its own unconfirmed choice; the user confirmed it, so the frontend should mirror 120 into the input's `maxLength` without treating it as provisional. The column is an unconstrained `String`, so Hibernate makes it `VARCHAR(255)`; the neighbouring convention is `UserNewsContainerService.TITLE_MAX_LENGTH = 40`, which is too short for real publisher strings (`The Chronicle of Higher Education` is 33 and longer ones exist). 120 sits between the two.
4. ~~**Should MODERATOR be able to hard-delete, or only ADMIN?**~~ **Settled by the user at the gate: both roles, all four endpoints, hard delete included** [corrected]. The reasoning offered and accepted: `active: false` covers the reversible case, and a moderator trusted to filter publishers is trusted to tidy the list. The frontend should render delete for both roles.
5. ~~**Should a create evict the `filter_news` cache immediately?**~~ **Moot** [corrected]. This question rested on the false premise corrected in §9: the fetch path never reads through the cache, because of the self-invocation in `NewsService.getAllFilterNews()`. There is no cache lag to close. The decision not to evict is therefore right for a better reason than the one originally given, and no `@CacheEvict` is needed — which is also why none was added.

---

## 12. File pointers

Re-locate by symbol; line numbers shift.

**Backend** (`C:\Users\bayon\.git\onelike-backend-agentic`):
- `src/main/java/com/onelike/app/controller/UserNewsContainerController.java` — the controller shape to copy: try/catch per method, `createContainerError`/`statusOf`/`messageOf`, `JSONObject.toJSONString()` in a `ResponseEntity`
- `src/main/java/com/onelike/app/service/UserNewsContainerService.java` — `wireDate` (the `Date`/`Timestamp` choke point) and its comment
- `src/main/java/com/onelike/app/model/FilterNews.java` — add `createdby` (String) and `createdat` (Date), both nullable; no other change
- `src/main/java/com/onelike/app/repository/FilterNewsRepository.java` — `getActiveFilterNews` stays as-is; the Console reads `findAll()`
- `src/main/java/com/onelike/app/service/NewsService.java` — `allFilterNews` / `getAllFilterNews`, whose `@Cacheable` never applies on a read at all (unqualified self-invocation — see §9)
- `src/main/java/com/onelike/app/service/CacheService.java`, `service/CacheLoader.java` — the 10-minute cache-refresh tick — real, but **not** behind the §9 timing: no read path consults that entry (see §9)
- `src/main/java/com/onelike/app/controller/SearchController.java` — `isGoodNews`, and `articlesOf`'s load of the filter list
- `src/main/java/com/onelike/app/util/OtherTool.java` — `endsWith`
- `src/main/java/com/onelike/app/exception/ContainerException.java` — the one-line template for `FilterNewsException`
- `src/main/java/com/onelike/app/service/ErrorService.java`, `error/ApiError.java` — why `errors[0]` needs the throwable
- `C:\Users\bayon\.git\onelike-backend-jx2-agentic\src\main\java\com\onelike\app\security\JWT.java` — the parser call to copy, minus its Spring Security imports

**Frontend** (`C:\Users\bayon\.git\onelike-agentic`):
- `src/components/Menu.js` — the `<Burger>` block, the insertion point after "API for AI Agents", `handleApiMenuClick` and its `authenticated` effect, `authenticated`/`user` bindings
- `src/components/SpaceItem.js` — the role-gate precedent to copy
- `src/services/usercontainer.service.js` — `toResult` / `networkResult`, and a live bodyless GET through `authHeaderWithJSON()`
- `src/helpers/auth-header.js` — `authHeaderWithJSON()`, unchanged
- `src/constants/worker.constants.js`, `.env` — the two hosts in §1
- `node_modules/react-burger-menu/lib/menuFactory.js` — the child-cloning code behind the fragment trap

---

## 13. A second write path into `filter_news` — the agent endpoint

Added after the Console shipped. **This file previously implied the Console was the only way a row could be written, and that `createdby` was always a token subject. Both are now false**, so the correction belongs here rather than in a commit message.

### `POST /service/filternews/agent`

```json
{ "name": "The Hacker News" }
```

Same request DTO (`FilterNewsDTO`), same validation, and the response body is **the `POST /service/filternews` body verbatim** — `{added, reactivated, filter{…}}` — because both endpoints are one call into `FilterNewsConsoleService.createFilter`. An already-present, already-active name is the same idempotent `200`.

**Add-only.** There is no agent equivalent of the list, the toggle or the delete, and that is a design choice, not an omission: the key is shared and carries no identity, so a leaked key can add rows (visible in the Console and undoable there) and cannot empty the list.

**`createdby` is the literal string `"agent"`** on every row this endpoint inserts — never null, never an email. So the column now holds one of three things: a Console caller's email, `"agent"`, or `null` for a row predating both paths. Read it as an opaque label and keep the frontend's existing null fallback; `"agent"` renders as-is with no frontend change.

### Auth — a shared API key, not a token

Not `Authorization: Bearer`. One shared key from the environment (`onelike.news.filter.apikey=${ONELIKE_NEWS_FILTER_APIKEY}`, no inline default), checked by `support/AgentApiKey` with `MessageDigest.isEqual` and both sides trimmed.

**"No inline default" protects against an *absent* variable, not an empty one.** Absent, the placeholder fails to resolve and the app refuses to start, naming this property — measured. But `ONELIKE_NEWS_FILTER_APIKEY=` resolves to `""`, the app **starts normally**, and the gate then fails closed: a permanently-401 endpoint with no startup error and no log line. That is the right failure mode and the wrong one to be surprised by, and it is exactly the state reached by copying `.env.example` and not filling the value in. If every call 401s, check for an empty variable before suspecting the key.

Because both sides are trimmed, a key may not meaningfully begin or end with whitespace — surrounding whitespace on a correct key is **accepted**, deliberately, so a copy-paste newline cannot lock an operator out.

Accepted **both** ways. `?apiKey=` wins over `X-API-Key` **only when it is non-blank**; an absent, empty or whitespace-only `?apiKey=` falls back to the header (`FilterNewsAgentController.java:106`, `(apiKey != null && !apiKey.isBlank()) ? apiKey : headerKey`). The fallback half is pinned by `FilterNewsAgentControllerAuthTest.blankApiKeyQueryParameterFallsBackToTheHeader`: an empty and a whitespace `?apiKey=`, each sent with the correct key in the header and a blank name, answer 400 `filternews.name.invalid`, which means they were authenticated through the header. The winning half — a non-blank but wrong `?apiKey=` overriding a correct header, answering 401 — is read from `:106` only. No test sends both transports with a non-blank query parameter.

| transport | name |
|---|---|
| query parameter | `?apiKey=…` |
| header | `X-API-Key: …` |

Those two names and that precedence, including the non-blank condition, are copied from the sibling repo's `PublicApiController.createDiscussion`: `../onelike-backend-jx2-agentic/src/main/java/com/onelike/app/controller/PublicApiController.java:104` reads `(apiKey != null && !apiKey.isBlank()) ? apiKey : headerKey` at jx2 HEAD `ea0f828` (`git show HEAD:… | grep -F 'rawKey ='`, cycle 2026-10-19 B). An agent already written against that API therefore needs no new convention.

**Prefer the header.** A query string is written verbatim to `logs/onelike.log`: both properties files set `logging.level.org.springframework.web=DEBUG`, and `DispatcherServlet`'s request log masks the parameter map but concatenates `getQueryString()` with no guard. The parameter exists for compatibility, not because it is safe.

### Errors

Two codes, plus the repo-standard catch-all. Same `ApiError` shape as §10.

| HTTP | `errors[0]` |
|---|---|
| 401 | `filternews.apikey.invalid` |
| 400 | `filternews.name.invalid` |
| 500 | varies, may be `null` |

`filternews.apikey.invalid` covers **missing, blank, wrong, and "the server's own key is unset"** with one code and one status, deliberately: distinguishing them would tell a prober whether they have the parameter name right. "Missing" and "blank" mean missing or blank in **both** transports. A blank `?apiKey=` next to a correct `X-API-Key` is not a 401: it falls back to the header and authenticates (see the precedence paragraph above). "Wrong" means the key actually presented is wrong. That includes a non-blank wrong `?apiKey=` sent next to a correct header, because the query parameter wins; that case is read from code and not tested.

**Read it as `body.errors?.[0]`, not `body.errors[0]`.** A malformed or absent request body is rejected by Spring during argument resolution, before this controller's try/catch exists, so the response is Boot's own — with **no `errors` key at all**, undefined rather than null. That path needs no key, so an unauthenticated caller can reach it. Same hole as §10; stated here because an agent author has no reason to read the Console's error section.

### Two things this endpoint deliberately does not have

- **No `@CrossOrigin`**, unlike all 19 other controllers here. CORS is browser-enforced only, so it protects nothing on a server-to-server call, and a shared global key has no business in a web page where any visitor could read it out of the JavaScript. A browser therefore cannot read this response cross-origin, which is the correct signal.
- **No global row cap.** Considered and declined at the gate.

### Shared with the Console

`FilterNewsConsoleService.normaliseName(String)` is now the single choke point for the name rules (trim, non-empty, `≤ 120`, no ISO control characters) and both controllers call it. The Console's wire behaviour is unchanged — the rules and their order are what `FilterNewsController` enforced inline before the method existed.

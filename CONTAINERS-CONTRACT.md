# User news containers — wire contract (phase 1)

The contract the backend implements and the `onelike-agentic` frontend builds against, for custom themed cards in Social News.

Backend phase 1 is **built, verified and committed** (`c69c7e7`). The 30-minute theme refresh landed in `555e74f`. In the current cycle a theme's `linkspreview` stops being empty. All four endpoints work live and the dev schema is correct. Nothing on the backend blocks the frontend.

## Sibling state — one reading, one stamp

**As of the `2026-10-12` cycle** (the loop's own unit: `LEARNINGS.md` labels two distinct cycles "2026-10-11 (fifth)" and "(sixth)", so a bare date cannot identify a reading).

- **Backend** (`onelike-backend-agentic`, `master`): phase 1, the 30-minute refresh, `linkspreview`, the per-user theme allowance and the index-0 front-insert are all built and verified; `themes` is live in production. Nothing on the backend blocks the frontend.
- **Frontend** (`onelike-agentic`, branch `feature/theme-allowance-ui`): HEAD `c9ddd15`, **9 commits not in `origin/main`** (tip `e86fb55`; `git rev-list --count origin/main..HEAD`). The whole themes feature (`574720a`, `916f399`), the allowance half (`c9ddd15`) and the front-insert `resync()` are **written and unreleased**. `git ls-tree -r --name-only origin/main | grep -i "usercontainer\|CardConfigPopup"` returns nothing and `git merge-base --is-ancestor 574720a origin/main` exits non-zero, so **no released client is a consumer of these endpoints at all** — no Add button, no card-config popup, no request to `/mycontainers`.
- Every client behaviour this file describes exists **in that branch's code**, cited by file and symbol. It is not what a user is running. A **code-state** claim ("the client reads this field") is measurable with `Grep` by any stage; a **release-state** claim ("users have this") is not, and must never be inferred from the first.
- **§ *Theme allowance* is therefore half built**: the backend half landed at `d6308e3` and is live; the client half is the forward-spec part. **Delete that marker when the client half is *released*, not merely written** — and only then.
- **Release that branch whole or not at all.** Releasing the themes commits *without* `c9ddd15`'s allowance UI would ship a flat cap of ten against a backend defaulting to 1 — enabled Add button, an up-to-a-minute wait, then a 409 carrying no `themes` to explain it. Settled at gate, 2026-10-11: the backend ships first at a default of 1, with the consequence accepted, and the consequence is **deferred to the client's release** rather than live, because nothing released consumes these endpoints.

**Re-taken whole.** Per-claim datestamps were tried and produced a header that disagreed with itself (`a66cb32`/8 commits vs `c9ddd15`/9 commits, both correct on the day each was written). This block is re-measured as a unit or not at all.

Every field name **and every dynamic** below was verified against the real code on both sides — `controller/UserNewsContainerController.java`, `service/UserNewsContainerService.java`, `model/dto/Container*.java`, `controller/SearchController.java` (`articlesOf`, `giveMeArrayOfLinks`), `util/ArticleImageCache.java`, `service/ThemeRefreshService.java`, `service/CleanupService.java` here; `src/components/Item.js`, `src/components/SocialMediaListRender.js`, `src/components/CardConfigPopup.js`, `src/components/Home.js`, `src/services/usercontainer.service.js` there — **not** relayed from any planning-stage spec. Where this file and an earlier `bridge` spec disagree, this file is right. Re-checked in full on 2026-10-11, the `linkspreview` cycle.

Three claims about the frontend that a later read of `onelike-agentic` falsified are marked **[corrected]** where they appear, each with a parenthetical saying what was wrong — the point of this file is to be a record of *verified* claims, so a silent repair would lose the fact that the claim was once believed.

Frontend work runs that repo's own six-stage loop: start at its `planner`, apply its confirmation gate, then `builder`.

---

## Base URL

Always the **unsuffixed** `process.env.REACT_APP_ONELIKE_BACKEND_API` (`https://api.onelike.app/service`), even in a non-English build. The per-language variables (`_DE`, `_FR`, …) point at `/dienst`, `/servicefr` … which are different controllers that do **not** have these endpoints. Mirror `newspin.service.js`; do not copy `socialmedia.service.js`'s 15-language-copies pattern here.

All mutations are POST — the **container API** has no PUT and no DELETE **[corrected]**. (This read "this repo has no PUT or DELETE anywhere", which is false of the frontend: `src/services/apikey.service.js:35` sends `method: 'DELETE'` to the Cloudflare worker. Only the narrow claim about these four endpoints holds. Corrected on 2026-10-11 against the frontend source.)

---

## Endpoints

### `GET /mycontainers?uuid={uuid}&language={language}`

Both params optional. Returns `200` always — never 404, never an error for an unknown user.

```json
{ "seeded": true,
  "themes": { "used": 1, "allowed": 1 },
  "containers": [
    { "kind": "THEME", "name": "AI", "position": 0, "id": 42,
      "news": [ ], "last_update": "Sat Oct 10 17:06:51 CEST 2026",
      "description": "", "website": null,
      "linkspreview": [ { "title": "…", "url": "https://publisher.example/a",
                          "image": "https://publisher.example/og.jpg", "timeago": "3 hours ago" } ] },
    { "kind": "PLATFORM", "name": "Twitter", "position": 1 }
  ] }
```

PLATFORM entries carry **only** `kind`, `name`, `position`. THEME entries add `id`, `news`, `last_update`, `description`, `website`, `linkspreview`.

Missing, blank or unknown `uuid`, or a user with no rows → `seeded: false` plus the 20 built-in platforms at positions 0-19. `seeded` means "has this user ever customised anything" and is deliberately **not** per-language. An unknown or absent `uuid` also reads `themes: { "used": 0, "allowed": 1 }` — the allowance a brand-new user would get, since there is no row to read a quota from. It is copy material only, never authority: this is a login-gated feature.

### `POST /container`

```json
{ "uuid": "", "firstname": "", "name": "", "email": "",
  "displayname": "", "title": "AI", "language": "English" }
```

`name` is the **caller's surname** (the same identity block as `PinDTO`). The container's own title is `title`.

Returns `{ "created": true, "container": { }, "themes": { "used": 1, "allowed": 1 } }`.

`themes` is **post-insert**: a successful create answers the count that includes the row it just made, so a client that disables its Add button from `used >= allowed` is correct immediately, without a re-fetch. The `created: false` branch carries `themes` too, with the unchanged count.

**A created theme lands at index 0, ahead of every platform.** The new row is written at stored `position` 0 and every other row of that user shifts +1, in a single `containerRepository.saveAll`. So a successful create answers `container.position: 0` — not 20 — and the next `GET /mycontainers` for that language returns the new card as `containers[0]`. **Multiple themes therefore read newest-first until the user saves an order**, which falls out of the same rule.

Settled at gate, 2026-10-11. "First among the themes, after the platforms" was rejected as not well-defined: once a user has reordered, platforms and themes interleave in one dense `position` sequence with no boundary to insert at. Index 0 is the only rule that survives a reorder. Accepted consequence: until a user reorders, their own cards outrank all 20 built-ins.

**Write-time only.** `POST /containers/order` overwrites every position wholesale and wins permanently; nothing re-asserts index 0 on a read, and no flag records "this user has reordered" because none is needed. A read-time sort was rejected for exactly this reason — it would silently fight a saved order with no way to tell the two states apart.

**`created: false` moves nothing.** A duplicate title returns the existing container at its existing position. It is not re-inserted at 0.

**A create never changes another language's order.** `containersJson` skips a row that `isRendered` rejects *before* assigning a wire index — `continue` at `UserNewsContainerService.java:790-792`, and `result.size()` is read only on the kept branch at `:794`/`:797` — so a German theme at stored position 0 consumes no index in the English list, and Twitter is still English `position` 0. The stored sequence is one sequence shared by all languages; the rendered sequences are not coupled by it. `wirePosition` (`:682-694`) uses the same predicate, so the create response agrees with the next listing.

A duplicate title for the same user *and language* returns `200` with `created: false` and the existing container — idempotent by design, not an error, so a double-submitting popup is safe.

### `POST /container/delete`

```json
{ "uuid": "", "firstname": "", "name": "", "email": "", "displayname": "", "id": 42 }
```

Returns `{ "deleted": true, "themes": { "used": 0, "allowed": 1 } }`. THEME rows only; a PLATFORM id returns 404.

`themes` is **post-delete** and is load-bearing, not symmetry: `usercontainer.reducer.js`'s `USERCONTAINER_DELETE_SUCCESS` filters the row out locally and `CardConfigPopup.js`'s `handleConfirmDelete` deliberately does not re-fetch on success, so without it `used` would stay stale-high and the Add button would remain disabled at exactly the moment the user had made room.

### `POST /containers/order`

```json
{ "uuid": "", "firstname": "", "name": "", "email": "", "displayname": "",
  "language": "English",
  "containers": [ { "kind": "PLATFORM", "name": "Twitter" }, { "kind": "THEME", "name": "AI" } ] }
```

Returns `{ "ordered": true, "containers": [ ] }` — the full list in its new order.

**The highest-risk item in this integration.** Each array element must carry **exactly `kind` and `name`**. The natural source is the merged render list, whose elements each hold the full `news[]` array, so a naive `.map()` POSTs megabytes of article JSON on every drag. Project explicitly:

```js
list.map(({ kind, name }) => ({ kind, name }))
```

Send the **complete** ordered list. Omitted rows are appended, never deleted — reorder is non-destructive. **Treat the echoed `containers` as authoritative** and re-render from it, discarding the optimistic local ordering: the client cannot predict append-of-omitted or ignore-of-unmatched.

---

## Language rules

- A theme belongs to the language it was created for. A theme created for English **does not appear** in German Social News at all.
- Send the backend's own language strings: `English`, `Deutsch`, `Francais`, … — **not** `German` or `French`. An unrecognised value silently falls back to English.
- The same title can exist **once per language**, so "Crypto"/English and "Crypto"/Deutsch are two separate cards, each consuming one unit of the user's allowance.
- **The theme cap is global across all languages.** Each language shows only its own themes, but the cap counts every language together: five English plus five German is ten. The cap is a per-user allowance, not a fixed ten — see § *Theme allowance*.

---

## Theme allowance

*Settled at gate, 2026-10-11. **Backend half built and live**: `d6308e3`, and `GET /service/mycontainers` returns `themes` in production (observed `themes: {"allowed": 1, "used": 0}` against `api.onelike.app`, 2026-10-11 23:18 +0200, reported by the frontend side rather than measured here). **The client half is the forward-spec part** — see the header.*

Every user has their own theme allowance. **Default 1**, raised per person by an admin with raw SQL in the nullable `user.themequota` column, and **10 is a hard ceiling** no per-user value can exceed — enforced by the configurable `onelike.containers.max-themes-per-user`, which reads `10` in both `application.properties` and `application.prod.properties`; **no code constant pins it**, so a deployment could in principle carry another number. `NULL` means "use the configured default" — it is not the same as `0`. Read-time clamping to `[0, ceiling]` means a hand-typed `25` behaves as `10` and a negative as `0`; `0` legitimately means "no custom cards at all". **No role is read anywhere on this path** — this repo's `User` has no `role` column and the allowance is per person, not per tier.

Enforcement is **at create only**. Nothing is ever deleted, hidden or renumbered because an allowance was lowered, so a user holding 3 themes whose allowance is 1 keeps all 3, reads `used: 3, allowed: 1`, and simply cannot add a 4th until they are below 1. `used > allowed` is therefore a **normal state, not an error** — the client must render it, not treat it as corrupt data.

### The `themes` object

`GET /mycontainers`, `POST /container` and `POST /container/delete` each return, as a **sibling** of their other top-level keys:

```json
"themes": { "used": 1, "allowed": 1 }
```

- **Always present on every 2xx response of all three**, in every success branch — including `created: false`, including a missing/blank/unknown/absent `uuid`. **Never present on any error body, whatever the status** — *measured* on 400 `container.title.invalid`, 400 `container.title.reserved`, 403 `container.identity.mismatch`, 404 `container.notfound` and 409 `container.limit.reached`, because all five are `ApiError`, which is `status`/`message`/`errors`/`warnings` only. **Do not implement "absent ⇒ old backend"** — that reading was in an earlier draft of this line and it would mis-handle every 403 and 404. Treat absent-on-2xx as the old-backend signal; on a non-2xx, keep the last `themes` you held.
- `used` and `allowed` are **always JSON numbers, never null, never absent, never strings.** `used >= 0`; `allowed` is already clamped to `[0, ceiling]` — `[0, 10]` under the config as deployed.
- `used` counts **every THEME row the user owns, in every language** — including languages this response is not showing. A user whose only theme is German, reading English, gets zero THEME entries in `containers` and `themes: { "used": 1, "allowed": 1 }`. That is correct and is the single likeliest source of a frontend bug: copy that says "1 of 1" next to an empty card list looks broken unless it also says some are in another language.
- `allowed` is **not** per language. There is no per-language allowance.
- `POST /containers/order` deliberately does **not** carry `themes`: reorder cannot change the theme count.
- A **409 `container.limit.reached` carries no `themes`** — `ApiError` is `status`/`message`/`errors`/`warnings` only. On a 409 the client must use the last `themes` it received, or number-free copy if it has none.

## Errors

Always this shape, from `errorService.createApiError`:

```json
{ "status": "BAD_REQUEST",
  "message": "[Error number: 7f3a9c] Bad Request : Invalid title",
  "errors": ["container.title.invalid"], "warnings": null }
```

**Branch on the HTTP status code and `errors[0]` only.** Never on `message` (prefixed at runtime with a random error number) and never on `body.status` (the enum *name* string, not a number).

| HTTP | `errors[0]` | What the popup should do |
|---|---|---|
| 400 | `container.identity.missing` | should not happen if login-gated; treat as a session problem |
| 400 | `container.title.invalid` | inline error on the title input; popup stays open |
| 400 | `container.title.reserved` | inline error — the title matches a built-in platform name |
| 400 | `container.id.missing` / `container.order.empty` | client bug; log it |
| 403 | `container.identity.mismatch` | **not** a validation error. Revert the optimistic change, close the popup, show the generic sign-in prompt, re-fetch `/mycontainers` |
| 404 | `container.notfound` | treat as already-deleted: remove locally, no error UI, then resync |
| 409 | `container.limit.reached` | inline notice naming **the allowance from the last `themes` received** — never a hardcoded ten; the 409 body itself carries no `themes`. With no `themes` ever received, use copy that names no number. Popup stays open |
| 500 | varies | generic failure; resync |

Title rules worth pre-enforcing client-side so the user avoids a round-trip: trimmed, 1-40 characters, no control characters, and not equal (case-insensitively) to any of the 20 platform names.

---

## Identity

The same five fields the Pin feature already sends, from the same Redux slice (`SocialMediaListRender.js` already selects `lin` / `u` / `authenticated`). Reuse Pin's `undefined`-replacer trick so an absent `displayname` is sent as `""` rather than dropped.

Send **only** the documented fields — not the whole `authentication.user` object, which carries `jwt`, `token` and `picture`. The DTOs do tolerate unknown fields (`@JsonIgnoreProperties`), but that is a safety net, not a licence.

One asymmetry to know about: **create** upserts the user, while **delete** and **reorder** require `uuid` and `email` to resolve to the same existing user — *except* when neither resolves at all, which is treated as a legitimate first write. So a brand-new logged-in user's very first drag works.

The `Authorization: Bearer` header from `authHeaderWithJSON()` is sent but **ignored** by the backend. This is a product gate, not a security boundary.

---

## Division of labour

The API owns **order and membership**. The client owns **icons and background images**. Neither crosses: the API never sends an icon name, asset path or colour.

Three things were needed on the client for `kind === "THEME"`. All three are **implemented in the `feature/theme-allowance-ui` branch** at `916f399` — written, not released (see the header) — recorded here because the division of labour rests on them:

1. **`kind` is destructured** in `SocialMediaNews` (`Item.js:104`) and `SocialMediaNewsPreview` (`:255`, `:258`) — the components this feature calls "SocialMediaCard". There is no file by that name.
2. **Icon.** `Logo` now takes a `kind` prop and returns `<></>` for THEME *before* the `switch (name)` (`Item.js:552-561`), so a theme called "AI" can never fall into a platform case. **Deliberate deviation from the original spec**, which asked for a generic icon: the client chose no icon at all. Icons are client-owned, so this is its call.
3. **No background image for THEME** **[corrected]**. `Image` does derive `/<name.toLowerCase()>-image-<theme>.png`, but an unmatched name never *requests* it: its `switch (name)` ends `default: return <></>` (`Item.js:712-713`) and `imageLocation` is read only inside a matched `case` body, so for a theme no `<img>` is created and no HTTP request is issued. (This passage claimed a theme "would request `/ai-image-dark.png` and 404"; that cannot happen. Corrected on 2026-10-11 against the frontend source.) The real hazard was a **blank card**: the ~5-second `setInterval` at `Item.js:171-182` randomly promotes a card to the image-only layout, whose only content is `<Image … />` — which for a theme renders nothing at all. Already fixed in the frontend: the interval returns early when `isTheme` (`:167-169`), the call site is guarded by `isTheme ? <></> : <Image … />` (`:399`), the description-only and image-only layouts are folded back to layout 1 for a theme (`:193`, `:352`), and the `InfoIcon` that was the only other way to reach them is no longer rendered on a theme card (`8b495af`).

Also: `website` is `null` for themes, so prefer skipping the `<a>` wrapper over rendering a non-clickable link; `description` is `""`.

### The merge with `/service/news`

`/service/news` still provides the 20 built-ins' **content**; `/mycontainers` provides **order and membership**. Merge in `SocialMediaListRender.js` (shared by all **15** language wrappers **[corrected]** — a glob of `src/components/SocialMediaList*.js` returns 16 files, but one of them is `SocialMediaListRender.js` itself, which is the merge point and not a wrapper; this read 16. Corrected on 2026-10-11 against the frontend source) and feed the result to `CustomPagination`.

Iterate `containers` — the API's order is the spine — not the news array:

```
for each c of containers:
  if c.kind === "THEME":    emit c as-is
  if c.kind === "PLATFORM":
    match = news.socialmedias.find(s => s.name === c.name)   // exact case
    if match: emit { ...match, kind: "PLATFORM" }
    else:     skip
then append any news.socialmedias entry that matched no container,
     in its original order, tagged kind:"PLATFORM"
```

A PLATFORM container with no `/service/news` entry is **skipped**: it means the feed job has not produced that platform yet, and a header with no articles is worse than its absence. A `/service/news` entry with no container is **appended**, mirroring the backend's own reconcile-on-read so the two agree.

Matching is **exact-case** on `name`. The casing is load-bearing — `Linkedin` (lowercase k), `YouTube`, `TikTok`, `Vkontakte`, `Wechat`, `Onlyfans` are keys in the client's `Logo` and `Image` switches and in the image filename derivation. Case-insensitive matching is used only server-side, for reorder and dedup.

---

## `linkspreview` — the preview carousel

Built by `SearchController.giveMeArrayOfLinks` (find it by name; it was declared at `:952` as of 2026-10-18 A), the same function the 20 platform cards go through, so an entry's shape is **identical to a platform entry's**: exactly these **four keys** — `title`, `url`, `image`, `timeago` (projected by its four `linkPreview.put` calls, `:980-983` as of 2026-10-18 A). Nothing else: no `publishedat`, no `type`, no `id`. **Object key order is not guaranteed** and must not be relied on: `net.minidev.json.JSONObject` extends `HashMap`, so these serialise in hash order (observed: `image, timeago, title, url`) — for platform cards too, since it is the same function. Read by name. Values are strings, with one caveat: the projection uses `getAsString`, so an upstream feed item with no title would yield `"title": null`. Not reachable from feeds `articlesOf` writes, which always set one.

```json
{ "title": "…", "url": "https://publisher.example/a",
  "image": "https://publisher.example/og.jpg", "timeago": "3 hours ago" }
```

**A non-empty `linkspreview` is what selects the card's render branch.** That is a contract-level fact now, not a frontend detail: at `Item.js:379` and `:423` a non-empty array renders the Swiper image carousel plus the "See More" button, and an empty one falls through to the text article list (or "Updating…" when `news` is empty too). So this array's contents — which move with no user action — decide which of two visibly different cards the user sees. Intended. (Only in `SocialMediaNewsPreview`; `SocialMediaNews` never reads `linkspreview`, and which of the two renders is `previewswitch`, `SocialMediaListRender.js:485`.)

The **shape** matches a platform's. The **dynamics** do not:

- **A theme's array is recomputed on every read**, from the already-populated `article_image` cache — no new fetch, no proxy spend. A platform's is a snapshot taken at fetch time.
- **A platform card that cannot refresh serves its last good fetch** — settled at gate, 2026-10-18 A. When `articlesOf` cannot refresh a platform (no proxy, all proxies inactive, unreachable URL, empty RSS, unreadable RSS), or `articlesOfAllSocialMedias` catches a throw from it (`fallbackCard`), the card is built by `SearchController.savedCard` from that platform's stored `news_block` row. Its `news` is the saved feed. Its `linkspreview` is the stored `news_block.linkspreview` column, filtered (`savedLinksPreviewOf`) to entries whose `url` is in that saved `news`. Its `last_update` is the row's stored `updated`. So a degraded platform card's `last_update` is the time of its last successful fetch, not now. If the row's feed is missing or unreadable, `news` and `linkspreview` are both `[]`, but `last_update` is still the stored `updated` when the row has one. `last_update` is now only when there is no row (none yet, or a lookup that threw) or the row has no `updated`. A row saved before the column existed (`linkspreview` NULL) serves its saved `news` with `linkspreview: []`. Separately, `saveNews` does not overwrite the property-1 row that `GET /service/news` serves when no card in the run has a non-empty `news`, and does not overwrite property 2 when the run's all-socials list is empty. So a refresh where every card is empty is not stored, and the previous cards keep being served. The user's words: "it can give the empty card, but it could also return what was previously fetched from that card"; "add the column, so that we can store preview images per platform"; "Keep the last good refresh". The wire shape is unchanged: 7 keys per platform card. Theme cards are not affected: `UserNewsContainerService` recomputes theirs on every read and ignores the stored column.
- **A theme's array can never dangle.** It is derived from the same feed that ships as `news` in the same response, so every entry's `url` matches a `news` entry — exactly what `Item.js:383-384` relies on to merge `publishedat` in for `NewsSaveButton`. As of 2026-10-18 A a platform card's array cannot dangle either, for the cards `articlesOf` and `fallbackCard` build and `GET /service/news` serves whole from the property-1 row. On a fresh refresh the previews are computed from a prefix of the same `newsArrayJson` that ships as `news`, and both are saved in one `newsBlockService.save`. On every degraded branch `savedCard` keeps only stored previews whose `url` is in the saved `news` it serves. *Backend builder: derive it from the same parsed feed object you put into `news`, not from a second read of the block, or this stops being true.*
- **It can be shorter than the platform equivalent, and it can be empty.** Only articles that were scanned **and** yielded a reachable image appear; an article whose `url` is missing, blank or still a Google News redirect is skipped (`giveMeArrayOfLinks`'s `realUrl` null / blank / `isGoogleNewsUrl` check, `:959-962` as of 2026-10-18 A), as on the platform path. On a cold cache the array is `[]` — a legitimate state, not an error.
- **Its length is bounded by the scan, not by the feed.** A theme's own fetch scans only the first `InfoMint.NUMBER_OF_PREVIEW_IMAGES_FOR_ENGLISH` (20, English) / `NUMBER_OF_PREVIEW_IMAGES` (11, every other language) articles of its feed (`SearchController.articlesOf`'s `numPreviewImages` selection, `:849-853` as of 2026-10-18 A), so an entry past that point appears only if another feed happened to scan the same article. Never assume a count.
- **The 72h blink.** `CleanupService` purges `article_image` rows whose `checked` is older than 72h and that are not behind a still-pinned article, and a cache **hit** does not refresh `checked` (`ArticleImageCache:128-142`). An article that sits in a theme's feed for more than 72h therefore loses its preview at the purge and regains it after the next fetch re-scans it: a card can flip between carousel and text list across reads. Platform cards share this property. Deliberately not mitigated this cycle.

---

## Wire details that bite if assumed otherwise

- **`position` is presentation order, never an identifier.** It is the dense index of the **language-filtered** list, so the same stored row reports different positions in different languages (stored 22, wire 20 in one verified run). Render in array order; do not sort by it, persist it, or key off it.
- **`last_update` moves between reads, with no user action.** `ThemeRefreshService` re-fetches stale themes (block older than an hour) on a 30-minute tick, **at most five per tick**, oldest-first with never-fetched first — so a theme's `news` array and `last_update` change over time as the 20 platform cards do, but on a best-effort cadence that degrades when more than five themes are stale. Do not treat a theme payload as immutable after create, do not cache one indefinitely, and do not key anything off `last_update`.
- **`last_update` is not ISO-8601.** It is Java's `Date.toString()` form — `"Sat Oct 10 17:06:51 CEST 2026"` — identical to what platform cards from `/service/news` already carry. Do not `new Date(...)`-parse it; render as-is or not at all. (It reaches that form because the backend normalises Hibernate's `java.sql.Timestamp` into a plain `java.util.Date`; json-smart would otherwise emit `{"nanos":N}`.)
- **Every create shifts every other row of that user — and for a theme with no `NewsBlock` yet, that moves its `last_update` forward with no fetch having happened.** The shift is **universal on the create path**, not occasional: the front-insert moves every other row of that user by +1, so `densify` stamps `updatedat` on each of them (`UserNewsContainerService.java:664-667`). Whether it reaches the wire is the conditional half. For a theme whose `NewsBlock` does not exist yet, `last_update` is `updatedat ?? createdat` (`:843-845`) — so creating card B **does** make card A report a newer `last_update`. Bounded on both sides: once a block exists, `last_update` is the block's own date (`:871`, `:874`) and the bump never reaches the wire, and `platformJson` carries no `last_update` at all (`:814-820`), so the 20 platform rows' bumps are invisible. **So: the stamp is universal, the wire effect is reachable only for a theme whose own create skipped or failed its fetch.** This is a change to a pinned field's *dynamics*, which § *Cross-repo work* item 5 treats as invalidating a spec just as a shape change would — hence its presence here rather than in a commit message.

---

## Logged-out behaviour

When `!authenticated`, **do not call `/mycontainers` at all** — there is no uuid to send — and render `/service/news` unchanged. That is byte-identical to today's behaviour, with zero extra requests on the anonymous hot path. Show the add/rearrange affordance (matching the Pin precedent) but have a click open the existing sign-in popup.

---

## Create latency

`POST /container` does a **live Google News fetch** for a theme nobody has created before: a proxy-liveness loop (up to 5s connect + 10s read each) plus an RSS fetch (10s + 15s). There is no server-side cap. A theme whose `NewsBlock` is younger than `onelike.containers.block-max-age-ms` (1h) is served from that shared block and returns fast — including a theme another user created. An **older** block is re-fetched live, so "this theme already exists" does not by itself mean "this create is fast".

- Client timeout **60 s** via `AbortController`.
- Show `DraggableLoadingPopup` (already imported in `SocialMediaListRender.js`, exported from `SpaceItem.js`) and disable the create button while in flight.
- **On abort or network failure, treat the outcome as unknown, not failed** — the row may well be committed. Say "this is taking longer than expected; your card may still appear", then re-fetch `/mycontainers`. Do not auto-retry: it is idempotent so a retry is safe, but it costs another minute.
- `200` with `news: []` is a **legitimate success** (all proxies down). Add the card; optionally show "no articles yet".

---

## Known backend behaviour — not bugs

- **`linkspreview` is live for theme cards** as of the 2026-10-11 cycle — it is no longer always `[]`. See its own section above for the entry shape and the dynamics. The frontend's existing `length !== 0` guards (`Item.js:379`, `:423`) are precisely what makes that a backend-only release; keep them.
- **A create can answer with an empty or stale theme even when the proxies are healthy.** `loadThemeContent` takes an in-flight guard keyed on title + language (`UserNewsContainerService.java:388`), so a create that races the refresh tick skips the fetch and returns whatever the block currently holds — possibly nothing. `news: []` remains a legitimate success.
- **A reorder in one language pushes the other languages' themes to the end of their own view**, and platform order is global across all languages. Verified lossless — nothing is ever deleted. Per-language ordering is a deliberate phase-2 candidate.

---

## Deploy ordering

Answered in both directions, per `AGENTIC-LOOP.md` § *Cross-repo work* item 6, each against the other side's **deployed** code.

**Backend first: safe, and for a stronger reason than "the client is not blocked".** There is no deployed consumer of these four endpoints to regress — see the header for the `git ls-tree` and `merge-base` measurements. Even against the unreleased client it would be safe: that client appends the created container locally (`usercontainer.reducer.js`, `upsertContainer` → `return [...list, container]`) and reads no `position` from any container (`grep -rn "\.position\b\|position:" src --include=*.js` matches only CSS values and toast props — zero API reads), so `position` changing 20 → 0 is inert and the post-create view is identical to today.

**Frontend first: visibly worse, and the asymmetric case.** A client that prepends locally against the current backend shows the new card at the front and then watches it **jump to the end** on the next `/mycontainers` — `Home.js`'s 20-minute tick and `Switch.js`'s "Load News" both re-fetch, so the jump is not hypothetical and needs no user action. The client-side fix in this cycle is a `resync()` on the create-success path, which against the current backend re-reads an append and so merely confirms today's behaviour: harmless, but it buys nothing until the backend lands.

**Therefore: backend first.** The client change is a no-op improvement before the backend ships and the whole point of the cycle after.

---

## File pointers

From a read of the frontend during contract work. **Re-locate by symbol; line numbers may have shifted.**

- `src/components/SocialMediaListRender.js` — the containers/news merge (`mergedSocialmedias`), the `data=` prop passed to `CustomPagination`, the `getMyContainers` fetch effect, the `previewswitch` choice of render component
- `src/components/Item.js` — `SocialMediaNews`, `SocialMediaNewsPreview`, the `linkspreview` carousel branch and its `news`-by-`url` merge, `Logo` / `Image`, `NewsSaveButton.handlePin`
- `src/components/CardConfigPopup.js` — create / delete / reorder UI, `toRows` (what keeps `news[]` and `linkspreview[]` out of the reorder payload), the server-echo reseed guard
- `src/services/usercontainer.service.js`, `src/actions/usercontainer.actions.js`, `src/reducers/usercontainer.reducer.js` — the implemented trio, modelled on `newspin.*`
- `src/components/Home.js` — the 20-minute tick that re-fetches `/mycontainers`; `src/components/Switch.js` — the "Load News" button that does the same
- `src/helpers/auth-header.js` — `authHeaderWithJSON()`
- `src/components/SpaceItem.js` — where `DraggableLoadingPopup` is exported from

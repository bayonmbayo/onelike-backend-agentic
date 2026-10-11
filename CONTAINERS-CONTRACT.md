# User news containers — wire contract (phase 1)

The contract the backend implements and the `onelike-agentic` frontend builds against, for custom themed cards in Social News.

Backend phase 1 is **built, verified and committed** (`c69c7e7`). All four endpoints work live and the dev schema is correct. Nothing on the backend blocks the frontend.

Every field name below was verified against the real code in `controller/UserNewsContainerController.java`, `service/UserNewsContainerService.java` and `model/dto/Container*.java` — **not** relayed from the planning-stage spec, which predates the language-scoping change. Where this file and `bridge`'s original spec disagree, this file is right.

Frontend work runs that repo's own six-stage loop: start at its `planner`, apply its confirmation gate, then `builder`.

---

## Base URL

Always the **unsuffixed** `process.env.REACT_APP_ONELIKE_BACKEND_API` (`https://api.onelike.app/service`), even in a non-English build. The per-language variables (`_DE`, `_FR`, …) point at `/dienst`, `/servicefr` … which are different controllers that do **not** have these endpoints. Mirror `newspin.service.js`; do not copy `socialmedia.service.js`'s 15-language-copies pattern here.

All mutations are POST — this repo has no PUT or DELETE anywhere.

---

## Endpoints

### `GET /mycontainers?uuid={uuid}&language={language}`

Both params optional. Returns `200` always — never 404, never an error for an unknown user.

```json
{ "seeded": true,
  "containers": [
    { "kind": "PLATFORM", "name": "Twitter", "position": 0 },
    { "kind": "THEME", "name": "AI", "position": 20, "id": 42,
      "news": [ ], "last_update": "Sat Oct 10 17:06:51 CEST 2026",
      "description": "", "website": null, "linkspreview": [] }
  ] }
```

PLATFORM entries carry **only** `kind`, `name`, `position`. THEME entries add `id`, `news`, `last_update`, `description`, `website`, `linkspreview`.

Missing, blank or unknown `uuid`, or a user with no rows → `seeded: false` plus the 20 built-in platforms at positions 0-19. `seeded` means "has this user ever customised anything" and is deliberately **not** per-language.

### `POST /container`

```json
{ "uuid": "", "firstname": "", "name": "", "email": "",
  "displayname": "", "title": "AI", "language": "English" }
```

`name` is the **caller's surname** (the same identity block as `PinDTO`). The container's own title is `title`.

Returns `{ "created": true, "container": { } }`.

A duplicate title for the same user *and language* returns `200` with `created: false` and the existing container — idempotent by design, not an error, so a double-submitting popup is safe.

### `POST /container/delete`

```json
{ "uuid": "", "firstname": "", "name": "", "email": "", "displayname": "", "id": 42 }
```

Returns `{ "deleted": true }`. THEME rows only; a PLATFORM id returns 404.

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
- The same title can exist **once per language**, so "Crypto"/English and "Crypto"/Deutsch are two separate cards, each consuming one of the user's ten.
- **The ten-theme cap is global across all languages.** Five English plus five German means the cap is reached; an eleventh in either language returns 409.

---

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
| 409 | `container.limit.reached` | inline notice naming the limit of ten; popup stays open |
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

Three things to add for `kind === "THEME"`:

1. **Destructure `kind`** in `SocialMediaNews` and `SocialMediaNewsPreview` — the components this feature calls "SocialMediaCard". There is no file by that name.
2. **Icon fallback.** `Logo` is a `switch (name)` whose `default` returns `<></>`, so a theme renders no icon. Add an explicit THEME branch with a generic icon. **Do not** extend the `name` switch — a theme called "AI" must never accidentally match a platform case.
3. **No background image for THEME.** `Image` builds `/<name.toLowerCase()>-image-<theme>.png`, so a theme would request `/ai-image-dark.png` and 404. Return early for THEME before the path is constructed, or use a gradient placeholder.

Also: `website` is `null` for themes, so prefer skipping the `<a>` wrapper over rendering a non-clickable link; `description` is `""`.

### The merge with `/service/news`

`/service/news` still provides the 20 built-ins' **content**; `/mycontainers` provides **order and membership**. Merge in `SocialMediaListRender.js` (shared by all 16 language wrappers) and feed the result to `CustomPagination`.

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

## Two wire details that bite if assumed otherwise

- **`position` is presentation order, never an identifier.** It is the dense index of the **language-filtered** list, so the same stored row reports different positions in different languages (stored 22, wire 20 in one verified run). Render in array order; do not sort by it, persist it, or key off it.
- **`last_update` moves between reads, with no user action.** A scheduled job re-fetches each live theme's news on a 30-minute cadence (anything older than an hour), so a theme's `news` array and `last_update` change over time exactly as the 20 platform cards do. Do not treat a theme payload as immutable after create, do not cache one indefinitely, and do not key anything off `last_update`.
- **`last_update` is not ISO-8601.** It is Java's `Date.toString()` form — `"Sat Oct 10 17:06:51 CEST 2026"` — identical to what platform cards from `/service/news` already carry. Do not `new Date(...)`-parse it; render as-is or not at all. (It reaches that form because the backend normalises Hibernate's `java.sql.Timestamp` into a plain `java.util.Date`; json-smart would otherwise emit `{"nanos":N}`.)

---

## Logged-out behaviour

When `!authenticated`, **do not call `/mycontainers` at all** — there is no uuid to send — and render `/service/news` unchanged. That is byte-identical to today's behaviour, with zero extra requests on the anonymous hot path. Show the add/rearrange affordance (matching the Pin precedent) but have a click open the existing sign-in popup.

---

## Create latency

`POST /container` does a **live Google News fetch** for a theme nobody has created before: a proxy-liveness loop (up to 5s connect + 10s read each) plus an RSS fetch (10s + 15s). There is no server-side cap. A theme another user already created is served from the shared cache and returns fast.

- Client timeout **60 s** via `AbortController`.
- Show `DraggableLoadingPopup` (already imported in `SocialMediaListRender.js`, exported from `SpaceItem.js`) and disable the create button while in flight.
- **On abort or network failure, treat the outcome as unknown, not failed** — the row may well be committed. Say "this is taking longer than expected; your card may still appear", then re-fetch `/mycontainers`. Do not auto-retry: it is idempotent so a retry is safe, but it costs another minute.
- `200` with `news: []` is a **legitimate success** (all proxies down). Add the card; optionally show "no articles yet".

---

## Known backend behaviour — not bugs

- **`linkspreview` is always `[]`** for theme cards in phase 1. Render it defensively (the existing code already guards on `length !== 0`). Staying defensive means a future backend change needs no second contract pass.
- **Themes do not refresh yet.** A theme's articles are frozen at creation time. That is the next backend cycle and needs no frontend change.
- **A reorder in one language pushes the other languages' themes to the end of their own view**, and platform order is global across all languages. Verified lossless — nothing is ever deleted. Per-language ordering is a deliberate phase-2 candidate.

---

## File pointers

From a read of the frontend during contract work. **Re-locate by symbol; line numbers may have shifted.**

- `src/components/SocialMediaListRender.js` — the `data=` prop passed to `CustomPagination`, the `getAllNews` batch dispatch, the `lin` / `u` / `authenticated` selectors, the existing `DraggableLoadingPopup` import
- `src/components/Item.js` — `SocialMediaNews`, `SocialMediaNewsPreview`, the `Logo` and `Image` switches and their call sites
- `src/services/newspin.service.js`, `src/actions/newspin.actions.js`, `src/reducers/newspin.reducer.js` — the pattern to mirror for a new `usercontainer.*` trio
- `src/helpers/auth-header.js` — `authHeaderWithJSON()`
- `src/components/SpaceItem.js` — where `DraggableLoadingPopup` is exported from

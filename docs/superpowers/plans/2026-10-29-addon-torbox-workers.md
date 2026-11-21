# TorBox Addon on Cloudflare Workers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Cloudflare Worker that answers BitChord's addon protocol from a TorBox account, with the lowest resolution latency the free Workers plan allows.

**Architecture:** `/search` is a single D1 query against an FTS5 index — no subrequests, no parsing, no scans. `/stream` answers `{"url","format"}` from the Workers Cache API; on a miss it mints one from TorBox once and caches the answer for 4 minutes. The index is built by a cron trigger that walks `mylist?offset&limit` and inserts only rows it hasn't seen.

**Tech Stack:** TypeScript on Workers (`workerd`), D1 + FTS5, Workers Cache API, Cron Triggers, `wrangler`, `bun test` for pure helpers.

**Spec:** Protocol is fixed by `app/src/main/java/com/music/bitchord/data/sources/addon/AddonModels.kt` and `AddonClient.kt` (the wire shapes, required `token` query param, 404-rules). Design decisions not in code live in this plan.

## Global Constraints

- **10 ms CPU per invocation** (Workers free). No request path may parse more than ~100 KB.
- **5M D1 rows read/day, 100k written/day.** `/search` must not full-scan; sync must not rewrite.
- **`token` is a required query param** on `requestdl`, not just the Bearer header — header-only returns 422.
- **The CDN link's `token` is the account API key.** Never log a minted URL; never log `env.TORBOX_KEY`.
- **The app reuses a `/stream` URL for 5 min** (`STREAM_TTL_MS`). Cache TTL must be under that.
- **The app races this addon against YouTube with a 20 s budget.** `/search` + `/stream` together must stay well under it.
- **No runtime dependencies.** Dev deps only: `wrangler`, `typescript`, `@cloudflare/workers-types`.
- **`TORBOX_KEY` never in the repo.** `.dev.vars` locally, `wrangler secret put` deployed.
- Protocol limit: the addon has no `limit` parameter — return a fixed 10 rows.
- **`addon/` is its own git repo** (initialised with `git init -b main`), outside the main repo's index. Every commit step runs as `git -C addon …`. Never run `git add addon` from the repository root — that stages a gitlink, not the files.

## Review Focus

- **FTS5 syntax in user input.** A query containing `"`, `*`, `-`, `(`, `NEAR` must not 500 — it must return results or an empty list.
- **Non-audio files in an album.** `album.nfo`, `.jpg`, `.cue` must never reach `/search` results.
- **`mylist` pagination edges.** Short final page, `offset` past the end, and account growth mid-walk must not stall or loop the cursor.
- **TorBox outage.** 5xx or timeout on `requestdl` must return 502 (app retries later), not 404 (app refuses the source for 10 min). Specified in Task 5 Step 1; no local fault injection, so it is verified by inspection only.
- **Missing/rotated `TORBOX_KEY`** must fail loudly (500 with a message) rather than returning an empty catalogue that looks like "artist not found".

---

### Task 1: Scaffold, routes, secret gate

**Files:**
- Create: `addon/package.json`, `addon/tsconfig.json`, `addon/wrangler.toml`, `addon/.gitignore`, `addon/.dev.vars.example`, `addon/src/index.ts`, `addon/src/types.ts`

**Interfaces:**
- Produces: `Env { DB: D1Database; TORBOX_KEY: string; PATH_SECRET: string }` in `src/types.ts`; `export default { fetch, scheduled }` from `src/index.ts`.

- [ ] **Step 1: Write `addon/src/index.ts` with the router and no handlers**

Route table after stripping `/${env.PATH_SECRET}`: `GET /manifest.json`, `GET /search`, `GET /stream/<id>`. Anything else → 404. A request whose path does not start with `/${env.PATH_SECRET}` → 404 (never 403 — do not confirm the prefix exists). Also write `addon/.gitignore` with `.wrangler/`, `.dev.vars`, `node_modules/`, `seed.sql`.

- [ ] **Step 2: Run it and verify the gate**

Run: `cd addon && bunx wrangler dev`
Expected: `curl -s -o /dev/null -w '%{http_code}' localhost:8787/search?q=x` → `404`; `curl -s localhost:8787/dev/manifest.json` → the manifest JSON (use `dev` as `PATH_SECRET` in `.dev.vars`).

- [ ] **Step 3: Commit**

```bash
git -C addon add -A
git -C addon commit -m "feat: scaffold worker, routes and path gate"
```

---

### Task 2: Pure helpers for filename parsing and query building

**Files:**
- Create: `addon/src/tracks.ts`, `addon/test/tracks.test.ts`

**Interfaces:**
- Produces:
  - `parseTitle(shortName: string): string` — `"01 - ELEVEN.flac"` → `"ELEVEN"`
  - `parseArtist(torrentName: string): string` — `"IVE - ELEVEN (2021) [FLAC 16bit-44.1kHz]"` → `"IVE"`
  - `extensionOf(name: string): string` — `"a.FLAC"` → `"flac"`
  - `isAudio(shortName: string, mimetype: string | undefined): boolean`
  - `buildMatchQuery(q: string): string | null` — `"ive eleven"` → `"ive"* OR "eleven"*`; `null` when no token of length ≥ 2 remains

- [ ] **Step 1: Write the failing tests**

```ts
import { expect, test } from "bun:test";
import { parseTitle, parseArtist, extensionOf, isAudio, buildMatchQuery } from "../src/tracks";

test("strips track number and extension", () => {
  expect(parseTitle("01 - ELEVEN.flac")).toBe("ELEVEN");
  expect(parseTitle("07. Take It.mp3")).toBe("Take It");
  expect(parseTitle("1-02 Song_Name.m4a")).toBe("Song Name");
});
test("artist is the torrent name before the first dash", () => {
  expect(parseArtist("IVE - ELEVEN (2021) [FLAC 16bit-44.1kHz]")).toBe("IVE");
  expect(parseArtist("No Dash Album")).toBe("No Dash Album");
});
test("extension is lowercased", () => { expect(extensionOf("a.FLAC")).toBe("flac"); });
test("only audio files count, regardless of extension", () => {
  expect(isAudio("album.nfo", undefined)).toBe(false);
  expect(isAudio("cover.jpg", "image/jpeg")).toBe(false);
  expect(isAudio("01 - ELEVEN.flac", "application/octet-stream")).toBe(true);
  expect(isAudio("01 - ELEVEN", "audio/flac")).toBe(true);
});
test("query is quoted and prefix-matched per token", () => {
  expect(buildMatchQuery("ive eleven")).toBe('"ive"* OR "eleven"*');
  expect(buildMatchQuery('  a  ')).toBeNull();
});
test("quotes in input cannot break out of a token", () => {
  expect(buildMatchQuery('foo"bar')).toBe('"foo""bar"*');
});
test("fts operators in input are inert", () => {
  expect(buildMatchQuery("NEAR( a * )")).toBe('"near"*'); // single-char tokens are dropped
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd addon && bun test`
Expected: FAIL — `Cannot find module '../src/tracks'`.

- [ ] **Step 3: Implement `addon/src/tracks.ts`**

Tokenise on whitespace, lowercase, drop tokens shorter than 2 chars, escape `"` by doubling, wrap as `"token"*`, join with ` OR `. `isAudio` accepts when `mimetype` starts with `audio/` **or** `extensionOf` is in `{flac, mp3, m4a, aac, opus, ogg, wav, dsf, dff, alac}`.

- [ ] **Step 4: Run to verify it passes**

Run: `cd addon && bun test`
Expected: PASS, 7/7.

- [ ] **Step 5: Commit**

```bash
git -C addon add -A
git -C addon commit -m "feat: filename parsing and fts query building"
```

---

### Task 3: Schema, local D1, seed script

**Files:**
- Create: `addon/schema.sql`, `addon/scripts/seed.ts`

**Interfaces:**
- Produces: table `tracks(id INTEGER PRIMARY KEY, torrent_id, file_id, title, artist, album, size, format)` with `UNIQUE(torrent_id, file_id)`; FTS5 table `tracks_fts` over `title, artist` kept current by AFTER INSERT trigger; table `sync_state(id=1, cursor)`.

- [ ] **Step 1: Write `addon/schema.sql`**

`tracks` as above; `tracks_fts` as `fts5(title, artist, content='tracks', content_rowid='id', tokenize='unicode61 remove_diacritics 2')`; trigger `tracks_ai AFTER INSERT ON tracks` inserting `(new.rowid, new.title, new.artist)` — **`new.rowid`, not `new.id`**: D1 rejects `new.id` with `no such column`; `INSERT OR IGNORE INTO sync_state VALUES (1, 0)`.

- [ ] **Step 2: Apply it locally and verify the tables exist**

Run: `cd addon && bunx wrangler d1 execute bitchord-addon --local --file=schema.sql && bunx wrangler d1 execute bitchord-addon --local --command "SELECT name FROM sqlite_master WHERE type IN ('table','trigger')"`
Expected: `tracks`, `tracks_fts`, its shadow tables, `sync_state`, `tracks_ai`.

- [ ] **Step 3: Write `addon/scripts/seed.ts`**

Reads `process.env.TORBOX_KEY`, pages `mylist?limit=100&offset=N` until a short page, applies `isAudio`/`parseTitle`/`parseArtist`, and writes `seed.sql` containing `INSERT INTO tracks(...) SELECT ... WHERE NOT EXISTS (...)` statements (one per track, batched into `BEGIN; … COMMIT;`). No network calls beyond TorBox; prints counts only, never URLs.

- [ ] **Step 4: Seed locally and verify a row**

Run: `cd addon && TORBOX_KEY=$TORBOX_KEY bun run scripts/seed.ts && bunx wrangler d1 execute bitchord-addon --local --file=seed.sql && bunx wrangler d1 execute bitchord-addon --local --command "SELECT count(*) FROM tracks"`
Expected: count > 5000; `SELECT count(*) FROM tracks_fts` equal.

- [ ] **Step 5: Commit**

```bash
git -C addon add -A
git -C addon commit -m "feat: d1 schema with fts index and seed script"
```

---

### Task 4: `/search`

**Files:**
- Modify: `addon/src/index.ts`

**Interfaces:**
- Consumes: `buildMatchQuery` (Task 2), `tracks`/`tracks_fts` (Task 3).
- Produces: `GET /search?q=` → `{"tracks":[{"id":"<tid>:<fid>","title","artist","album","format"}]}`.

- [ ] **Step 1: Write the handler**

Blank `q`, or `buildMatchQuery(q) === null` → `{"tracks":[]}` with `Cache-Control: no-store`, before touching D1. Otherwise one prepared statement joining `tracks_fts` to `tracks` on `rowid`, `WHERE tracks_fts MATCH ?1 ORDER BY rank LIMIT 10`, `.bind(query).all()`. Map each row to the wire shape; `id` is `` `${torrent_id}:${file_id}` ``. A D1 error is a **500**, never an empty list — an empty list reads as "artist not found" and sends the user looking for the wrong problem.

- [ ] **Step 2: Verify against the local DB**

Run: `cd addon && bunx wrangler dev` then
`curl -s "localhost:8787/dev/search?q=eleven" | head -c 300`
`curl -s "localhost:8787/dev/search?q=%22%2A%28" | head -c 120`
Expected: first returns rows with `"id":"130633950:2"`-shaped ids; second returns `{"tracks":[]}` and status 200, never 500.

- [ ] **Step 3: Verify no album.nfo leaks**

Run: `curl -s "localhost:8787/dev/search?q=eleven" | grep -ci nfo`
Expected: `0`.

- [ ] **Step 4: Measure it**

Run: `for i in 1 2 3 4 5; do curl -s -o /dev/null -w '%{time_total}\n' "localhost:8787/dev/search?q=eleven"; done`
Expected: all under 0.060 s locally (local D1 is faster than remote; this only proves the request does no extra work).

- [ ] **Step 5: Commit**

```bash
git -C addon add -A
git -C addon commit -m "feat: fts search endpoint"
```

---

### Task 5: `/stream` with cache

**Files:**
- Modify: `addon/src/index.ts`

**Interfaces:**
- Produces: `GET /stream/<torrentId>:<fileId>` → `200 {"url","format"}`, or 404/502.

- [ ] **Step 1: Write the handler**

Parse the id; malformed → 404. If `env.TORBOX_KEY` is blank → 500 with `TORBOX_KEY is not set`, before any fetch — a rotated or missing secret must not read as "TorBox has no such file". Build `const key = new Request("https://addon.internal/stream/" + id)` and `await caches.default.match(key)`; on a hit, return the cached JSON body. On a miss, `fetch` `requestdl` with `token`, `torrent_id`, `file_id`, `redirect=false`, `Authorization: Bearer`, and `redirect: "manual"` so a 3xx is never chased; 404 → 404; any other non-2xx → 502; `success !== true` → 404. Look up `format` from `tracks` for the id, `caches.default.put(key, <json>)` via `ctx.waitUntil`, and return `{"url","format"}`. Never log the URL or the key.

**Never return a redirect.** `AddonClient.body()` only parses a 2xx as JSON and special-cases 404/429/5xx; OkHttp follows redirects, so a 302 is chased to the CDN and the entire audio file is read as a String before the JSON decode fails. The URL is for the *player* to fetch, not for the client.

- [ ] **Step 2: Verify the JSON answer locally**

Run: `curl -s "localhost:8787/dev/stream/130633950:2" | sed -E 's#"url":"https://([^/]+)/[^"]*"#"url":"https://\1/…"#'`
Expected: `{"url":"https://nexus.…","format":"flac"}` — redacted so the key-bearing URL is never printed.

- [ ] **Step 3: Verify a bad id does not call TorBox, and a blank key fails loudly**

Run: `curl -s -o /dev/null -w '%{http_code}\n' "localhost:8787/dev/stream/nope"`
Then blank `TORBOX_KEY` in `.dev.vars`, restart, and `curl -s -o /dev/null -w '%{http_code}\n' "localhost:8787/dev/stream/130633950:2"`.
Expected: `404` for the bad id; `500` for the blank key. Restore the key afterwards.

- [ ] **Step 4: Verify the warm path is subrequest-free**

Run twice; the second must not appear in `wrangler dev` output after the first's `fetch` line.
Expected: second `curl -w '%{time_total}'` under 0.010 s locally.

- [ ] **Step 5: Commit**

```bash
git -C addon add -A
git -C addon commit -m "feat: stream endpoint with edge cache"
```

---

### Task 6: Cron sync

**Files:**
- Modify: `addon/src/index.ts`, `addon/wrangler.toml`

**Interfaces:**
- Consumes: `parseTitle`/`parseArtist`/`isAudio` (Task 2), `tracks`/`sync_state` (Task 3).
- Produces: `scheduled()` walks one page per tick.

- [ ] **Step 1: Add the trigger to `wrangler.toml`**

`[triggers] crons = ["*/2 * * * *"]`.

- [ ] **Step 2: Write `scheduled()`**

Read `cursor` and the last page size from `sync_state`; `fetch` `mylist?limit=25&offset=<cursor>`. If the page is empty **and** `cursor !== 0`, reset cursor to 0 and return. Insert audio files with the `WHERE NOT EXISTS` form in one `DB.batch`; advance cursor by the page size; if the page was short, set cursor to 0. Catch and log failures without advancing the cursor.

- [ ] **Step 3: Verify one tick locally**

Run: `cd addon && bunx wrangler dev --test-scheduled` then
`curl -s "localhost:8787/__scheduled?cron=*"` and
`bunx wrangler d1 execute bitchord-addon --local --command "SELECT * FROM sync_state"`
Expected: cursor advanced by 25; a second identical tick advances again; replaying a tick writes 0 new rows.

- [ ] **Step 4: Verify the wrap**

Run: `bunx wrangler d1 execute bitchord-addon --local --command "UPDATE sync_state SET cursor=880"` then trigger a tick.
Expected: cursor returns to a small number, not 880+25.

- [ ] **Step 5: Commit**

```bash
git -C addon add -A
git -C addon commit -m "feat: cron sync of torbox library"
```

---

### Task 7: Deploy and measure

**Files:** none.

- [ ] **Step 1: Create the remote DB and apply the schema**

Run: `cd addon && bunx wrangler d1 create bitchord-addon` then copy the `database_id` into `wrangler.toml`, then `bunx wrangler d1 execute bitchord-addon --remote --file=schema.sql`.

- [ ] **Step 2: Set the secret, deploy, seed**

Run: `bunx wrangler login`, `bunx wrangler secret put TORBOX_KEY`, `bunx wrangler secret put PATH_SECRET`, `bunx wrangler deploy`, then regenerate `seed.sql` and apply with `--remote`.

- [ ] **Step 3: Measure from India**

Run, 10 iterations each, against the deployed URL with a real `PATH_SECRET`:
`/search?q=eleven` → expect p50 < 100 ms.
`/stream/<id>` twice → expect first < 450 ms, second < 60 ms.
Expected: thresholds met; if `/search` p50 > 100 ms, record the D1 location and recreate the database in the closest region.

- [ ] **Step 4: End-to-end in the app**

Add `https://<worker>.workers.dev/<PATH_SECRET>` in Sources with **Prefetch addon sources** on; play three tracks.
Expected: Stats for Nerds shows the TorBox source and a `complete` prefetch; logcat shows no `Bitchord` error for the addon.

- [ ] **Step 5: Commit the real database id**

```bash
git -C addon add wrangler.toml
git -C addon commit -m "chore: point the d1 binding at the deployed database"
```

---

## Out of scope

- Design B (paid plan, in-memory index) — kept as the escape hatch if D1 latency disappoints.
- `/search` result caching — the app already caches searches for 10 minutes.
- Atmos/userset/zip links, `webdl`/`usenet` sources, multi-account support.

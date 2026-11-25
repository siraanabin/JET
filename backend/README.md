# JET Diagnostics Backend

Cloudflare Worker + D1 backend for **opt-in** diagnostic telemetry. This directory is independent of the Android app. This change does not enable telemetry in the app and does not alter the download engine or Settings UI.

## Privacy and security model

- The app sends a random install ID in `X-Install-ID`. The Worker stores only its SHA-256 hash.
- The app sends `Authorization: Bearer <INGEST_API_KEY>` to ingest, bug-report, top-link, and self-delete endpoints.
- **Important: `INGEST_API_KEY` is a shared client key. It can be extracted from an APK. It is not a durable secret and must not be treated as strong authentication.** Protection relies mainly on daily global limits, the per-install event/submission limits, server-side validation, and abuse monitoring. A modified client can still impersonate installs or exhaust shared quotas.
- `ADMIN_API_KEY` is a separate Cloudflare secret and is used only for the dashboard summary endpoint. Never put it in the APK.
- Neither key is committed to this repository. Do not add `.dev.vars`, `.env`, or secret values to Git.
- The Worker does not store request IP addresses, email, phone, location, IMEI, Android ID, file names/paths, video titles, or full URLs.
- `video_id` is accepted only by `/v1/top-link`, and only when the client has a separate video-ID opt-in. It is never stored in `events` or `daily_summary`.
- `init_error` and bug-report descriptions are stripped of URL/path-like fragments and truncated to 500 characters. This is best-effort text sanitization, not a guarantee that arbitrary text cannot contain personal information; clients should avoid sending sensitive content.
- `/v1/me/data` deletes that install's `events`, `bug_reports`, `top_links`, and `daily_counters`. It intentionally does **not** modify `daily_summary`; aggregate counts may therefore continue to include deleted events.

## Limits

All day-based limits reset at **00:00 UTC**.

| Limit | Value |
|---|---:|
| Total events per UTC day | 8,000 |
| Failure + fallback events per UTC day | 1,500 reserved cap |
| Events per install ID per UTC day (`USER_EVENT_LIMIT`) | 60 |
| Success sampling | First 5,000 success observations unsampled; then 1 in 10 |
| Bug reports + top links combined per install ID per UTC day | 10 |
| Daily write kill switch | 60,000 reserved write units |
| Retention | 30 days |
| Maximum ingest batch | 15 events |

Failure/fallback events use a separate counter so success traffic cannot consume their 1,500-event allowance. Successful events after the first 5,000 observations are sampled at one in ten. An event rejected by the per-install or total quota is not stored.

`write_units` is a conservative admission-control estimate, not Cloudflare's exact billing meter. It reserves five units per event and six per bug/top-link submission, including the event summary and counter updates. Retention deletes are also budget-checked against the same 60,000-unit kill switch; a cleanup batch skips any table deletion that would cross the limit. The event table intentionally has only a day index, not an install index, to keep per-event index writes lower; `/v1/me/data` may therefore scan the retained event table (at most about 240,000 events at the stated daily cap). Compare actual D1 usage in the Cloudflare dashboard.

### Estimated D1 row writes per operation

These counts include the affected table row and its maintained indexes:

| Operation | Estimated D1 rows written |
|---|---:|
| Accepted event: `events` row + day index | 2 |
| One `daily_summary` upsert | 1 |
| Global + install counter updates | 2 |
| **Accepted event total** | **5** |
| Bug report or top link: table row + two indexes | 3 |
| Its global + install counter updates | 2 |
| **Bug/top-link submission** | **5** (reserves 6) |
| Event retention delete | 2 |
| Bug/top-link retention delete | 3 |
| Summary/counter retention delete | 1 |

A newly seen install adds one counter-row insert and one global-counter update (2 additional writes); the first global counter row of a UTC day adds one. The 60,000 kill switch uses conservative reservations, so actual D1 billing counters should still be checked in Cloudflare analytics.

## Ingest batch and Cloudflare invocation limits

`POST /v1/ingest` accepts at most **15 events**. The request constructs 48 prepared SQL statements in one `DB.batch()` call: three counter setup/accounting statements and three statements per event. Cloudflare documents that a D1 `db.batch()` counts as **one query/subrequest** against the per-Worker-invocation D1 query limit, even when the batch contains multiple statements. Therefore this request uses one D1 query subrequest, well below the 50-query limit.

Cloudflare Workers Free currently documents **10 ms CPU time per invocation**. CPU use depends on payload size, isolate state, crypto, parsing, and runtime conditions; a local unit test cannot prove that every production invocation stays below 10 ms. **10 ms CPU compliance: যাচাই করিনি.** Load-test on a deployed staging Worker before enabling app traffic. The Worker is not deployed by this repository change.

## D1 schema

Migration `0001_initial_schema.sql` creates:

- `events`: allowlisted event properties; no `video_id`.
- `daily_summary`: aggregate metrics only; no `video_id`.
- `bug_reports`: sanitized, max-500-character descriptions.
- `top_links`: the only table containing `video_id`.
- `daily_counters`: global and per-install daily counters.

Indexes support day-based retention and per-install deletion of bug reports/top links. The events table has only its day index; per-install event deletion scans retained rows to reduce per-event index writes. The dashboard endpoint reads only `daily_summary`, never raw `events`.

## API

All client endpoints require:
- `Authorization: Bearer <INGEST_API_KEY>`
- `X-Install-ID: <random-install-id>` (16–128 ASCII letters/digits/period/underscore/hyphen)

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/ingest` | Batch events; max 15 |
| POST | `/v1/bug-report` | One sanitized bug report |
| POST | `/v1/top-link` | One video ID; separate opt-in required |
| GET | `/v1/admin/daily-summary?day=YYYY-MM-DD` | Aggregate dashboard; requires `ADMIN_API_KEY` |
| DELETE | `/v1/me/data` | Delete the current install's records, except daily aggregates |

Quota responses use HTTP 429 and include `code`, `retryAfter`, and `stopUntilUtc`. The client should stop sending for that UTC day when `stopUntilUtc` is present. Partial ingest responses include `accepted`, `rejected`, and a stop code; do not retry rejected events repeatedly on the same day.

### Example event

```json
{
  "event_type": "failure",
  "engine": "yt-dlp",
  "fallbackReason": "native_init_failed",
  "initStep": "ffmpeg_runtime_initialize",
  "initError": "Initialization failed",
  "speed": 1.25,
  "durationMs": 42000,
  "format": "mp3",
  "resolution": "audio",
  "sizeBytes": 2457600,
  "retries": 1,
  "appVersion": "0.9.12",
  "androidVersion": "15",
  "deviceModel": "Example device",
  "abi": "arm64-v8a",
  "networkType": "wifi",
  "freeStorageBucket": "5-10GB"
}
```

Do not send a title, file path, full URL, or `video_id` in this object.

## Cloudflare Free plan limits (checked 2026-11-25)

The following are the current published limits from **developers.cloudflare.com**:

| Product / limit | Free allowance |
|---|---:|
| Workers requests | 100,000 per day |
| Workers CPU time | 10 ms per invocation |
| Workers subrequests | 50 per invocation |
| Workers Cron Triggers | 5 per account |
| D1 rows read | 5 million per day |
| D1 rows written | 100,000 per day |
| D1 maximum database size | 500 MB |
| D1 total account storage | 5 GB |
| D1 databases | 10 per account |
| D1 queries per Worker invocation | 50 |

D1 `db.batch()` counts as one query against the per-invocation D1 query limit. Each statement inside the batch remains subject to its individual SQL limits. Workers CPU time is separate: waiting for D1 I/O does not count as Worker CPU, but JavaScript work does.

Official sources (all on developers.cloudflare.com):
- Workers limits: https://developers.cloudflare.com/workers/platform/limits/
- Workers pricing: https://developers.cloudflare.com/workers/platform/pricing/
- D1 limits: https://developers.cloudflare.com/d1/platform/limits/
- D1 pricing: https://developers.cloudflare.com/d1/platform/pricing/
- D1 Free-tier limit enforcement: https://developers.cloudflare.com/changelog/post/2026-09-01-d1-free-tier-limit-enforcement/

Free-plan D1 queries fail when daily row-read or row-write limits are exceeded, until the UTC midnight reset. Deletions also count as D1 row writes. Monitor actual usage before enabling telemetry broadly.

## Setup and deployment (manual; phone-friendly)

No database, Worker, secret, or deployment is created by this repository change.

### 1. Create D1 database

1. Open `https://dash.cloudflare.com/` in Chrome on your phone and sign in.
2. Open **Workers & Pages → D1 SQL Database → Create database**.
3. Name it `JET_DIAGNOSTICS`.
4. Copy the database ID.
5. In GitHub, open `backend/wrangler.jsonc` and replace `REPLACE_WITH_D1_DATABASE_ID` with that ID. This ID is not a secret.

### 2. Apply migration

From a computer with Node.js installed, in the `backend/` directory run:

```bash
npm install
npx wrangler d1 migrations apply JET_DIAGNOSTICS --remote
```

The Cloudflare dashboard's D1 SQL console can also execute the contents of `migrations/0001_initial_schema.sql` manually from a phone.

### 3. Set secrets

In Cloudflare Dashboard, open **Workers & Pages → jet-diagnostics → Settings → Variables and Secrets** and add these as **Secrets**:

- `INGEST_API_KEY`: generate a long random value. It is shared with the app, so assume it can be extracted from the APK.
- `ADMIN_API_KEY`: generate a different long random value. Keep this only for dashboard access.

Alternatively, from a terminal:

```bash
npx wrangler secret put INGEST_API_KEY
npx wrangler secret put ADMIN_API_KEY
```

Never paste either value into GitHub files, issues, commits, or chat.

### 4. Deploy

Before deploying, replace the D1 database ID in `wrangler.jsonc`, install dependencies, and apply migrations. Then deploy from the `backend/` directory:

```bash
npx wrangler deploy
```

For phone-only deployment, use Cloudflare Dashboard → Workers & Pages → Create → Worker, then use the browser editor to create the Worker and configure its D1 binding and secrets. Uploading the whole source folder and applying migrations is easier from a computer; phone-only setup may be inconvenient.

### 5. Verify without enabling app traffic

Use the Cloudflare Worker URL with a test install ID and the secret configured locally. Check quota responses, dashboard authorization, and deletion behavior. Do not enable app telemetry until the consent UI and app-side opt-in integration are implemented separately.

## Local tests

Run:

```bash
npm test
```

The test suite covers total/failure/per-install quotas, success sampling, kill-switch decisions, shared bug/top-link quota constant, video-ID table placement, and URL/path sanitization. It does not replace a D1 integration test or a production CPU/load test.

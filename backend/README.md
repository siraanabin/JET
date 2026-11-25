# JET Telemetry API (Cloudflare Workers + D1)

This service is independent of the Android app source. It exposes ingestion endpoints and a PIN-protected admin API.

## Requirements

- Node.js 20+
- A Cloudflare account
- Wrangler CLI: `npm install -g wrangler` (or use `npx wrangler`)

Run all commands below from the repository's `backend/` directory.

## 1. Create the D1 database

### Browser

1. Open the [Cloudflare dashboard](https://dash.cloudflare.com/).
2. Select **Workers & Pages → D1 SQL Database → Create**.
3. Name it `jet-telemetry`.
4. Open the database's **Settings** and copy its Database ID.
5. Paste that ID into `database_id` in `wrangler.toml`.

### CLI

```sh
npx wrangler login
npx wrangler d1 create jet-telemetry
```

Copy the returned `database_id` into `wrangler.toml`.

## 2. Apply the schema

From `backend/`:

```sh
npx wrangler d1 execute jet-telemetry --remote --file=./schema.sql
```

For a local development database, omit `--remote`:

```sh
npx wrangler d1 execute jet-telemetry --local --file=./schema.sql
```

## 3. Configure the admin PIN

Set a strong, private PIN as a Worker secret (recommended; do not commit it):

```sh
npx wrangler secret put ADMIN_PIN
```

Paste the PIN when prompted. The `ADMIN_PIN` value in `wrangler.toml` is only a local placeholder; replace it for local testing or rely on the secret in deployed environments. Never use the placeholder in production.

## 4. Deploy

```sh
npx wrangler deploy
```

Wrangler prints the deployed Worker URL, for example `https://jet-telemetry-api.<account>.workers.dev`. Set `BASE_URL` below to that URL.

## 5. Test endpoints with curl

```sh
export BASE_URL="https://jet-telemetry-api.<account>.workers.dev"
export ADMIN_PIN="your-secret-pin"
```

### Heartbeat

```sh
curl -i -X POST "$BASE_URL/api/heartbeat" \
  -H 'Content-Type: application/json' \
  -d '{"install_id":"test-install-001","app_version":"0.9.5","device_model":"Pixel","os_version":"Android 15"}'
```

### Download ingestion

```sh
curl -i -X POST "$BASE_URL/api/download" \
  -H 'Content-Type: application/json' \
  -d '{"install_id":"test-install-001","video_url":"https://example.com/watch?v=abc","normalized_url":"https://example.com/watch?v=abc","title":"Example video","format":"mp4","size_text":"12 MB","engine":"custom-8-worker","speed_text":"4 MB/s","fallback_reason":null,"raw_telemetry":{"connections":8}}'
```

The endpoint increments the normalized link counter and retains only the newest 20 individual download log rows. Aggregate download totals remain available through `top_links.download_count`.

### Feedback

```sh
curl -i -X POST "$BASE_URL/api/feedback" \
  -H 'Content-Type: application/json' \
  -d '{"install_id":"test-install-001","type":"Bug","message":"Library scan did not show media","device_info":"Pixel / Android 15","app_version":"0.9.5","attached_log":"Optional diagnostic text"}'
```

### Admin summary (protected)

```sh
curl -i "$BASE_URL/api/admin/summary" \
  -H "Authorization: Bearer $ADMIN_PIN"
```

Returns `totalUsers`, `activeToday`, `totalDownloads`, `customEngineRate`, `last20Logs`, `topLinks` (up to 20), and `feedbackReports`.

### Toggle a feedback report's fixed state (protected)

Replace `1` with an existing feedback report ID:

```sh
curl -i -X POST "$BASE_URL/api/admin/feedback/1/toggle" \
  -H "Authorization: Bearer $ADMIN_PIN"
```

## API notes

- All timestamps are Unix seconds (UTC).
- JSON requests are limited to 64 KiB.
- CORS supports browser dashboard requests and app requests. For production, consider restricting allowed origins to known dashboard/app web origins.
- Public ingestion routes intentionally require no admin PIN. Add rate limiting/abuse controls at the Cloudflare layer before exposing publicly at scale.
- `customEngineRate` is the fraction of retained download-log rows whose engine is not `yt-dlp-managed`, `unknown`, or empty. Because only the newest 20 detailed logs are retained, this is a recent-window rate, not a lifetime rate.
- `totalDownloads` is calculated from cumulative `top_links.download_count`, so pruning detailed logs does not reduce the aggregate.

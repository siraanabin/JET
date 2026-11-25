const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };
const MAX_BODY_BYTES = 64 * 1024;

function corsHeaders(request) {
  const origin = request.headers.get("Origin");
  return {
    "Access-Control-Allow-Origin": origin || "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin"
  };
}

function respond(request, data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...JSON_HEADERS, ...corsHeaders(request) }
  });
}

function cleanString(value, max = 4000) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") return null;
  return value.trim().slice(0, max) || null;
}

async function readJson(request) {
  const length = Number(request.headers.get("Content-Length") || 0);
  if (length > MAX_BODY_BYTES) throw new Error("Request body too large");
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) throw new Error("Request body too large");
  try {
    const value = JSON.parse(text);
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
    return value;
  } catch {
    throw new Error("Request body must be a JSON object");
  }
}

function adminAuthorized(request, env) {
  const expected = env.ADMIN_PIN;
  const header = request.headers.get("Authorization") || "";
  return typeof expected === "string" && expected.length > 0 &&
    header === `Bearer ${expected}`;
}

async function heartbeat(request, env) {
  const body = await readJson(request);
  const installId = cleanString(body.install_id, 200);
  if (!installId) return respond(request, { error: "install_id is required" }, 400);
  const now = Math.floor(Date.now() / 1000);
  await env.DB.prepare(`
    INSERT INTO users (install_id, app_version, device_model, os_version, first_seen, last_seen)
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT(install_id) DO UPDATE SET
      app_version = COALESCE(excluded.app_version, users.app_version),
      device_model = COALESCE(excluded.device_model, users.device_model),
      os_version = COALESCE(excluded.os_version, users.os_version),
      last_seen = excluded.last_seen
  `).bind(
    installId,
    cleanString(body.app_version, 80),
    cleanString(body.device_model, 160),
    cleanString(body.os_version, 80),
    now,
    now
  ).run();
  return respond(request, { ok: true, last_seen: now });
}

async function download(request, env) {
  const body = await readJson(request);
  const normalizedUrl = cleanString(body.normalized_url, 2048);
  if (!normalizedUrl) return respond(request, { error: "normalized_url is required" }, 400);
  const now = Math.floor(Date.now() / 1000);
  const installId = cleanString(body.install_id, 200);
  const videoUrl = cleanString(body.video_url, 4096);
  const title = cleanString(body.title, 1000);
  const format = cleanString(body.format, 80);
  const engine = cleanString(body.engine, 120);
  const statements = [
    env.DB.prepare(`
      INSERT INTO download_logs
      (install_id, video_url, normalized_url, title, format, size_text, engine,
       speed_text, fallback_reason, raw_telemetry, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      installId, videoUrl, normalizedUrl, title, format,
      cleanString(body.size_text, 100), engine,
      cleanString(body.speed_text, 100),
      cleanString(body.fallback_reason, 2000),
      JSON.stringify(body.raw_telemetry ?? body).slice(0, MAX_BODY_BYTES),
      now
    ),
    env.DB.prepare(`
      INSERT INTO top_links
      (normalized_url, sample_url, title, download_count, last_format, last_downloaded_at)
      VALUES (?, ?, ?, 1, ?, ?)
      ON CONFLICT(normalized_url) DO UPDATE SET
        sample_url = COALESCE(excluded.sample_url, top_links.sample_url),
        title = COALESCE(excluded.title, top_links.title),
        download_count = top_links.download_count + 1,
        last_format = COALESCE(excluded.last_format, top_links.last_format),
        last_downloaded_at = excluded.last_downloaded_at
    `).bind(normalizedUrl, videoUrl, title, format, now),
    env.DB.prepare(`
      DELETE FROM download_logs
      WHERE id NOT IN (SELECT id FROM download_logs ORDER BY created_at DESC, id DESC LIMIT 20)
    `)
  ];
  await env.DB.batch(statements);
  return respond(request, { ok: true, created_at: now });
}

async function feedback(request, env) {
  const body = await readJson(request);
  const type = cleanString(body.type, 20);
  const message = cleanString(body.message, 10000);
  if (!["Bug", "Feature", "General"].includes(type))
    return respond(request, { error: "type must be Bug, Feature, or General" }, 400);
  if (!message) return respond(request, { error: "message is required" }, 400);
  const now = Math.floor(Date.now() / 1000);
  const result = await env.DB.prepare(`
    INSERT INTO feedback_reports
    (install_id, type, message, device_info, app_version, attached_log, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `).bind(
    cleanString(body.install_id, 200), type, message,
    cleanString(typeof body.device_info === "string" ? body.device_info : JSON.stringify(body.device_info), 4000),
    cleanString(body.app_version, 80),
    cleanString(body.attached_log, 16000),
    now
  ).run();
  return respond(request, { ok: true, id: result.meta.last_row_id });
}

async function adminSummary(request, env) {
  if (!adminAuthorized(request, env)) return respond(request, { error: "Unauthorized" }, 401);
  const now = Math.floor(Date.now() / 1000);
  const todayStart = now - (now % 86400);
  const [users, active, downloads, engines, logs, links, reports] = await Promise.all([
    env.DB.prepare("SELECT COUNT(*) AS count FROM users").first(),
    env.DB.prepare("SELECT COUNT(*) AS count FROM users WHERE last_seen >= ?").bind(todayStart).first(),
    env.DB.prepare("SELECT COALESCE(SUM(download_count), 0) AS count FROM top_links").first(),
    env.DB.prepare(`
      SELECT
        COALESCE(SUM(CASE WHEN engine IS NOT NULL AND engine NOT IN ('yt-dlp-managed', 'unknown', '') THEN 1 ELSE 0 END), 0) AS custom,
        COUNT(*) AS total
      FROM download_logs
    `).first(),
    env.DB.prepare("SELECT * FROM download_logs ORDER BY created_at DESC, id DESC LIMIT 20").all(),
    env.DB.prepare("SELECT * FROM top_links ORDER BY download_count DESC, last_downloaded_at DESC LIMIT 20").all(),
    env.DB.prepare("SELECT * FROM feedback_reports ORDER BY created_at DESC LIMIT 200").all()
  ]);
  const customEngineRate = engines.total ? engines.custom / engines.total : 0;
  return respond(request, {
    totalUsers: users.count,
    activeToday: active.count,
    totalDownloads: downloads.count,
    customEngineRate,
    last20Logs: logs.results,
    topLinks: links.results,
    feedbackReports: reports.results
  });
}

async function toggleFeedback(request, env, id) {
  if (!adminAuthorized(request, env)) return respond(request, { error: "Unauthorized" }, 401);
  if (!/^\\d+$/.test(id)) return respond(request, { error: "Invalid feedback id" }, 400);
  const result = await env.DB.prepare(`
    UPDATE feedback_reports SET is_fixed = CASE WHEN is_fixed = 0 THEN 1 ELSE 0 END
    WHERE id = ?
  `).bind(Number(id)).run();
  if (!result.meta.changes) return respond(request, { error: "Feedback report not found" }, 404);
  const row = await env.DB.prepare("SELECT id, is_fixed FROM feedback_reports WHERE id = ?").bind(Number(id)).first();
  return respond(request, { ok: true, ...row });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS")
      return new Response(null, { status: 204, headers: corsHeaders(request) });
    const url = new URL(request.url);
    try {
      if (request.method === "POST" && url.pathname === "/api/heartbeat") return await heartbeat(request, env);
      if (request.method === "POST" && url.pathname === "/api/download") return await download(request, env);
      if (request.method === "POST" && url.pathname === "/api/feedback") return await feedback(request, env);
      if (request.method === "GET" && url.pathname === "/api/admin/summary") return await adminSummary(request, env);
      const toggle = url.pathname.match(/^\\/api\\/admin\\/feedback\\/(\\d+)\\/toggle$/);
      if (request.method === "POST" && toggle) return await toggleFeedback(request, env, toggle[1]);
      return respond(request, { error: "Not found" }, 404);
    } catch (error) {
      const status = error.message === "Request body too large" ? 413 :
        error.message.startsWith("Request body") ? 400 : 500;
      return respond(request, { error: status === 500 ? "Internal server error" : error.message }, status);
    }
  }
};

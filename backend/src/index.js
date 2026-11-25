import {
  DAILY_WRITE_KILL_SWITCH,
  EVENT_WRITE_RESERVATION,
  FAILURE_EVENT_LIMIT,
  INGEST_BATCH_SIZE,
  SUBMISSION_WRITE_RESERVATION,
  TOTAL_EVENT_LIMIT,
  USER_EVENT_LIMIT,
  USER_SUBMISSION_LIMIT,
  isValidInstallId,
  isValidVideoId,
  sanitizeSensitiveText,
} from "./limits.js";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function utcDay() {
  return new Date().toISOString().slice(0, 10);
}

function nextUtcDay(day = utcDay()) {
  const date = new Date(`${day}T00:00:00.000Z`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString();
}

function stopResponse(code, day, accepted = 0) {
  return json({
    ok: false,
    accepted,
    code,
    retryAfter: nextUtcDay(day),
    stopUntilUtc: nextUtcDay(day).slice(0, 10),
    message: "Daily diagnostic limit reached. Pause submissions until the next UTC day.",
  }, 429);
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(a, b) {
  const left = new TextEncoder().encode(a || "");
  const right = new TextEncoder().encode(b || "");
  let diff = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let i = 0; i < length; i += 1) {
    diff |= (left[i] || 0) ^ (right[i] || 0);
  }
  return diff === 0;
}

function authorized(request, secret) {
  const supplied = request.headers.get("authorization") || "";
  return supplied.startsWith("Bearer ") &&
    constantTimeEqual(supplied.slice(7), secret);
}

function installId(request) {
  const id = request.headers.get("x-install-id") || "";
  return isValidInstallId(id) ? id : null;
}

function cleanNullableText(value, max = 500) {
  if (value === undefined || value === null || value === "") return null;
  return sanitizeSensitiveText(String(value), max) || null;
}

function normalizeEvent(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const eventType = ["success", "failure", "fallback"].includes(raw.event_type)
    ? raw.event_type : null;
  if (!eventType) return null;

  const numberOrNull = (value, max = Number.MAX_SAFE_INTEGER) => {
    const n = Number(value);
    return Number.isFinite(n) && n >= 0 && n <= max ? n : null;
  };

  return {
    event_type: eventType,
    engine: cleanNullableText(raw.engine, 80) || "unknown",
    fallback_reason: cleanNullableText(raw.fallbackReason, 160),
    init_step: cleanNullableText(raw.initStep, 100),
    init_error: cleanNullableText(raw.initError, 500),
    speed: numberOrNull(raw.speed, 1_000_000),
    duration_ms: numberOrNull(raw.durationMs, 7 * 24 * 60 * 60 * 1000),
    format: cleanNullableText(raw.format, 40),
    resolution: cleanNullableText(raw.resolution, 40),
    size_bytes: numberOrNull(raw.sizeBytes, Number.MAX_SAFE_INTEGER),
    retries: numberOrNull(raw.retries, 100) ?? 0,
    app_version: cleanNullableText(raw.appVersion, 40),
    android_version: cleanNullableText(raw.androidVersion, 40),
    device_model: cleanNullableText(raw.deviceModel, 100),
    abi: cleanNullableText(raw.abi, 40),
    network_type: cleanNullableText(raw.networkType, 40),
    free_storage_bucket: cleanNullableText(raw.freeStorageBucket, 40),
  };
}

async function ingest(request, env) {
  if (!authorized(request, env.INGEST_API_KEY)) return json({ ok: false, code: "UNAUTHORIZED" }, 401);
  const rawInstallId = installId(request);
  if (!rawInstallId) return json({ ok: false, code: "INVALID_INSTALL_ID" }, 400);
  let body;
  try { body = await request.json(); } catch { return json({ ok: false, code: "INVALID_JSON" }, 400); }
  if (!body || !Array.isArray(body.events) || body.events.length < 1 || body.events.length > INGEST_BATCH_SIZE) {
    return json({ ok: false, code: "INVALID_BATCH", maxBatchSize: INGEST_BATCH_SIZE }, 400);
  }
  const events = body.events.map(normalizeEvent);
  if (events.some((event) => !event)) return json({ ok: false, code: "INVALID_EVENT" }, 400);

  const installHash = await sha256(rawInstallId);
  const day = utcDay();
  const statements = [
    env.DB.prepare("INSERT OR IGNORE INTO daily_counters(day, install_hash, write_units) VALUES (?, '__global__', 1)").bind(day),
    env.DB.prepare(`
      INSERT OR IGNORE INTO daily_counters(day, install_hash)
      SELECT ?, ?
      WHERE (SELECT write_units FROM daily_counters
             WHERE day = ? AND install_hash = '__global__') + 2 <= ?
    `).bind(day, installHash, day, DAILY_WRITE_KILL_SWITCH),
    env.DB.prepare(`
      UPDATE daily_counters
      SET write_units = write_units + changes() + 1
      WHERE day = ? AND install_hash = '__global__' AND changes() > 0
    `).bind(day),
  ];

  for (const event of events) {
    const metricKey = `${event.event_type}:${event.engine}:${event.format || "-"}:${event.resolution || "-"}`;
    statements.push(
      env.DB.prepare(`
        INSERT INTO events (
          day, install_hash, event_type, engine, fallback_reason, init_step,
          init_error, speed, duration_ms, format, resolution, size_bytes,
          retries, app_version, android_version, device_model, abi, network_type,
          free_storage_bucket, created_at
        )
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        WHERE
          (SELECT event_count FROM daily_counters WHERE day = ? AND install_hash = '__global__') < ?
          AND (
            ? IN ('failure', 'fallback')
            OR (SELECT event_count FROM daily_counters WHERE day = ? AND install_hash = '__global__') < ?
          )
          AND (SELECT event_count FROM daily_counters WHERE day = ? AND install_hash = ?) < ?
          AND (SELECT write_units FROM daily_counters WHERE day = ? AND install_hash = '__global__') + ? <= ?
          AND (
            ? NOT IN ('failure', 'fallback')
            OR (SELECT failure_count FROM daily_counters WHERE day = ? AND install_hash = '__global__') < ?
          )
          AND (
            ? != 'success'
            OR (SELECT success_seen + 1 FROM daily_counters WHERE day = ? AND install_hash = '__global__') <= 5000
            OR ((SELECT success_seen + 1 FROM daily_counters WHERE day = ? AND install_hash = '__global__') - 5001) % 10 = 0
          )
      `).bind(
        day, installHash, event.event_type, event.engine, event.fallback_reason,
        event.init_step, event.init_error, event.speed, event.duration_ms,
        event.format, event.resolution, event.size_bytes, event.retries,
        event.app_version, event.android_version, event.device_model, event.abi,
        event.network_type, event.free_storage_bucket, new Date().toISOString(),
        day, TOTAL_EVENT_LIMIT,
        event.event_type, day, TOTAL_EVENT_LIMIT - FAILURE_EVENT_LIMIT,
        day, installHash, USER_EVENT_LIMIT,
        day, EVENT_WRITE_RESERVATION, DAILY_WRITE_KILL_SWITCH,
        event.event_type, day, FAILURE_EVENT_LIMIT,
        event.event_type, day, day
      ),
      env.DB.prepare(`
        INSERT INTO daily_summary(day, metric_key, event_count, duration_sum, speed_sum)
        SELECT ?, ?, 1, COALESCE(?, 0), COALESCE(?, 0)
        WHERE changes() > 0
        ON CONFLICT(day, metric_key) DO UPDATE SET
          event_count = event_count + 1,
          duration_sum = duration_sum + excluded.duration_sum,
          speed_sum = speed_sum + excluded.speed_sum
      `).bind(day, metricKey, event.duration_ms, event.speed),
      env.DB.prepare(`
        UPDATE daily_counters
        SET
          event_count = event_count + changes(),
          failure_count = failure_count + CASE WHEN ? IN ('failure', 'fallback') THEN changes() ELSE 0 END,
          success_seen = success_seen + CASE WHEN ? = 'success' THEN 1 ELSE 0 END,
          write_units = write_units + changes() * ? +
            CASE WHEN ? = 'success' AND changes() = 0 THEN 2 ELSE 0 END
        WHERE day = ? AND install_hash IN (?, '__global__')
          AND (
            changes() > 0
            OR (
              ? = 'success'
              AND (SELECT write_units FROM daily_counters
                   WHERE day = ? AND install_hash = '__global__') + 2 <= ?
            )
          )
      `).bind(
        event.event_type, event.event_type, EVENT_WRITE_RESERVATION,
        event.event_type, day, installHash, event.event_type, day, DAILY_WRITE_KILL_SWITCH
      )
    );
  }

  // 3 counter setup/accounting statements + (3 statements * 15 events) = 48.
  // One D1 batch is one D1 query subrequest for this Worker invocation.
  const results = await env.DB.batch(statements);
  let accepted = 0;
  for (let i = 0; i < events.length; i += 1) {
    if ((results[3 + (i * 3)]?.meta?.changes || 0) > 0) accepted += 1;
  }

  if (accepted === 0) {
    const counters = await env.DB.prepare(`
      SELECT event_count, failure_count, success_seen, write_units
      FROM daily_counters WHERE day = ? AND install_hash = '__global__'
    `).bind(day).first();
    const user = await env.DB.prepare(`
      SELECT event_count FROM daily_counters WHERE day = ? AND install_hash = ?
    `).bind(day, installHash).first();
    let code = "EVENT_LIMIT_REACHED";
    if (counters?.write_units >= DAILY_WRITE_KILL_SWITCH) code = "WRITE_KILL_SWITCH";
    else if (user?.event_count >= USER_EVENT_LIMIT) code = "USER_EVENT_LIMIT";
    else if (counters?.event_count >= TOTAL_EVENT_LIMIT) code = "EVENT_LIMIT_REACHED";
    else if (events.every((e) => e.event_type !== "success") && counters?.failure_count >= FAILURE_EVENT_LIMIT) code = "FAILURE_LIMIT_REACHED";
    else if (counters?.event_count >= TOTAL_EVENT_LIMIT - FAILURE_EVENT_LIMIT) code = "FAILURE_CAPACITY_RESERVED";
    else code = "SUCCESS_SAMPLING";
    return stopResponse(code, day, 0);
  }

  return json({
    ok: true,
    accepted,
    rejected: events.length - accepted,
    code: events.length === accepted ? "ACCEPTED" : "PARTIAL_LIMIT_OR_SAMPLING",
    stopUntilUtc: events.length === accepted ? null : nextUtcDay(day).slice(0, 10),
    maxBatchSize: INGEST_BATCH_SIZE,
  });
}

async function submitUserItem(request, env, kind) {
  if (!authorized(request, env.INGEST_API_KEY)) return json({ ok: false, code: "UNAUTHORIZED" }, 401);
  const rawInstallId = installId(request);
  if (!rawInstallId) return json({ ok: false, code: "INVALID_INSTALL_ID" }, 400);
  let body;
  try { body = await request.json(); } catch { return json({ ok: false, code: "INVALID_JSON" }, 400); }

  const day = utcDay();
  const hash = await sha256(rawInstallId);
  const createdAt = new Date().toISOString();
  const statements = [
    env.DB.prepare("INSERT OR IGNORE INTO daily_counters(day, install_hash, write_units) VALUES (?, '__global__', 1)").bind(day),
    env.DB.prepare(`
      INSERT OR IGNORE INTO daily_counters(day, install_hash)
      SELECT ?, ?
      WHERE (SELECT write_units FROM daily_counters
             WHERE day = ? AND install_hash = '__global__') + 2 <= ?
    `).bind(day, hash, day, DAILY_WRITE_KILL_SWITCH),
    env.DB.prepare(`
      UPDATE daily_counters SET write_units = write_units + changes() + 1
      WHERE day = ? AND install_hash = '__global__' AND changes() > 0
    `).bind(day),
  ];

  if (kind === "bug") {
    const description = sanitizeSensitiveText(body?.description, 500);
    if (!description) return json({ ok: false, code: "INVALID_DESCRIPTION" }, 400);
    const category = sanitizeSensitiveText(String(body.category || "general"), 80) || "general";
    const appVersion = cleanNullableText(body.appVersion, 40);
    statements.push(env.DB.prepare(`
      INSERT INTO bug_reports(day, install_hash, category, description, app_version, created_at)
      SELECT ?, ?, ?, ?, ?, ?
      WHERE (SELECT submission_count FROM daily_counters WHERE day = ? AND install_hash = ?) < ?
        AND (SELECT write_units FROM daily_counters WHERE day = ? AND install_hash = '__global__') + ? <= ?
    `).bind(day, hash, category, description, appVersion, createdAt,
      day, hash, USER_SUBMISSION_LIMIT, day, SUBMISSION_WRITE_RESERVATION, DAILY_WRITE_KILL_SWITCH));
  } else {
    if (!isValidVideoId(body?.video_id)) return json({ ok: false, code: "INVALID_VIDEO_ID" }, 400);
    statements.push(env.DB.prepare(`
      INSERT INTO top_links(day, install_hash, video_id, created_at)
      SELECT ?, ?, ?, ?
      WHERE (SELECT submission_count FROM daily_counters WHERE day = ? AND install_hash = ?) < ?
        AND (SELECT write_units FROM daily_counters WHERE day = ? AND install_hash = '__global__') + ? <= ?
    `).bind(day, hash, body.video_id, createdAt,
      day, hash, USER_SUBMISSION_LIMIT, day, SUBMISSION_WRITE_RESERVATION, DAILY_WRITE_KILL_SWITCH));
  }

  statements.push(env.DB.prepare(`
    UPDATE daily_counters
    SET
      submission_count = submission_count + changes(),
      write_units = write_units + changes() * ?
    WHERE day = ? AND install_hash IN (?, '__global__') AND changes() > 0
  `).bind(SUBMISSION_WRITE_RESERVATION, day, hash));

  const results = await env.DB.batch(statements);
  const inserted = results[3]?.meta?.changes || 0;
  if (!inserted) {
    const row = await env.DB.prepare(`
      SELECT submission_count FROM daily_counters WHERE day = ? AND install_hash = ?
    `).bind(day, hash).first();
    const global = await env.DB.prepare(`
      SELECT write_units FROM daily_counters WHERE day = ? AND install_hash = '__global__'
    `).bind(day).first();
    const code = global?.write_units >= DAILY_WRITE_KILL_SWITCH
      ? "WRITE_KILL_SWITCH"
      : row?.submission_count >= USER_SUBMISSION_LIMIT ? "USER_SUBMISSION_LIMIT" : "DAILY_LIMIT_REACHED";
    return stopResponse(code, day);
  }
  const latest = await env.DB.prepare(
    "SELECT submission_count FROM daily_counters WHERE day = ? AND install_hash = ?"
  ).bind(day, hash).first();
  return json({ ok: true, accepted: 1, remaining: Math.max(0, USER_SUBMISSION_LIMIT -
    (latest?.submission_count || 0)), stopUntilUtc: null });
}

async function dailySummary(request, env) {
  if (!authorized(request, env.ADMIN_API_KEY)) return json({ ok: false, code: "UNAUTHORIZED" }, 401);
  const day = new URL(request.url).searchParams.get("day") || utcDay();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return json({ ok: false, code: "INVALID_DAY" }, 400);
  const rows = await env.DB.prepare(`
    SELECT day, metric_key, event_count, duration_sum, speed_sum
    FROM daily_summary WHERE day = ? ORDER BY event_count DESC
  `).bind(day).all();
  return json({ ok: true, day, summary: rows.results || [] });
}

async function deleteMyData(request, env) {
  if (!authorized(request, env.INGEST_API_KEY)) return json({ ok: false, code: "UNAUTHORIZED" }, 401);
  const rawInstallId = installId(request);
  if (!rawInstallId) return json({ ok: false, code: "INVALID_INSTALL_ID" }, 400);
  const hash = await sha256(rawInstallId);
  const results = await env.DB.batch([
    env.DB.prepare("DELETE FROM events WHERE install_hash = ?").bind(hash),
    env.DB.prepare("DELETE FROM bug_reports WHERE install_hash = ?").bind(hash),
    env.DB.prepare("DELETE FROM top_links WHERE install_hash = ?").bind(hash),
    env.DB.prepare("DELETE FROM daily_counters WHERE install_hash = ?").bind(hash),
  ]);
  return json({
    ok: true,
    deleted: true,
    rows: results.reduce((sum, result) => sum + (result.meta?.changes || 0), 0),
    dailySummaryChanged: false,
  });
}

async function retention(env) {
  const cutoff = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  const day = utcDay();
  const budgetedDelete = (table, keyExpression, keyColumns, limit, cost) => [
    env.DB.prepare(`
      DELETE FROM ${table}
      WHERE ${keyExpression} IN (
        SELECT ${keyColumns} FROM ${table} WHERE day < ? ORDER BY day LIMIT ${limit}
      )
      AND (
        SELECT write_units FROM daily_counters
        WHERE day = ? AND install_hash = '__global__'
      ) + (
        SELECT COUNT(*) FROM (
          SELECT ${keyColumns} FROM ${table} WHERE day < ? ORDER BY day LIMIT ${limit}
        )
      ) * ${cost} + 1 <= ?
    `).bind(cutoff, day, cutoff, DAILY_WRITE_KILL_SWITCH),
    env.DB.prepare(`
      UPDATE daily_counters
      SET write_units = write_units + changes() * ${cost} + 1
      WHERE day = ? AND install_hash = '__global__' AND changes() > 0
    `).bind(day),
  ];
  await env.DB.batch([
    env.DB.prepare("INSERT OR IGNORE INTO daily_counters(day, install_hash, write_units) VALUES (?, '__global__', 1)").bind(day),
    ...budgetedDelete("events", "id", "id", 2_000, 2),
    ...budgetedDelete("bug_reports", "id", "id", 250, 3),
    ...budgetedDelete("top_links", "id", "id", 250, 3),
    ...budgetedDelete("daily_summary", "(day, metric_key)", "day, metric_key", 250, 1),
    ...budgetedDelete("daily_counters", "(day, install_hash)", "day, install_hash", 250, 1),
  ]);
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
          "access-control-allow-headers": "authorization,content-type,x-install-id",
          "access-control-max-age": "86400",
        },
      });
    }
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/v1/ingest") return ingest(request, env);
    if (request.method === "POST" && url.pathname === "/v1/bug-report") return submitUserItem(request, env, "bug");
    if (request.method === "POST" && url.pathname === "/v1/top-link") return submitUserItem(request, env, "top");
    if (request.method === "GET" && url.pathname === "/v1/admin/daily-summary") return dailySummary(request, env);
    if (request.method === "DELETE" && url.pathname === "/v1/me/data") return deleteMyData(request, env);
    return json({ ok: false, code: "NOT_FOUND" }, 404);
  },

  async scheduled(_controller, env, ctx) {
    ctx.waitUntil(retention(env));
  },
};

import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { sanitizeSensitiveText, isValidVideoId, normalizeBugLogs } from "../src/limits.js";

test("video_id exists only in top_links, not events or daily_summary", async () => {
  const schema = await readFile(new URL("../migrations/0001_initial_schema.sql", import.meta.url), "utf8");
  const eventsBlock = schema.split("CREATE TABLE IF NOT EXISTS events (")[1].split(");")[0];
  const summaryBlock = schema.split("CREATE TABLE IF NOT EXISTS daily_summary (")[1].split(");")[0];
  const linksBlock = schema.split("CREATE TABLE IF NOT EXISTS top_links (")[1].split(");")[0];
  assert.equal(/video_id/i.test(eventsBlock), false);
  assert.equal(/video_id/i.test(summaryBlock), false);
  assert.match(linksBlock, /video_id/);
  const limits = await readFile(new URL("../src/limits.js", import.meta.url), "utf8");
  const worker = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(limits, /USER_EVENT_LIMIT = 60/);
  assert.match(worker, /USER_EVENT_LIMIT/);
  assert.match(worker, /cleanNullableText\(raw\.initError, 500\)/);
  assert.match(worker, /sanitizeSensitiveText\(body\?\.description, 500\)/);
  assert.match(worker, /normalizeBugLogs\(body\?\.logs\)/);
  assert.match(worker, /INSERT INTO bug_reports\([^)]*logs/s);
  assert.match(worker, /url\.pathname === "\/v1\/top-link"/);
  const deleteHandler = worker.split("async function deleteMyData")[1].split("async function retention")[0];
  assert.match(deleteHandler, /DELETE FROM events/);
  assert.match(deleteHandler, /DELETE FROM bug_reports/);
  assert.match(deleteHandler, /DELETE FROM top_links/);
  assert.match(deleteHandler, /DELETE FROM daily_counters/);
  assert.doesNotMatch(deleteHandler, /DELETE FROM daily_summary/);
  assert.match(deleteHandler, /dailySummaryChanged: false/);
});

test("sensitive text removes URLs, paths, email, phone, IPv4 and IPv6", () => {
  const clean = sanitizeSensitiveText(
    "url https://example.test/private?q=1 path /storage/emulated/0/Movies/video.mp4 " +
    "mail user@example.com phone +1 (202) 555-0198 ipv4 192.168.1.25 ipv6 2001:db8::1"
  );
  for (const value of ["https://", "/storage/emulated/0", "user@example.com", "202) 555", "192.168.1.25", "2001:db8::1"]) {
    assert.equal(clean.includes(value), false, value);
  }
  assert.match(clean, /\[EMAIL\]/);
  assert.match(clean, /\[PHONE\]/);
  assert.ok((clean.match(/\[IP\]/g) || []).length >= 2);
});

test("sensitive text truncates to requested maximum", () => {
  assert.equal(sanitizeSensitiveText("x".repeat(700), 500).length, 500);
});

test("bug logs allowlist, sanitize, cap at 20 and 6000 serialized chars", () => {
  const logs = Array.from({ length: 20 }, (_, i) => ({
    event_type: "failure", engine: "yt-dlp", initError: "user@example.com 10.0.0.1 " + "x".repeat(200),
    speed: i + 0.5, title: "must-not-store", url: "https://example.com"
  }));
  const normalized = normalizeBugLogs(logs);
  const parsed = JSON.parse(normalized);
  assert.ok(parsed.length <= 20);
  assert.ok(normalized.length <= 6000);
  assert.equal("title" in parsed[0], false);
  assert.equal("url" in parsed[0], false);
  assert.equal(parsed[0].initError.includes("user@example.com"), false);
  assert.equal(parsed[0].initError.includes("10.0.0.1"), false);
  assert.equal(normalizeBugLogs(Array(21).fill({ engine: "x" })), null);
});

test("top-link accepts 1-64 allowed characters only, rejects URL and invalid lengths", () => {
  assert.equal(isValidVideoId("a"), true);
  assert.equal(isValidVideoId("Abc_123-xy"), true);
  assert.equal(isValidVideoId("a".repeat(64)), true);
  assert.equal(isValidVideoId("a".repeat(65)), false);
  assert.equal(isValidVideoId("https://example.test/watch?v=abc"), false);
  assert.equal(isValidVideoId("bad id"), false);
});

test("bug-report description is sanitized and limited to 500 characters", () => {
  const description = sanitizeSensitiveText("contact user@example.com " + "x".repeat(700), 500);
  assert.ok(description.length <= 500);
  assert.equal(description.includes("user@example.com"), false);
});

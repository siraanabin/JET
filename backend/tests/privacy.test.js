import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { sanitizeSensitiveText, isValidVideoId } from "../src/limits.js";

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
  const deleteHandler = worker.split("async function deleteMyData")[1].split("async function retention")[0];
  assert.match(deleteHandler, /DELETE FROM events/);
  assert.match(deleteHandler, /DELETE FROM bug_reports/);
  assert.match(deleteHandler, /DELETE FROM top_links/);
  assert.match(deleteHandler, /DELETE FROM daily_counters/);
  assert.doesNotMatch(deleteHandler, /DELETE FROM daily_summary/);
});

test("sensitive text strips URLs and file paths and truncates to 500 chars", () => {
  const clean = sanitizeSensitiveText(
    "Failed at https://example.test/private?q=1 /storage/emulated/0/Movies/video.mp4 " + "x".repeat(600)
  );
  assert.equal(clean.includes("https://"), false);
  assert.equal(clean.includes("/storage/emulated/0"), false);
  assert.ok(clean.length <= 500);
});

test("top-link accepts an ID but rejects a complete URL", () => {
  assert.equal(isValidVideoId("Abc_123-xy"), true);
  assert.equal(isValidVideoId("https://example.test/watch?v=abc"), false);
});

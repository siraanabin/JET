import test from "node:test";
import assert from "node:assert/strict";
import {
  DAILY_WRITE_KILL_SWITCH,
  FAILURE_EVENT_LIMIT,
  SUCCESS_SAMPLE_AFTER,
  SUCCESS_EVENT_CEILING,
  TOTAL_EVENT_LIMIT,
  USER_EVENT_LIMIT,
  USER_SUBMISSION_LIMIT,
  shouldAcceptEvent,
  shouldAcceptSubmission,
} from "../src/limits.js";

const base = {
  eventType: "success",
  totalEvents: 0,
  failureEvents: 0,
  userEvents: 0,
  globalSuccessSeen: 0,
  writeUnits: 0,
};

test("daily total event quota", () => {
  assert.equal(shouldAcceptEvent({ ...base, totalEvents: TOTAL_EVENT_LIMIT }).code, "EVENT_LIMIT_REACHED");
});

test("success events cannot consume the reserved failure/fallback capacity", () => {
  assert.equal(shouldAcceptEvent({ ...base, totalEvents: SUCCESS_EVENT_CEILING }).code, "FAILURE_CAPACITY_RESERVED");
  assert.equal(shouldAcceptEvent({
    ...base, eventType: "failure", totalEvents: SUCCESS_EVENT_CEILING,
  }).accepted, true);
});

test("failure/fallback quota has its own reserved cap", () => {
  assert.equal(shouldAcceptEvent({
    ...base, eventType: "failure", failureEvents: FAILURE_EVENT_LIMIT,
  }).code, "FAILURE_LIMIT_REACHED");
  assert.equal(shouldAcceptEvent({
    ...base, eventType: "fallback", failureEvents: FAILURE_EVENT_LIMIT,
  }).code, "FAILURE_LIMIT_REACHED");
});

test("per-install event quota", () => {
  assert.equal(shouldAcceptEvent({ ...base, userEvents: USER_EVENT_LIMIT }).code, "USER_EVENT_LIMIT");
});

test("success sampling keeps first 5000 and then every tenth", () => {
  assert.equal(shouldAcceptEvent({ ...base, globalSuccessSeen: SUCCESS_SAMPLE_AFTER }).accepted, true);
  assert.equal(shouldAcceptEvent({ ...base, globalSuccessSeen: 5001 }).accepted, true);
  assert.equal(shouldAcceptEvent({ ...base, globalSuccessSeen: 5002 }).accepted, false);
  assert.equal(shouldAcceptEvent({ ...base, globalSuccessSeen: 5011 }).accepted, true);
});

test("write kill switch blocks writes at its boundary", () => {
  assert.equal(shouldAcceptEvent({ ...base, writeUnits: DAILY_WRITE_KILL_SWITCH - 4 }).code, "WRITE_KILL_SWITCH");
});

test("user bug and top-link submissions share one ten-item limit", () => {
  assert.equal(USER_SUBMISSION_LIMIT, 10);
  assert.equal(shouldAcceptSubmission({ submissionCount: 9, writeUnits: 0 }).accepted, true);
  assert.equal(shouldAcceptSubmission({ submissionCount: 10, writeUnits: 0 }).code, "USER_SUBMISSION_LIMIT");
  assert.equal(shouldAcceptSubmission({ submissionCount: 0, writeUnits: DAILY_WRITE_KILL_SWITCH }).code, "WRITE_KILL_SWITCH");
});

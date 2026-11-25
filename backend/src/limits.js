export const TOTAL_EVENT_LIMIT = 8_000;
export const FAILURE_EVENT_LIMIT = 1_500;
export const SUCCESS_EVENT_CEILING = TOTAL_EVENT_LIMIT - FAILURE_EVENT_LIMIT;
export const USER_EVENT_LIMIT = 60;
export const SUCCESS_SAMPLE_AFTER = 5_000;
export const SUCCESS_SAMPLE_EVERY = 10;
export const USER_SUBMISSION_LIMIT = 10;
export const DAILY_WRITE_KILL_SWITCH = 60_000;
export const INGEST_BATCH_SIZE = 15;

// Conservative reservation: event row + event indexes + two counter rows +
// at most one summary row. Compare the estimate with D1 dashboard usage.
export const EVENT_WRITE_RESERVATION = 5;
export const SUBMISSION_WRITE_RESERVATION = 6;

export function shouldAcceptEvent({
  eventType,
  totalEvents,
  failureEvents,
  userEvents,
  globalSuccessSeen,
  writeUnits,
}) {
  if (writeUnits + EVENT_WRITE_RESERVATION > DAILY_WRITE_KILL_SWITCH) {
    return { accepted: false, code: "WRITE_KILL_SWITCH" };
  }
  if (totalEvents >= TOTAL_EVENT_LIMIT) {
    return { accepted: false, code: "EVENT_LIMIT_REACHED" };
  }
  if (userEvents >= USER_EVENT_LIMIT) {
    return { accepted: false, code: "USER_EVENT_LIMIT" };
  }
  if (eventType === "failure" || eventType === "fallback") {
    if (failureEvents >= FAILURE_EVENT_LIMIT) {
      return { accepted: false, code: "FAILURE_LIMIT_REACHED" };
    }
    return { accepted: true, code: null };
  }
  if (totalEvents >= SUCCESS_EVENT_CEILING) {
    return { accepted: false, code: "FAILURE_CAPACITY_RESERVED" };
  }
  if (
    globalSuccessSeen > SUCCESS_SAMPLE_AFTER &&
    (globalSuccessSeen - SUCCESS_SAMPLE_AFTER - 1) % SUCCESS_SAMPLE_EVERY !== 0
  ) {
    return { accepted: false, code: "SUCCESS_SAMPLING" };
  }
  return { accepted: true, code: null };
}

export function shouldAcceptSubmission({ submissionCount, writeUnits }) {
  if (writeUnits + SUBMISSION_WRITE_RESERVATION > DAILY_WRITE_KILL_SWITCH) {
    return { accepted: false, code: "WRITE_KILL_SWITCH" };
  }
  if (submissionCount >= USER_SUBMISSION_LIMIT) {
    return { accepted: false, code: "USER_SUBMISSION_LIMIT" };
  }
  return { accepted: true, code: null };
}

export function sanitizeSensitiveText(value, maxLength = 500) {
  if (typeof value !== "string") return "";
  return value
    .replace(/\b(?:https?|ftp):\/\/[^\s"'<>]+/gi, "[URL]")
    .replace(/\bwww\.[^\s"'<>]+/gi, "[URL]")
    .replace(/\b(?:file|content):\/\/[^\s"'<>]+/gi, "[PATH]")
    .replace(/\b[A-Za-z]:\\[^\s"'<>]+/g, "[PATH]")
    .replace(/\\\\[^\s"'<>]+/g, "[PATH]")
    .replace(/(?:^|\s)\/?(?:[\w.-]+\/){1,}[\w.-]+/g, " [PATH]")
    .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, "[EMAIL]")
    .replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, "[IP]")
    .replace(/(?<![\w:])(?:[A-F0-9]{0,4}:){2,7}[A-F0-9]{0,4}(?![\w:])/gi, "[IP]")
    .replace(/(?<!\d)(?:\+?\d[\s().-]*){7,15}(?!\d)/g, "[PHONE]")
    .replace(/[\u0000-\u001F\u007F]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, maxLength);
}

export function isValidInstallId(value) {
  return typeof value === "string" &&
    value.length >= 16 &&
    value.length <= 128 &&
    /^[A-Za-z0-9._-]+$/.test(value);
}

export function isValidVideoId(value) {
  return typeof value === "string" &&
    value.length >= 1 &&
    value.length <= 64 &&
    /^[A-Za-z0-9_-]+$/.test(value);
}

const BUG_LOG_FIELDS = new Set([
  "event_type", "engine", "fallbackReason", "initStep", "initError",
  "speed", "durationMs", "format", "resolution", "sizeBytes", "retries",
  "appVersion", "androidVersion", "deviceModel", "abi", "networkType",
  "freeStorageBucket", "timestamp",
]);
const BUG_LOG_TEXT_LIMITS = {
  event_type: 16, engine: 80, fallbackReason: 160, initStep: 100,
  initError: 300, format: 40, resolution: 40, appVersion: 40,
  androidVersion: 40, deviceModel: 80, abi: 40, networkType: 40,
  freeStorageBucket: 40, timestamp: 32,
};

export function normalizeBugLogs(value) {
  if (value === undefined || value === null) return "[]";
  if (!Array.isArray(value) || value.length > 20) return null;
  const result = [];
  let used = 2;
  for (const raw of value) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) continue;
    const clean = {};
    for (const [key, item] of Object.entries(raw)) {
      if (!BUG_LOG_FIELDS.has(key) || item === undefined || item === null) continue;
      if (Object.hasOwn(BUG_LOG_TEXT_LIMITS, key)) {
        if (typeof item !== "string") continue;
        const text = sanitizeSensitiveText(item, BUG_LOG_TEXT_LIMITS[key]);
        if (text) clean[key] = text;
      } else if (typeof item === "number" && Number.isFinite(item) && item >= 0) {
        clean[key] = item;
      }
    }
    if (!Object.keys(clean).length) continue;
    const encoded = JSON.stringify(clean);
    const extra = encoded.length + (result.length ? 1 : 0);
    if (used + extra > 6000) break;
    result.push(clean);
    used += extra;
  }
  return JSON.stringify(result);
}

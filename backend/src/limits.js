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
    value.length >= 3 &&
    value.length <= 128 &&
    /^[A-Za-z0-9_-]+$/.test(value);
}

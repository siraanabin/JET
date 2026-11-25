PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY,
  day TEXT NOT NULL,
  install_hash TEXT NOT NULL,
  event_type TEXT NOT NULL CHECK (event_type IN ('success', 'failure', 'fallback')),
  engine TEXT NOT NULL,
  fallback_reason TEXT,
  init_step TEXT,
  init_error TEXT,
  speed REAL,
  duration_ms INTEGER,
  format TEXT,
  resolution TEXT,
  size_bytes INTEGER,
  retries INTEGER NOT NULL DEFAULT 0,
  app_version TEXT,
  android_version TEXT,
  device_model TEXT,
  abi TEXT,
  network_type TEXT,
  free_storage_bucket TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_day
  ON events(day);

CREATE TABLE IF NOT EXISTS daily_summary (
  day TEXT NOT NULL,
  metric_key TEXT NOT NULL,
  event_count INTEGER NOT NULL DEFAULT 0,
  duration_sum INTEGER NOT NULL DEFAULT 0,
  speed_sum REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (day, metric_key)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS bug_reports (
  id INTEGER PRIMARY KEY,
  day TEXT NOT NULL,
  install_hash TEXT NOT NULL,
  category TEXT NOT NULL,
  description TEXT NOT NULL,
  app_version TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bug_reports_install_day
  ON bug_reports(install_hash, day);
CREATE INDEX IF NOT EXISTS idx_bug_reports_day
  ON bug_reports(day);

CREATE TABLE IF NOT EXISTS top_links (
  id INTEGER PRIMARY KEY,
  day TEXT NOT NULL,
  install_hash TEXT NOT NULL,
  video_id TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_top_links_install_day
  ON top_links(install_hash, day);
CREATE INDEX IF NOT EXISTS idx_top_links_day
  ON top_links(day);

CREATE TABLE IF NOT EXISTS daily_counters (
  day TEXT NOT NULL,
  install_hash TEXT NOT NULL,
  event_count INTEGER NOT NULL DEFAULT 0,
  failure_count INTEGER NOT NULL DEFAULT 0,
  success_seen INTEGER NOT NULL DEFAULT 0,
  submission_count INTEGER NOT NULL DEFAULT 0,
  write_units INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, install_hash)
) WITHOUT ROWID;

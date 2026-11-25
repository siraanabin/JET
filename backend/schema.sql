PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  install_id TEXT PRIMARY KEY,
  app_version TEXT,
  device_model TEXT,
  os_version TEXT,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS download_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  install_id TEXT,
  video_url TEXT,
  normalized_url TEXT,
  title TEXT,
  format TEXT,
  size_text TEXT,
  engine TEXT,
  speed_text TEXT,
  fallback_reason TEXT,
  raw_telemetry TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_download_logs_created_at
  ON download_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_download_logs_install_id
  ON download_logs(install_id);
CREATE INDEX IF NOT EXISTS idx_download_logs_normalized_url
  ON download_logs(normalized_url);

CREATE TABLE IF NOT EXISTS top_links (
  normalized_url TEXT PRIMARY KEY,
  sample_url TEXT,
  title TEXT,
  download_count INTEGER NOT NULL DEFAULT 1,
  last_format TEXT,
  last_downloaded_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_top_links_download_count
  ON top_links(download_count DESC, last_downloaded_at DESC);

CREATE TABLE IF NOT EXISTS feedback_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  install_id TEXT,
  type TEXT NOT NULL CHECK (type IN ('Bug', 'Feature', 'General')),
  message TEXT NOT NULL,
  device_info TEXT,
  app_version TEXT,
  attached_log TEXT,
  is_fixed INTEGER NOT NULL DEFAULT 0 CHECK (is_fixed IN (0, 1)),
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feedback_reports_created_at
  ON feedback_reports(created_at DESC);

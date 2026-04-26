CREATE TABLE IF NOT EXISTS report_dump_queue (
  id TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  next_attempt_at TEXT NOT NULL,
  last_attempt_at TEXT NOT NULL DEFAULT '',
  last_error TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS report_dump_queue_next_attempt_idx
ON report_dump_queue(next_attempt_at ASC, created_at ASC);

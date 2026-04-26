CREATE TABLE IF NOT EXISTS train_instances (
  id TEXT PRIMARY KEY,
  service_date TEXT NOT NULL,
  from_station TEXT NOT NULL,
  to_station TEXT NOT NULL,
  departure_at TEXT NOT NULL,
  arrival_at TEXT NOT NULL,
  source_version TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_train_instances_date_dep
  ON train_instances(service_date, departure_at);

CREATE TABLE IF NOT EXISTS checkins (
  user_id INTEGER PRIMARY KEY,
  train_instance_id TEXT NOT NULL,
  checked_in_at TEXT NOT NULL,
  auto_checkout_at TEXT NOT NULL,
  muted_until TEXT,
  is_active INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY(train_instance_id) REFERENCES train_instances(id)
);

CREATE INDEX IF NOT EXISTS idx_checkins_train_active
  ON checkins(train_instance_id, is_active);

CREATE TABLE IF NOT EXISTS subscriptions (
  user_id INTEGER NOT NULL,
  train_instance_id TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  is_active INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY(user_id, train_instance_id),
  FOREIGN KEY(train_instance_id) REFERENCES train_instances(id)
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_train_active
  ON subscriptions(train_instance_id, is_active);

CREATE TABLE IF NOT EXISTS report_events (
  id TEXT PRIMARY KEY,
  train_instance_id TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  signal TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(train_instance_id) REFERENCES train_instances(id)
);

CREATE INDEX IF NOT EXISTS idx_report_events_train_time
  ON report_events(train_instance_id, created_at);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id INTEGER PRIMARY KEY,
  alerts_enabled INTEGER NOT NULL DEFAULT 1,
  alert_style TEXT NOT NULL DEFAULT 'DETAILED',
  language TEXT NOT NULL DEFAULT 'EN',
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS daily_metrics (
  metric_date TEXT NOT NULL,
  key TEXT NOT NULL,
  value INTEGER NOT NULL,
  PRIMARY KEY(metric_date, key)
);

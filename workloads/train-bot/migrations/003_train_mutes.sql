CREATE TABLE IF NOT EXISTS train_mutes (
  user_id INTEGER NOT NULL,
  train_instance_id TEXT NOT NULL,
  muted_until TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, train_instance_id),
  FOREIGN KEY(train_instance_id) REFERENCES train_instances(id)
);

CREATE INDEX IF NOT EXISTS idx_train_mutes_train_until
  ON train_mutes(train_instance_id, muted_until);

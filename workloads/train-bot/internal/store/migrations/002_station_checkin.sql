ALTER TABLE checkins ADD COLUMN boarding_station_id TEXT;

CREATE TABLE IF NOT EXISTS stations (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  normalized_key TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS train_stops (
  train_instance_id TEXT NOT NULL,
  station_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  arrival_at TEXT,
  departure_at TEXT,
  PRIMARY KEY (train_instance_id, station_id),
  FOREIGN KEY(train_instance_id) REFERENCES train_instances(id),
  FOREIGN KEY(station_id) REFERENCES stations(id)
);

CREATE INDEX IF NOT EXISTS idx_train_stops_station_seq
  ON train_stops(station_id, seq);

CREATE INDEX IF NOT EXISTS idx_train_stops_train_seq
  ON train_stops(train_instance_id, seq);

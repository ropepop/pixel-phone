ALTER TABLE stations ADD COLUMN latitude REAL;
ALTER TABLE stations ADD COLUMN longitude REAL;

CREATE TABLE IF NOT EXISTS station_sighting_events (
  id TEXT PRIMARY KEY,
  station_id TEXT NOT NULL,
  destination_station_id TEXT,
  matched_train_instance_id TEXT,
  user_id INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(station_id) REFERENCES stations(id),
  FOREIGN KEY(destination_station_id) REFERENCES stations(id),
  FOREIGN KEY(matched_train_instance_id) REFERENCES train_instances(id)
);

CREATE INDEX IF NOT EXISTS idx_station_sighting_station_time
  ON station_sighting_events(station_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_station_sighting_train_time
  ON station_sighting_events(matched_train_instance_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_station_sighting_user_scope_time
  ON station_sighting_events(user_id, station_id, destination_station_id, created_at DESC);

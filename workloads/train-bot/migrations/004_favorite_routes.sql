CREATE TABLE IF NOT EXISTS favorite_routes (
  user_id INTEGER NOT NULL,
  from_station_id TEXT NOT NULL,
  to_station_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, from_station_id, to_station_id),
  FOREIGN KEY(from_station_id) REFERENCES stations(id),
  FOREIGN KEY(to_station_id) REFERENCES stations(id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_routes_user
  ON favorite_routes(user_id, created_at DESC);

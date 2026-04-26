CREATE TABLE IF NOT EXISTS stop_sightings (
  id TEXT PRIMARY KEY,
  stop_id TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS stop_sightings_user_scope_idx
ON stop_sightings(user_id, stop_id, created_at DESC);

CREATE INDEX IF NOT EXISTS stop_sightings_visible_idx
ON stop_sightings(stop_id, created_at DESC);

CREATE TABLE IF NOT EXISTS vehicle_sightings (
  id TEXT PRIMARY KEY,
  stop_id TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  mode TEXT NOT NULL,
  route_label TEXT NOT NULL,
  direction TEXT NOT NULL,
  destination TEXT NOT NULL,
  departure_seconds INTEGER NOT NULL DEFAULT 0,
  live_row_id TEXT NOT NULL DEFAULT '',
  scope_key TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS vehicle_sightings_user_scope_idx
ON vehicle_sightings(user_id, scope_key, created_at DESC);

CREATE INDEX IF NOT EXISTS vehicle_sightings_visible_idx
ON vehicle_sightings(stop_id, created_at DESC);

PRAGMA foreign_keys=off;

ALTER TABLE train_stops RENAME TO train_stops_old;

CREATE TABLE train_stops (
  train_instance_id TEXT NOT NULL,
  station_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  arrival_at TEXT,
  departure_at TEXT,
  PRIMARY KEY (train_instance_id, seq),
  FOREIGN KEY(train_instance_id) REFERENCES train_instances(id),
  FOREIGN KEY(station_id) REFERENCES stations(id)
);

INSERT INTO train_stops(train_instance_id, station_id, seq, arrival_at, departure_at)
SELECT train_instance_id, station_id, seq, arrival_at, departure_at
FROM train_stops_old;

DROP TABLE train_stops_old;

CREATE INDEX IF NOT EXISTS idx_train_stops_station_seq
  ON train_stops(station_id, seq);

CREATE INDEX IF NOT EXISTS idx_train_stops_train_seq
  ON train_stops(train_instance_id, seq);

PRAGMA foreign_keys=on;

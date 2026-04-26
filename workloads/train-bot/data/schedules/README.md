Place daily snapshots in this directory using the format:

data/schedules/YYYY-MM-DD.json

Schema:
{
  "source_version": "snapshot-2026-02-25",
  "trains": [
    {
      "id": "2026-02-25-riga-jelgava-0800",
      "service_date": "2026-02-25",
      "from_station": "Riga",
      "to_station": "Jelgava",
      "departure_at": "2026-02-25T08:00:00+02:00",
      "arrival_at": "2026-02-25T08:45:00+02:00",
      "stops": [
        {"station_name":"Riga","seq":1,"departure_at":"2026-02-25T08:00:00+02:00"},
        {"station_name":"Jelgava","seq":2,"arrival_at":"2026-02-25T08:45:00+02:00"}
      ]
    }
  ]
}

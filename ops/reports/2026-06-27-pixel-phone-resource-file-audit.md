# Pixel Phone Resource And File Audit - 2026-06-27

Evidence directory: `ops/evidence/pixel-phone-resource-file-audit-20260627T052642Z/`

## Scope

- Access path: SSH routed access to the Pixel over `100.76.50.43`, using a temporary key-only Dropbear listener on port `22234`.
- Resource poll: 60 samples at 5-second intervals, approximately 5 minutes total.
- File-content evaluation: on-phone SHA-256 read pass over persistent Android roots:
  `/system`, `/system_ext`, `/vendor`, `/product`, `/odm`, `/apex`, `/metadata`, `/data`, `/storage/emulated/0`, `/mnt/vendor`, and `/persist` when present.

## Resource Results

- Average CPU busy: `30.77%`
- CPU busy range: `16.56%` to `51.99%`
- Average 1-minute load: `3.13`
- 1-minute load range: `2.33` to `3.90`
- Average available RAM: `2721.6 MiB`
- Average free swap: `653.3 MiB`
- Disk usage during the poll:
  - `/`: `100%`
  - `/data`: `50%`
  - `/storage/emulated/0`: `50%`

Raw poll: `ops/evidence/pixel-phone-resource-file-audit-20260627T052642Z/poll_5m_corrected.csv`

## File-Content Results

- Real file content hashes recorded: `305292`
- Raw hash lines recorded: `305294`
- Difference: two placeholder hashes were produced for empty roots by `xargs` when there were no files. They are ignored for the real file count.
- Hash pass start: `20260627T052035Z`
- Hash pass end: `20260627T052642Z`
- Reported read/hash errors: `20`

Root counts from the phone-side pass:

| Root | Files Found | Bytes Reported |
| --- | ---: | ---: |
| `/system` | 2416 | 1455392099 |
| `/system_ext` | 259 | 568197765 |
| `/vendor` | 2717 | 788883954 |
| `/product` | 970 | 703513210 |
| `/odm` | 0 | 0 |
| `/apex` | 1 | 14770 |
| `/metadata` | 242 | 227141 |
| `/data` | 298358 | 627749317 |
| `/storage/emulated/0` | 324 | 146806624 |
| `/mnt/vendor` | 0 | 0 |
| `/persist` | missing | missing |

Raw file evidence:

- `ops/evidence/pixel-phone-resource-file-audit-20260627T052642Z/file_content_hash_summary.txt`
- `ops/evidence/pixel-phone-resource-file-audit-20260627T052642Z/file_content_hash_errors.log`
- `ops/evidence/pixel-phone-resource-file-audit-20260627T052642Z/file_content_hashes.sha256`

## Notes

- The temporary SSH route remained reachable throughout collection.
- The four file-content hash errors were disappearing cache/database files; the other error lines came from byte-total estimation using `find -exec ls`, which hit Android argument limits. The content hash pass itself still completed with the hash file above.
- No stable Pixel architecture was changed.

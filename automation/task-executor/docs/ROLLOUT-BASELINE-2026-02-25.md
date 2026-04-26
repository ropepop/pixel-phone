# Drain Runtime Rollout Baseline (2026-02-25)

Pre-change baseline captured on **2026-02-25** before adaptive runtime rollout.

- Repository: `ropepop/task-executor`
- Source: GitHub Actions API workflow runs
- Observation window: last 24 hours before capture (`cutoff=2026-02-24T18:24:55Z`)

## Workflow reliability snapshot

| Workflow | Total runs | Completed | Success | Failure | Cancelled | In progress | Failure+Cancelled Rate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `stalled-runner-cron.yml` | 70 | 70 | 67 | 1 | 2 | 0 | 4.29% |
| `cache-refresh-cron.yml` | 26 | 26 | 23 | 3 | 0 | 0 | 11.54% |

## Duration snapshot (seconds)

| Workflow | Min | P50 | P95 | Max | Avg |
| --- | ---: | ---: | ---: | ---: | ---: |
| `stalled-runner-cron.yml` | 9 | 33 | 64 | 70 | 30 |
| `cache-refresh-cron.yml` | 12 | 18 | 23 | 26 | 18 |

## Post-rollout comparison targets

- Drain (`stalled-runner-cron.yml`) P50 duration in the **60-100s** band when backlog exists.
- At least **80%** of backlog-present runs in the **60-120s** duration band.
- Failure+cancelled rate increase no more than **2 percentage points** vs this baseline.

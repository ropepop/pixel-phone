# `/links` Strict Drain Follow-up Spec

This repository can enforce orchestration-level strict mode, but true single-item execution guarantees require endpoint changes in the private `/links` repository.

## Required Endpoint Changes

1. Add strict single-item claim/lease semantics to `POST /api/internal/pipeline/operations/actions/drain`.
   - One request must process at most one operation item when strict-mode hints are present.
   - Enforce `attemptedCount <= 1` and `succeededCount <= 1` in endpoint response generation.

2. Persist per-item retry state.
   - Track retry attempts and `nextEligibleAt` on the specific queued item.
   - Prevent immediate reprocessing of the same rate-limited item until eligibility window is reached.

3. Honor optional strict-mode hints sent by task-executor.
   - `X-Cron-Processing-Mode: single-item-strict`
   - `X-Cron-Max-Items: 1`
   - `X-Cron-Backoff-Strategy: exponential-jitter`
   - Endpoint should remain backward compatible if headers are absent.

4. Return traceable item-level metadata in response.
   - Add optional `currentItemId`.
   - Add optional `itemAttemptState` (for example: `claimed`, `executed`, `rate_limited`, `deferred`).
   - Keep existing high-level counters for compatibility.

5. Trim non-essential payload fields in strict mode.
   - Return only data required by orchestration decisions and observability.
   - Avoid expensive or verbose response construction that does not influence control flow.

## Suggested Acceptance Criteria

1. With strict-mode headers enabled, one request never executes more than one queue item.
2. A rate-limited item is retried only after its `nextEligibleAt` threshold.
3. Response always includes stable counters plus optional item-level identifiers.
4. Existing non-strict callers remain compatible without schema breaks.

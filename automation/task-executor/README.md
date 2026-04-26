# App Cron Jobs

Public repository for App's scheduled cron jobs, designed to utilize GitHub Actions free minutes.

## Overview

This repository contains GitHub Actions workflows that trigger scheduled maintenance tasks for the App application. All business logic, API endpoints, and sensitive operations remain in the private App repository.

## Cron Jobs

### 1. Operation Queue Drain

**Schedule**: Every 6 minutes (at 2, 8, 14, 20, ..., 56 minutes past the hour)

**Purpose**: Processes stalled operations in the queue to ensure reliable execution of background tasks.

**Endpoint**: `POST /api/internal/pipeline/operations/actions/drain`

**What it does**:

- Identifies operations that have been stalled for >45 seconds
- Uses a strict single-item loop so each successful iteration advances one item at a time
- Uses adaptive request timeout windows (bounded by remaining run budget)
- Enforces single-item contract checks (`attemptedCount <= 1`, `succeededCount <= 1`) and stops safely on violations
- Keeps continuation via generation chaining when backlog remains (`gen1 -> gen2 -> gen3 -> gen4`)
- Returns before/after snapshots of the queue state
- Sends trace/runtime headers (`X-Cron-Source`, `X-Cron-Run-Id`, `X-Cron-Event`, `X-Cron-Chain-Depth`, `X-Cron-Runtime-Tier`, `X-Cron-Target-Runtime-Sec`) plus strict-mode hints (`X-Cron-Processing-Mode`, `X-Cron-Max-Items`, `X-Cron-Backoff-Strategy`) with each drain request
- Uses `succeededCount`, `rateLimitedCount`/`rateLimitOnly`, and backlog snapshots for decisions
- Uses exponential+jitter backoff on rate-limited-only iterations (defaults: base `2s`, cap `300s`, jitter `25%`, max retries `7`)
- When provided, uses `sleep = max(retryAfterSec, computedBackoff)`
- Stops gracefully on `rate_limit_retry_cap_reached` or `rate_limit_budget_exhausted` (no chain storming)
- Never dispatches more than one follow-up worker per run
- Dispatch continuation order is fixed: `gen1 -> gen2 -> gen3 -> gen4` (terminal, no `gen5`)
- Uses workflow concurrency locking so overlapping drain workers on the same ref do not run in parallel

### 2. Cache Refresh

**Schedule**: Every 10 minutes (at 4, 14, 24, 34, 44, 54 minutes past the hour)

**Purpose**: Refreshes Supabase read models by syncing data from Notion databases.

**Endpoint**: `POST /api/internal/cache/refresh`

**What it does**:

- Fetches latest link and tree data from Notion
- Updates Supabase cache tables
- Ensures read models stay synchronized with source data

## Setup Instructions

### Required Secrets

Configure the following secrets in your GitHub repository settings:

1. **`STALLED_RUNNER_BASE_URL`** (Required)

   - The base URL of your App deployment
   - Example: `https://your-domain.com`
   - Used to construct the full endpoint URLs for cron jobs

2. **`CRON_SECRET`** (Required)
   - A secure, randomly-generated token for authenticating cron requests
   - Must match the `CRON_SECRET` environment variable in your deployment
   - Example generation: `openssl rand -hex 32`
   - This secret is validated by the API endpoints before processing requests

### Configuring Secrets

1. Navigate to your GitHub repository
2. Go to **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add both `STALLED_RUNNER_BASE_URL` and `CRON_SECRET`
5. Save the secrets

### Manual Execution

Workflows support manual triggering for testing:

1. Go to the **Actions** tab in your GitHub repository
2. Select the workflow you want to run
3. Click **Run workflow**
4. Choose the branch (if applicable)
5. Click **Run workflow**

For **Operation Queue Drain**, optional manual `workflow_dispatch` inputs are available:
- `chain_depth` (default: `0`)
- `chain_origin` (default: `gen1`; legacy `firstrun..fourthrun` is accepted)
- `run_budget_minutes` (default: `100`)
- `max_request_timeout_sec` (default: `1800`)
- `max_iterations` (default: `60`)
- `min_iterations_before_chain` (legacy compatibility input; default: `2`)
- `max_chain_depth` (default: `500`, hard-capped to `1000`)
- `max_dispatch_depth` (default: `3`, must be `<= max_chain_depth`)
- `backoff_base_sec` (default: `2`)
- `backoff_cap_sec` (default: `300`)
- `backoff_max_retries` (default: `7`)
- `backoff_jitter_pct` (default: `25`)

These inputs are primarily for continuity testing/debugging; normal manual runs can use defaults.

For deep chain experiments, use the manual workflow **Operation Queue Drain Nested Playground** (`.github/workflows/stalled-runner-nested-playground.yml`), which defaults to `max_chain_depth=1000` and `max_dispatch_depth=999`.

## Security

### What's Public

- Workflow configuration files (YAML)
- This documentation
- Execution logs (excluding secrets)

### What Remains Private

All sensitive code stays in the private App repository:

- ✅ API endpoint implementations
- ✅ Database operations and schema
- ✅ Business logic and algorithms
- ✅ Authentication and authorization logic
- ✅ Notion integration code
- ✅ Supabase connection details

### Security Measures

1. **Bearer Token Authentication**: All cron endpoints require a valid `CRON_SECRET` in the `Authorization` header
2. **Secret Masking**: GitHub Actions automatically masks secrets in logs
3. **Timeout Protection**: Drain workflow has a 120-minute max run duration with internal budget controls; other workflows keep 15-minute limits
4. **HTTP Validation**: Endpoints validate request methods and reject unauthorized access

## Monitoring

### Checking Workflow Status

1. Go to the **Actions** tab to view recent runs
2. Click on a workflow run to see detailed logs
3. Check the output for status codes and response data

### Expected Response Codes

- **200 OK**: Cron job executed successfully
- **4xx Client Error**: Check your secret configuration
- **5xx Server Error**: Check your deployment logs

### Troubleshooting

**Workflow fails with "Missing required GitHub secret"**

- Verify secrets are configured in repository settings
- Ensure secret names match exactly (case-sensitive)

**Endpoint returns 401 Unauthorized**

- Verify `CRON_SECRET` matches between GitHub and your deployment
- Check that the secret is properly formatted (no extra whitespace)

**Endpoint returns 5xx errors**

- Check your deployment logs for application errors
- Verify your deployment is healthy and accessible
- Ensure the `STALLED_RUNNER_BASE_URL` is correct

## Architecture

```
┌─────────────────────┐      ┌──────────────────┐      ┌─────────────────┐
│  GitHub Actions     │      │  App             │      │  Notion API     │
│  (Public Repo)      │─────▶│  Deployment      │─────▶│                 │
│                     │      │  (Private)       │      │                 │
│  - Cron Schedule    │      │                  │      │                 │
│  - HTTP Request     │      │  - Auth Valid.   │      │  - Link Data    │
│  - Secret Mgmt      │      │  - Business Log. │      │  - Tree Data    │
└─────────────────────┘      └──────────────────┘      └─────────────────┘
         │                            │
         │                            ▼
         │                     ┌──────────────────┐
         │                     │  Supabase        │
         │                     │  (Cache Tables)  │
         │                     └──────────────────┘
         │
         ▼
┌─────────────────────┐
│  GitHub Secrets     │
│                     │
│  - CRON_SECRET      │
│  - BASE_URL         │
└─────────────────────┘
```

## License

This repository is part of the App project. All rights reserved.

## Support

For issues or questions, please refer to the private App repository documentation or contact the development team.

Private endpoint follow-up spec for strict single-item execution:
- [`docs/LINKS-STRICT-DRAIN-FOLLOWUP.md`](docs/LINKS-STRICT-DRAIN-FOLLOWUP.md)

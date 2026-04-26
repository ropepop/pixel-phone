# Cron Jobs Setup Guide

This document provides detailed instructions for setting up and maintaining the cron jobs.

## Quick Start

1. Configure required secrets in GitHub repository settings
2. Operation Queue Drain runs automatically on schedule and self-chains when needed
3. Monitor execution in the Actions tab

## Detailed Setup

### Step 1: Generate CRON_SECRET

Generate a secure random token for authentication:

```bash
# Using openssl
openssl rand -hex 32

# Using Node.js
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"

# Using Python
python3 -c "import secrets; print(secrets.token_hex(32))"
```

Save the generated token securely.

### Step 2: Configure GitHub Secrets

1. Navigate to your GitHub repository
2. Go to **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add the following secrets:

| Secret Name               | Value                     | Description                             |
| ------------------------- | ------------------------- | --------------------------------------- |
| `STALLED_RUNNER_BASE_URL` | `https://your-domain.com` | Base URL of your App deployment         |
| `CRON_SECRET`             | (generated token)         | Authentication token for cron endpoints |

### Step 3: Verify Deployment Configuration

Ensure your App deployment has the `CRON_SECRET` environment variable configured:

**Vercel:**

```bash
vercel env add CRON_SECRET
```

**Other platforms:**
Set the environment variable according to your platform's documentation.

### Step 4: Test the Workflows

1. Go to **Actions** tab
2. Select **Operation Queue Drain** workflow
3. Click **Run workflow**
   - Optional inputs:
     - `chain_depth` (default `0`)
     - `chain_origin` (default `gen1`; legacy `firstrun..fourthrun` accepted)
     - `run_budget_minutes` (default `100`)
     - `max_request_timeout_sec` (default `1800`)
     - `max_iterations` (default `60`)
     - `min_iterations_before_chain` (legacy compatibility input; default `2`)
     - `backoff_base_sec` (default `2`)
     - `backoff_cap_sec` (default `300`)
     - `backoff_max_retries` (default `7`)
     - `backoff_jitter_pct` (default `25`)
4. Wait for execution to complete
5. Check logs for success/failure

Repeat for **Cache Refresh** workflow.

## Maintenance

### Updating Cron Schedules

Edit the cron expression in the workflow YAML files:

```yaml
on:
  schedule:
    - cron: '2-59/6 * * * *' # Operation Queue Drain schedule
```

Cron format: `minute hour day month weekday`

Use [crontab.guru](https://crontab.guru) to validate cron expressions.

### Rotating CRON_SECRET

1. Generate a new secret token
2. Update the GitHub secret first
3. Update the deployment environment variable
4. Test affected workflows to verify authentication

### Monitoring Best Practices

1. **Check Actions regularly**: Review workflow runs in the Actions tab
2. **Set up notifications**: Configure GitHub notifications for failed workflows
3. **Monitor endpoint logs**: Check your deployment logs for cron endpoint activity
4. **Track execution times**: Ensure workflows complete within timeout limits
5. **Watch drain step summaries**: Confirm backlog/processed metrics and chaining decisions match expectations

### Operation Queue Drain Backlog Chaining

The drain workflow runs a strict single-item loop and can queue a follow-up worker (`workflow_dispatch`) when backlog remains and run budget is exhausted after a successful item transition.

Guard conditions:
- Chain only if `after.pendingCount + after.dispatchedCount > 0`
- Chain only if last transition succeeded (`succeededCount == 1`)
- Chain only if current `chain_depth < 500`
- Max one self-dispatch per run
- Chain dispatch depth cap is typically `3`, mapping to:
  - `0=gen1`
  - `1=gen2`
  - `2=gen3`
  - `3=gen4` (terminal, no further queueing)

Strict-mode behavior:
- Enforces `attemptedCount <= 1` and `succeededCount <= 1` on every iteration
- Stops safely with `single_item_contract_violation` when either counter is greater than 1
- Rate limit is detected when `rateLimitOnly == true` or (`succeededCount == 0` and `rateLimitedCount > 0`)
- Runner retries same item path with exponential+jitter backoff:
  - `delay = min(backoff_cap_sec, backoff_base_sec * 2^(retryIndex-1)) ± jitter`
  - default `backoff_base_sec=2`, `backoff_cap_sec=300`, `backoff_jitter_pct=25`, `backoff_max_retries=7`
  - if `retryAfterSec` is returned, sleep uses `max(retryAfterSec, computedDelay)`
- Stops gracefully on `rate_limit_retry_cap_reached` or `rate_limit_budget_exhausted` without dispatch

Additional behavior:
- Follow-up runs increment `chain_depth` and set `chain_origin` by depth (`gen2`, `gen3`, `gen4`)
- If chaining was required by guard conditions but workflow self-dispatch fails (non-2xx), the job fails
- No extra repository secrets are required; chaining uses the workflow `GITHUB_TOKEN`
- Drain requests include trace/runtime headers and strict-mode hints:
  - `X-Cron-Source`, `X-Cron-Run-Id`, `X-Cron-Event`, `X-Cron-Chain-Depth`, `X-Cron-Runtime-Tier`, `X-Cron-Target-Runtime-Sec`
  - `X-Cron-Processing-Mode`, `X-Cron-Max-Items`, `X-Cron-Backoff-Strategy`
- Step summary includes: `decision_code`, `decision_reason`, `iteration_count`, `run_budget_sec`, `remaining_budget_sec`, `chain_action`

## Troubleshooting

### Common Issues

#### 1. "Missing required GitHub secret"

**Cause**: Secret not configured or misspelled

**Solution**:

- Verify secret names match exactly (case-sensitive)
- Check that secrets are configured at the repository level
- Ensure no extra whitespace in secret values

#### 2. "401 Unauthorized"

**Cause**: CRON_SECRET mismatch

**Solution**:

- Verify GitHub secret matches deployment environment variable
- Regenerate the secret and update both locations
- Check for encoding issues (should be plain hex string)

#### 3. "Connection timeout"

**Cause**: Deployment unreachable

**Solution**:

- Verify `STALLED_RUNNER_BASE_URL` is correct
- Check deployment is running and accessible
- Test URL manually: `curl https://your-domain.com/api/internal/pipeline/operations/actions/drain`

#### 4. "500 Internal Server Error"

**Cause**: Application error in endpoint

**Solution**:

- Check deployment logs for stack traces
- Verify database connections are healthy
- Check Notion API status (for cache refresh)

### Debug Mode

To enable more verbose logging, temporarily add a debug step to workflows:

```yaml
- name: Debug secrets (remove after testing)
  run: |
    echo "Base URL configured: ${{ secrets.STALLED_RUNNER_BASE_URL != '' }}"
    echo "Cron secret configured: ${{ secrets.CRON_SECRET != '' }}"
```

**Important**: Remove debug steps after troubleshooting to avoid exposing information.

## Performance Considerations

### Timeout Settings

Current timeouts are configured for safety:

- Drain workflow timeout: 120 minutes
- Other workflow timeouts: 15 minutes
- Drain request timeout: adaptive per iteration (bounded by `max_request_timeout_sec`, default 1800s)
- Drain self-dispatch request (`curl --max-time`): 60 seconds
- Refresh request (`curl --max-time`): 120 seconds

Adjust based on your data volume and processing needs.

### Rate Limiting

If you encounter rate limits:

1. Increase interval between cron runs
2. Ensure endpoint single-item mode is enabled and honoring `X-Cron-Max-Items: 1`
3. Tune workflow backoff inputs:
   - `backoff_base_sec`
   - `backoff_cap_sec`
   - `backoff_max_retries`
   - `backoff_jitter_pct`

### Concurrent Execution

The drain workflow is configured with a concurrency group per ref, so overlapping drain workers on the same ref do not run in parallel. Cache refresh may still run independently.

## Security Checklist

- [ ] CRON_SECRET is a strong random token (64+ characters)
- [ ] Secrets are configured in GitHub repository settings
- [ ] Deployment validates CRON_SECRET on every request
- [ ] Workflow logs don't expose sensitive information
- [ ] Base URL uses HTTPS
- [ ] Regular secret rotation schedule is established

## Migration from Private Repository

If migrating from the private repository:

1. **Deploy public repository workflows first**

   - Keep private workflows active
   - Configure secrets in public repo
   - Test both run in parallel

2. **Verify public workflows execute successfully**

   - Monitor several scheduled runs
   - Check endpoint logs for expected behavior
   - Verify no errors or failures

3. **Disable private repository workflows**

   - Delete or comment out cron schedules in private repo
   - Keep workflow files as backup if desired
   - Update documentation to reference public repo

4. **Monitor transition**
   - Watch for any gaps in cron execution
   - Verify all operations continue normally
   - Update team documentation

## Support Resources

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Cron Syntax Reference](https://crontab.guru)
- [GitHub Secrets Guide](https://docs.github.com/en/actions/security-guides/encrypted-secrets)

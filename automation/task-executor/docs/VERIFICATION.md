# Cron Jobs Verification Guide

This guide helps you verify that the cron jobs are functioning correctly in the new repository.

## Current Status

✅ **Workflows uploaded** to https://github.com/ropepop/task-executor
✅ **Original workflows removed** from links repository
⏳ **Awaiting activation** - GitHub Actions needs to be enabled
⏳ **Awaiting secrets** - CRON_SECRET and STALLED_RUNNER_BASE_URL need to be configured

## Verification Steps

### Step 1: Enable GitHub Actions

1. Go to: https://github.com/ropepop/task-executor/actions
2. You should see a green button: **"I understand my workflows, go ahead and enable them"**
3. Click the button to enable Actions

**Expected Result:**

- Workflows become active
- "Enable" button disappears
- Workflows show in the Actions tab

### Step 2: Configure Secrets

1. Go to: https://github.com/ropepop/task-executor/settings/secrets/actions/new
2. Add the following secrets:

| Secret Name               | Value                                                              |
| ------------------------- | ------------------------------------------------------------------ |
| `STALLED_RUNNER_BASE_URL` | `https://links.jolkins.id.lv`                                      |
| `CRON_SECRET`             | `T3lnYVQ0U_XWhTw1DDQmoGNRWXs_i58g0VqMIT-TI4SLU5Ui_kKfiHWEgMh-UNpm` |

**Expected Result:**

- Both secrets appear in the secrets list
- No errors during creation

### Step 3: Manual Test Run

#### Test Operation Queue Drain:

1. Go to: https://github.com/ropepop/task-executor/actions/workflows/stalled-runner-cron.yml
2. Click **"Run workflow"** button
3. Select branch: `main`
4. Click **"Run workflow"**
5. Wait for the run to complete (~1-2 minutes, tier-dependent)
6. Click on the run to view logs

**Expected Logs:**

```
✓ Validate required secrets
  - No errors about missing secrets
✓ Drain operation queue
  - "Drain status: 200" or "Drain status: 201"
  - JSON response from endpoint
  - Runtime lines: "Runtime request", "Runtime applied", "Runtime wall-clock"
  - "Operation queue drain failed" should NOT appear
```

**Success Criteria:**

- ✅ Green checkmark ✓
- ✅ HTTP status code 200 or 201
- ✅ No authentication errors
- ✅ Completion time matches runtime tier target:
  - `chain_depth 0-2`: ~60s (+ runner overhead)
  - `chain_depth 3`: ~90s (+ runner overhead)

#### Runtime tier spot checks:

Run additional manual dispatch tests to validate case-by-case runtime enforcement:
1. `chain_depth=0` -> expect ~60s
2. `chain_depth=1` -> expect ~60s
3. `chain_depth=2` -> expect ~60s
4. `chain_depth=3` -> expect ~90s and no further queueing (terminal `fourthrun`)

#### Test Cache Refresh:

1. Go to: https://github.com/ropepop/task-executor/actions/workflows/cache-refresh-cron.yml
2. Click **"Run workflow"** button
3. Select branch: `main`
4. Click **"Run workflow"**
5. Wait for the run to complete (~60-90 seconds)
6. Click on the run to view logs

**Expected Logs:**

```
✓ Validate required secrets
  - No errors about missing secrets
✓ Refresh read models from Notion
  - "Refresh status: 200" or "Refresh status: 201"
  - JSON response with counts
  - "Cache refresh failed" should NOT appear
```

**Success Criteria:**

- ✅ Green checkmark ✓
- ✅ HTTP status code 200 or 201
- ✅ No authentication errors
- ✅ Completion time < 2 minutes

### Step 4: Verify Scheduled Execution

After enabling and configuring:

1. Wait for the next scheduled run:

   - **Operation Queue Drain**: At 2, 8, 14, 20... minutes past the hour
   - **Cache Refresh**: At 4, 14, 24, 34... minutes past the hour

2. Go to: https://github.com/ropepop/task-executor/actions
3. Check that workflows trigger automatically

**Expected Result:**

- New runs appear automatically at scheduled times
- Runs complete successfully with green checkmarks
- No manual intervention required

### Step 5: Monitor Execution History

After 24 hours, check:

1. Go to: https://github.com/ropepop/task-executor/actions
2. Review the execution history

**Expected Statistics (per 24 hours):**

- **Operation Queue Drain**: ~240 runs (every 6 minutes)
- **Cache Refresh**: ~144 runs (every 10 minutes)
- **Success rate**: Should be >95%

## Troubleshooting

### Issue: "This workflow has no runs yet"

**Cause**: GitHub Actions not enabled or secrets not configured

**Solution**:

1. Enable Actions (Step 1)
2. Add secrets (Step 2)
3. Manually trigger workflows (Step 3)

### Issue: "Missing required GitHub secret"

**Cause**: Secrets not configured or misspelled

**Solution**:

1. Go to Settings → Secrets and variables → Actions
2. Verify both secrets exist:
   - `STALLED_RUNNER_BASE_URL`
   - `CRON_SECRET`
3. Check spelling (case-sensitive!)
4. Re-add secrets if needed

### Issue: "401 Unauthorized"

**Cause**: CRON_SECRET mismatch between GitHub and deployment

**Solution**:

1. Verify the CRON_SECRET in GitHub matches `.env.local`:
   ```bash
   # Check your local .env.local
   grep CRON_SECRET .env.local
   ```
2. Update GitHub secret if different
3. Wait 1-2 minutes for changes to propagate
4. Retry workflow

### Issue: "Connection timeout" or "Could not resolve host"

**Cause**: Deployment URL is unreachable

**Solution**:

1. Verify `STALLED_RUNNER_BASE_URL` is correct
2. Test URL manually:
   ```bash
   curl -I https://links.jolkins.id.lv
   ```
3. Check deployment is running (Vercel, etc.)
4. Verify DNS is resolving correctly

### Issue: "500 Internal Server Error"

**Cause**: Application error in the endpoint

**Solution**:

1. Check deployment logs (Vercel, etc.)
2. Look for errors around the workflow run time
3. Verify database connections (Supabase)
4. Check Notion API status (for cache refresh)
5. Review application logs for stack traces

### Issue: Workflows not running on schedule

**Cause**: GitHub Actions disabled or repository inactive

**Solution**:

1. Verify Actions is enabled (Step 1)
2. Check repository has recent activity
3. Ensure workflows are not disabled in Settings
4. Manually trigger to "wake up" the repository

## Verification Checklist

Use this checklist to track verification progress:

### Setup

- [ ] GitHub Actions enabled
- [ ] STALLED_RUNNER_BASE_URL secret configured
- [ ] CRON_SECRET secret configured
- [ ] Both workflows visible in Actions tab

### Manual Testing

- [ ] Operation Queue Drain manual run successful
- [ ] Cache Refresh manual run successful
- [ ] Both workflows return HTTP 200/201
- [ ] No authentication errors in logs
- [ ] Completion times are acceptable

### Automated Testing

- [ ] First scheduled run executed automatically
- [ ] Scheduled runs continue without issues
- [ ] Success rate is >95% over 24 hours
- [ ] No missed scheduled runs

### Cleanup Verification

- [ ] Original workflows removed from links repository
- [ ] Git history shows deletion commit
- [ ] No duplicate workflows running

## Monitoring Best Practices

### Daily Checks

- Review Actions tab for any failures
- Check success rate percentage
- Monitor execution times

### Weekly Checks

- Review total execution count
- Check for any pattern in failures
- Verify secrets haven't expired

### Monthly Checks

- Rotate CRON_SECRET (security best practice)
- Review and optimize cron schedules if needed
- Update documentation if processes changed

## Success Metrics

After 48 hours of operation, you should see:

| Metric | Expected Value |
|--------|----------------|
| Operation Queue Drain runs/day | ~240 |
| Cache Refresh runs/day | ~144 |
| Success rate | >95% |
| Average execution time (drain) | 60-120 seconds (tier-dependent) |
| Average execution time (refresh) | <90 seconds |
| Authentication failures | 0 |
| Connection timeouts | <1% |

## Next Steps After Verification

Once verified working:

1. **Update team documentation**

   - Note new repository location
   - Share verification results
   - Update runbooks

2. **Set up monitoring alerts** (optional)

   - GitHub notifications for failures
   - External monitoring service
   - Slack/Discord webhooks

3. **Consider optimizations**
   - Adjust cron schedules if needed
   - Fine-tune timeouts
   - Add retry logic if necessary

## Support

If issues persist after troubleshooting:

- Check GitHub Status: https://www.githubstatus.com/
- Review GitHub Actions documentation
- Contact repository maintainers

---

**Last Updated**: February 18, 2026
**Repository**: https://github.com/ropepop/task-executor

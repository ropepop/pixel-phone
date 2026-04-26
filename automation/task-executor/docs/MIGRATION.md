# Cron Jobs Migration Checklist

Use this checklist when migrating cron jobs from the private repository to this public repository.

## Pre-Migration

### Repository Setup

- [ ] Create new public GitHub repository
- [ ] Initialize with README.md
- [ ] Add .gitignore file
- [ ] Create .github/workflows directory
- [ ] Create docs directory

### Files to Migrate

- [ ] Copy `stalled-runner-cron.yml` workflow
- [ ] Copy `cache-refresh-cron.yml` workflow
- [ ] Create SETUP.md documentation
- [ ] Create SECURITY.md documentation
- [ ] Create MIGRATION.md (this file)

### Secret Preparation

- [ ] Verify CRON_SECRET is configured in private repo
- [ ] Verify STALLED_RUNNER_BASE_URL is configured
- [ ] Document secret requirements
- [ ] Plan secret rotation if needed

## Migration Steps

### Phase 1: Parallel Deployment (Recommended)

1. **Configure Public Repository**

   - [ ] Push workflow files to public repo
   - [ ] Configure secrets in public repo settings
   - [ ] Verify secret names match exactly
   - [ ] Enable GitHub Actions (if disabled)

2. **Test Public Repository Workflows**

   - [ ] Manually trigger "Operation Queue Drain"
   - [ ] Check execution logs
   - [ ] Verify endpoint received request
   - [ ] Confirm successful operation
   - [ ] Manually trigger "Cache Refresh"
   - [ ] Check execution logs
   - [ ] Verify cache was refreshed
   - [ ] Confirm successful operation

3. **Monitor Scheduled Runs**
   - [ ] Wait for first scheduled run (drain)
   - [ ] Verify it executed on schedule
   - [ ] Check for any errors
   - [ ] Wait for first scheduled run (refresh)
   - [ ] Verify it executed on schedule
   - [ ] Check for any errors
   - [ ] Monitor for 24-48 hours
   - [ ] Confirm no missed executions

### Phase 2: Decommission Private Workflows

4. **Disable Private Repository Workflows**

   - [ ] Navigate to private repo
   - [ ] Go to Settings → Actions → General
   - [ ] Option A: Disable Actions entirely (if no other workflows)
   - [ ] Option B: Delete cron workflow files
   - [ ] Option C: Comment out cron schedules in YAML
   - [ ] Commit changes to private repo

5. **Verify Continuity**
   - [ ] Confirm public workflows still running
   - [ ] Check no gaps in execution
   - [ ] Verify operations continue normally
   - [ ] Monitor for 24-48 hours

## Post-Migration

### Documentation Updates

- [ ] Update private repo README to reference public repo
- [ ] Update team documentation
- [ ] Update runbooks/operational procedures
- [ ] Notify team of new repository location
- [ ] Update monitoring/alerting configurations

### Security Verification

- [ ] Verify no sensitive code in public repo
- [ ] Check Git history for accidental commits
- [ ] Confirm secrets are properly masked in logs
- [ ] Review repository permissions
- [ ] Set up branch protection (if desired)

### Monitoring Setup

- [ ] Configure GitHub notifications for failures
- [ ] Set up external monitoring (optional)
- [ ] Create dashboard for workflow status
- [ ] Define alerting thresholds
- [ ] Document escalation procedures

## Rollback Plan

If issues occur during migration:

1. **Immediate Rollback**

   - [ ] Re-enable private repository workflows
   - [ ] Restore deleted workflow files from Git
   - [ ] Verify private workflows are running
   - [ ] Disable public repository workflows

2. **Investigate Issues**

   - [ ] Review error logs from public workflows
   - [ ] Check secret configuration
   - [ ] Verify endpoint accessibility
   - [ ] Test authentication manually

3. **Retry Migration**
   - [ ] Fix identified issues
   - [ ] Wait for stability
   - [ ] Restart migration process
   - [ ] Monitor more closely

## Success Criteria

Migration is successful when:

- ✅ Public workflows execute on schedule
- ✅ No missed cron executions
- ✅ All operations complete successfully
- ✅ No errors in workflow logs
- ✅ Private workflows are disabled
- ✅ Team is aware of new location
- ✅ Documentation is updated
- ✅ Monitoring is in place

## Timeline

Recommended timeline:

- **Day 1**: Setup and testing
- **Day 2-3**: Parallel operation (both repos)
- **Day 4**: Decommission private workflows
- **Day 5-7**: Monitor and verify
- **Week 2**: Complete documentation updates

## Contacts

- Repository Owner: [Name]
- DevOps Lead: [Name]
- On-Call: [Contact Info]

## Notes

Add any migration-specific notes here:

---

**Migration Started**: \***\*\_\_\_\*\***

**Migration Completed**: \***\*\_\_\_\*\***

**Issues Encountered**:

- **Resolutions**:

-

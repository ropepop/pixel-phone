# Security Guidelines

This document outlines security practices for the cron jobs repository.

## What This Repository Contains

✅ **Public (Safe to Share)**

- GitHub Actions workflow YAML files
- Setup documentation
- Architecture diagrams
- Execution logs (with secrets masked)

## What Remains Private

🔒 **Private (Never Commit)**

- API endpoint implementations
- Database connection strings
- Business logic code
- Authentication algorithms
- Notion API integration code
- Supabase schema details
- Application source code

## Secret Management

### GitHub Secrets

The following secrets are required and must be configured in GitHub:

1. **`STALLED_RUNNER_BASE_URL`**

   - Type: String (URL)
   - Purpose: Base URL of the App deployment
   - Example: `https://app.example.com`
   - Sensitivity: Low (public-facing URL)

2. **`CRON_SECRET`**
   - Type: String (hex token)
   - Purpose: Bearer token for authenticating cron requests
   - Example: `a1b2c3d4e5f6...` (64 characters)
   - Sensitivity: **HIGH** (must be kept secret)

### Secret Security Best Practices

1. **Never commit secrets to Git**

   - This repository has no `.env` files
   - No secrets in workflow YAML files
   - GitHub Actions injects secrets at runtime

2. **Use strong secrets**

   - CRON_SECRET should be at least 64 characters
   - Use cryptographically secure random generation
   - Avoid predictable patterns

3. **Rotate secrets regularly**

   - Recommended: Every 90 days
   - Immediately if compromise is suspected
   - Update both GitHub and deployment simultaneously

4. **Limit secret access**
   - Only repository admins should configure secrets
   - Use GitHub Environments for production vs staging
   - Audit secret access periodically

## Authentication Flow

```
┌─────────────────┐
│ GitHub Actions  │
│                 │
│ 1. Read secrets │
│ 2. Build request│
│ 3. Add header   │
└────────┬────────┘
         │
         │ POST /api/...
         │ Authorization: Bearer <CRON_SECRET>
         ▼
┌─────────────────┐
│  App API        │
│                 │
│ 4. Validate     │
│ 5. Process      │
│ 6. Respond      │
└─────────────────┘
```

### Request Validation

All cron endpoints must validate:

- ✅ Request method is POST
- ✅ Authorization header is present
- ✅ Bearer token matches CRON_SECRET
- ✅ Request originates from expected IP range (optional)

## Network Security

### HTTPS Only

All endpoints must use HTTPS:

- Encrypts data in transit
- Prevents man-in-the-middle attacks
- Validates server identity

### IP Allowlisting (Optional)

For enhanced security, configure your deployment to accept cron requests only from GitHub Actions IP ranges:

[GitHub IP ranges](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-githubs-ip-addresses)

**Note**: GitHub Actions IP ranges change frequently. Use with caution.

## Logging and Monitoring

### What Gets Logged

GitHub Actions logs:

- ✅ Workflow execution start/end
- ✅ HTTP status codes
- ✅ Response body content (non-sensitive)
- ❌ Secrets are automatically masked

### What to Monitor

1. **Failed authentications**

   - Check deployment logs for 401 errors
   - May indicate secret mismatch or attack attempt

2. **Unusual execution patterns**

   - Unexpected workflow triggers
   - Execution outside scheduled times
   - Multiple concurrent runs

3. **Performance anomalies**
   - Increasing execution times
   - Frequent timeouts
   - Resource exhaustion

## Incident Response

### If CRON_SECRET is Compromised

1. **Generate new secret immediately**

   ```bash
   openssl rand -hex 32
   ```

2. **Update GitHub secret**

   - Go to Settings → Secrets and variables → Actions
   - Update CRON_SECRET value

3. **Update deployment**

   - Update environment variable in hosting platform
   - Restart deployment if necessary

4. **Verify both workflows**

   - Manually trigger both workflows
   - Confirm successful execution

5. **Investigate compromise**
   - Review access logs
   - Check for unauthorized access
   - Audit repository collaborators

### If Workflows are Triggered Unexpectedly

1. Check workflow run history
2. Review trigger sources (schedule vs manual)
3. Verify no unauthorized repository access
4. Consider temporarily disabling workflows
5. Rotate secrets as precaution

## Compliance Considerations

### Data Protection

- No personal data is processed by cron workflows
- No PII transmitted in requests or responses
- No data stored in GitHub (beyond execution logs)

### Access Control

- Repository visibility: Public
- Write access: Limited to maintainers
- Secret configuration: Admins only
- Workflow execution: Automated + manual (maintainers)

## Security Checklist

Before making repository public:

- [ ] No secrets committed to Git history
- [ ] No `.env` files or similar
- [ ] No hardcoded credentials in workflows
- [ ] CRON_SECRET is strong (64+ characters)
- [ ] Endpoints validate authentication
- [ ] HTTPS is enforced
- [ ] Logging doesn't expose sensitive data
- [ ] Team is trained on secret rotation

## Additional Resources

- [GitHub Actions Security Hardening](https://docs.github.com/en/actions/security-guides/security-hardening-for-github-actions)
- [OpenSSF Best Practices](https://openssf.org/)
- [CIS Benchmark for GitHub](https://www.cisecurity.org/benchmark/github)

## Contact

For security concerns or questions, contact the repository maintainers.

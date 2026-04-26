# Quick Start Guide

Get your cron jobs running in 5 minutes!

## Step 1: Configure Secrets (2 minutes)

1. Go to your GitHub repository **Settings**
2. Click **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add these two secrets:

| Name                      | Value                                          |
| ------------------------- | ---------------------------------------------- |
| `STALLED_RUNNER_BASE_URL` | Your app URL (e.g., `https://app.example.com`) |
| `CRON_SECRET`             | Random token (see below)                       |

**Generate CRON_SECRET:**

```bash
# Run this command and copy the output
openssl rand -hex 32
```

## Step 2: Verify Deployment (1 minute)

Make sure your App deployment has the same `CRON_SECRET` environment variable configured.

**Vercel:**

```bash
vercel env add CRON_SECRET
# Paste the same token you added to GitHub
```

## Step 3: Test Workflows (2 minutes)

1. Go to **Actions** tab
2. Click **Operation Queue Drain**
3. Click **Run workflow**
4. Wait 1-2 minutes (workflow enforces ~60s for depths 0-2 and ~90s for depth 3)
5. Check for ✅ green checkmark

Repeat for **Cache Refresh** workflow.

## Done! ✅

Your cron jobs will now run automatically:

- **Operation Queue Drain**: Every 6 minutes
- **Cache Refresh**: Every 10 minutes

## What's Next?

- Monitor execution in the **Actions** tab
- Read [SETUP.md](docs/SETUP.md) for detailed instructions
- Review [SECURITY.md](docs/SECURITY.md) for security best practices
- Check [MIGRATION.md](docs/MIGRATION.md) if migrating from private repo

## Troubleshooting

**Workflow failed?**

- Check that both secrets are configured correctly
- Verify your deployment is accessible
- Check workflow logs for specific error messages

**Need help?**

- See [docs/SETUP.md](docs/SETUP.md) for detailed troubleshooting
- Review [docs/SECURITY.md](docs/SECURITY.md) for security checklist

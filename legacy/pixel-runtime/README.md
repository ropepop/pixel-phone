# Legacy Pixel Runtime

This directory keeps the retired rollback-only deployment path.

Contents:
- the old rooted Pixel and orchestrator runtime tree
- the old Swarm deployment layout
- the old Pixel deployment helpers
- old runbooks that only apply to that retired runtime

Active production operations now live outside this directory:
- Docker Compose layout: `infra/arbuzas/docker/`
- active deploy entrypoint: `tools/arbuzas/deploy.sh`
- active operator docs: `docs/runbooks/ROOT_OPERATIONS.md`

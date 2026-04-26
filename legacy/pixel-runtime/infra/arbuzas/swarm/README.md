# Arbuzas Swarm Layout

This directory is the checked-in deployment layout for the Arbuzas Docker Swarm migration.

## What Lives Here

- `images/`: Dockerfiles and helper entrypoints for the Arbuzas-owned workloads and DNS sidecars.
- `stacks/`: the three Swarm stack definitions:
  - `platform.stack.yml`
  - `apps.stack.yml`
  - `dns.stack.yml`
- `env/swarm.example.env`: copy target for local operator overrides such as hostnames, ports, and third-party image pins.
- Release builds default to `linux/amd64` so an Apple Silicon operator machine still emits images Arbuzas can boot.

## Host Layout

- Persistent state: `/srv/arbuzas`
- Secrets and runtime env files: `/etc/arbuzas`
- Swarm release bundles: `/etc/arbuzas/swarm/releases/<release-id>`
- Active release symlink: `/etc/arbuzas/swarm/current`
- Generated Cloudflare stack configs: `/etc/arbuzas/swarm/cloudflared`

## Service Inventory

- `platform`: Portainer server and Portainer agent
- `apps`: Train, Satiksme, Subscription, Site Notifications, and the three Cloudflare tunnel sidecars
- `dns`: AdGuardHome core, DNS identity web, and nginx frontend

## Operator Entry Points

- Host prepare: `tools/arbuzas/swarm_host_prepare.sh`
- Build, ship, deploy, validate, rollback: `tools/arbuzas/swarm_release.sh`
- One-time big-bang cutover: `tools/arbuzas/swarm_cutover.sh`

## Notes

- Stack names are fixed to `platform`, `apps`, and `dns` so the internal service names stay stable.
- Cloudflare configs are rendered from credentials JSON files on the remote host, not checked into git.
- The DNS frontend remains compatible with the current helper scripts: localhost stays the default, while Swarm uses explicit upstream host overrides.

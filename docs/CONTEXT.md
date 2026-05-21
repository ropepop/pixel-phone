# Pixel Phone Context Guide

Use this file to pick a small Markdown context set for the Pixel phone archive and ticket/phone orchestration material.

## Always-read spine

- `../AGENTS.md`: repo-wide communication, verification, and architecture-update rules.
- `../README.md`: archive/project overview.
- This file: where to go next.

## Durable docs

- `architecture/`: stable component boundaries, runtime paths, endpoints, health fields, cleanup behavior, and safety policies.
- `runbooks/`: repeatable operator procedures.
- `reference/`: exhaustive command/config details.

## Heavy docs to avoid by default

- `../ops/reports/`: dated measurements and historical analysis. Read only the narrow report needed for the task.
- `../ops/evidence/` if present: generated proof, screenshots, logs, and browser captures.
- Large reference docs such as orchestrator config/command references should be used as lookup material, not always-read context.

## Task routing

- Ticket stream/browser/public behavior: start with `../AGENTS.md`, then the relevant `architecture/` doc and only the latest linked report if needed.
- Root orchestrator or Pixel runtime behavior: start with `runbooks/ROOT_OPERATIONS.md`, then split into architecture/reference docs as needed.
- Config questions: use `reference/` docs directly.
- Historical performance or measurement questions: use `ops/reports/` and summarize durable lessons back into architecture or runbook docs.

## Placement rules for new Markdown

- Stable architecture: `architecture/`.
- Repeatable procedure: `runbooks/`.
- Exhaustive config/API/commands: `reference/`.
- Dated measurement or analysis: `../ops/reports/YYYY-MM/`.
- Screenshots, raw logs, browser captures, or device proof: `../ops/evidence/<topic>/<timestamp>/` with a short index.

Keep root Markdown limited to `README.md`, `AGENTS.md`, license/changelog/contributing files, and deliberately current top-level notes.

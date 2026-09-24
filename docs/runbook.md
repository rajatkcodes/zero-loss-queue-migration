# Incident Runbook — Alert Delivery Pipeline

Severity mapping used by `metrics-aggregator` (`src/swym/services/metrics_aggregator.clj`,
`detect-incidents!`). These are the same P1–P4 definitions the Incidents view in the
dashboard renders next to the live feed.

## P1 — Page immediately

**Triggers:**
- Enterprise-tier merchant breach-rate > 5% over the last 60s (≥5 samples).
- The legacy path is down (chaos-induced or real).

**Why P1:** enterprise merchants have a tighter SLA (1.5s vs 5s) and are the accounts
most sensitive to delivery delay — a restock alert that arrives after a competitor's
doesn't get the click. A legacy-path outage before a merchant has been cut over means
their alerts are queued but not draining.

**Response:**
1. Check `GET /api/metrics/snapshot` for `legacy-down` and per-merchant `breach-rate`.
2. If legacy is down: confirm whether this is an induced chaos test (`POST /api/chaos/legacy {"down":false}` to restore) or a real Redis outage. Redis Lists are durable, so the backlog is not lost — it drains once the path is restored. No data loss, but SLA is actively being missed while down.
3. If it's an enterprise breach unrelated to an outage: check whether that merchant is still on `legacy` mode — if so, this is a live argument for prioritizing their cutover. Consider an emergency `dual-write` toggle to start shadowing traffic on the new path immediately.

## P2 — Investigate same day

**Triggers:**
- Standard-tier merchant breach-rate > 5%.
- Any DLQ growth for a merchant (a whole-event processing failure, not a per-recipient bounce).

**Response:**
1. `GET /api/incidents` for the merchant/queue detail.
2. DLQ entries auto-replay every 10s (`dlq-auto-replay-loop` in control-plane) using the merchant's *current* routing mode — if they've since been cut over, the replay goes out the new path. Manually trigger `POST /api/dlq/replay` if you don't want to wait for the next tick.
3. If DLQ growth is sustained (not draining), the hard-failure rate for that path is elevated — check whether the queue is under unusually high load (a single large enterprise event sharing the shared legacy worker pool with everyone else is the textbook cause pre-migration).

## P3 — Monitor

**Triggers:**
- Legacy queue depth > 500.

**Why:** backlog on the shared legacy list means downstream alerts are delayed, not lost. This is the direct, visible symptom of the "no tenant isolation" problem the migration exists to fix.

**Response:** watch whether it's draining (workers keeping pace) or growing (need more legacy workers, or accelerate cutover of the noisiest queue). Not urgent unless it's compounding with a P1.

## P4 — Informational

**Triggers:** a core queue's migration mode changed (`legacy` → `dual-write` → `new`, or a rollback).

**Response:** none required — this is the audit trail of the migration itself, useful for correlating "did SLA get better/worse right after we cut this queue over."

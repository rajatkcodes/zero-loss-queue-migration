# Migration Playbook

How to cut one of the 5 core queues over from the legacy Redis path to the new Kafka
path, live, using this project's control-plane API (or the Migration Control view in
the dashboard).

## 1. Pick the queue and establish a baseline

```
GET /api/queues
GET /api/metrics/snapshot     # note the queue's current p50/p95/p99, error-rate, breach-rate
```

Defend the choice with traffic data: the highest-volume or highest-breach-rate queue
is usually the right first candidate — it's where the legacy architecture is hurting
the most, and where the new path's isolation gains will be most visible.

## 2. Shadow it with dual-write

```
POST /api/queues/{queue-id}/mode
{"mode": "dual-write"}
```

Every event for that queue now goes to *both* paths. The legacy path stays
authoritative (nothing user-facing changes yet); the new path's consumers process the
same traffic for real, so its SLA numbers in the dashboard are trustworthy, not
theoretical.

Watch `/api/metrics/snapshot`'s `queues.{queue-id}` entry and compare against the
baseline. Let it run long enough to see the tail latencies (p95/p99), not just the
average.

## 3. Cut over

```
POST /api/queues/{queue-id}/mode
{"mode": "new"}
```

Traffic now flows only through Kafka. This is instant and requires no restart — the
event-generator reads the flag on every dispatch.

## 4. Roll back if needed

```
POST /api/queues/{queue-id}/mode
{"mode": "legacy"}
```

Since the flag is checked per-event, rollback is exactly as fast as cutover. This is
the "thoroughly tested, zero-risk rollback path" the role's 90-day goal calls for —
tested here by literally exercising it, not just designing for it.

## 5. Staged rollout within a queue (optional)

Before flipping a whole queue, a single merchant can be moved independently:

```
POST /api/merchants/{merchant-id}/mode
{"mode": "dual-write"}   # or "new" / "legacy"

DELETE /api/merchants/{merchant-id}/mode   # clear override, fall back to the queue's mode
```

Useful for validating against one high-value or high-risk merchant before committing
the whole queue.

## 6. Prove it survives a failure

```
POST /api/chaos/legacy
{"down": true}
```

Merchants already on `new` or `dual-write` keep delivering without interruption.
Merchants still on `legacy` queue up (Redis Lists are durable — nothing is dropped,
just delayed) until:

```
POST /api/chaos/legacy
{"down": false}
```

restores the path and the backlog drains. This is the honest version of "zero message
loss": it's a property of merchants that have been migrated, not a blanket claim that
covers merchants still waiting their turn — which is itself the argument for finishing
the migration.

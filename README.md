# swym-queue-migration

A working, live-running simulation of migrating a high-fan-out, Redis-backed alert
pipeline to a partitioned, multi-tenant-isolated Kafka pipeline — zero downtime, zero
message loss, feature-toggled per queue, with real distributed tracing and an SLA/
incident dashboard. Built as an interview showcase for a Staff Platform Engineer role
centered on exactly this problem: migrating a live notification queue off Redis
without dropping events or interrupting merchants.

Every design decision here is deliberately traceable back to a specific line in that
role's job description — see `docs/architecture.md` for the mapping and the
trade-offs, and `docs/migration-playbook.md` for the actual cutover procedure this
system implements.

## What's actually running

- **5 Clojure backend services** (`src/swym/services/`): an event generator that
  simulates restock/price-drop traffic across 24 merchants, a legacy Redis-based
  consumer (the "before" — one shared queue, no isolation), a new Kafka-based
  consumer (the "after" — per-tier topics and consumer groups), a metrics aggregator
  (SLA rollups + P1–P4 incident detection), and a control-plane (HTTP + WebSocket API
  the frontend talks to).
- **Real OpenTelemetry SDK tracing**, end to end, across process boundaries, with a
  custom Redis-backed `SpanExporter` written via Clojure `reify` against the Java SDK
  interface — not a homegrown stand-in. Verified: a span started in one process and a
  child span started in a completely separate process share the same trace ID.
- **A live React dashboard** (`frontend/`): migration control panel (toggle each of
  the 5 core queues between legacy / dual-write / new, live), an animated architecture
  view, a per-merchant SLA dashboard, a trace waterfall explorer, an incident feed with
  runbook reference, and chaos controls to induce and watch recovery from a legacy
  outage.
- **Fully isolated infra** — its own docker-compose stack (Redis on `6390`, Kafka on
  `19092`, Zookeeper on `2192`), so it never touches anything else running on the host
  and is reproducible with one command.

## Architecture

```
                                   ┌─────────────────────┐
                                   │   event-generator    │
                                   │ (restock/price-drop  │
                                   │  across 24 merchants)│
                                   └──────────┬───────────┘
                                              │ per-queue feature flag:
                                              │ legacy | dual-write | new
                        ┌─────────────────────┼─────────────────────────┐
                        │                                                │
                        ▼                                                ▼
           ┌─────────────────────────┐                    ┌───────────────────────────┐
           │  Redis List (shared)     │                    │  Kafka: standard topic     │
           │  swym:legacy:queue       │                    │  Kafka: enterprise topic   │
           │  — no tenant isolation — │                    │  — partitioned, isolated — │
           └────────────┬─────────────┘                    └─────────────┬──────────────┘
                        │ 4 shared workers                    6 + 4 dedicated workers
                        ▼                                                ▼
           ┌─────────────────────────┐                    ┌───────────────────────────┐
           │   legacy-consumer        │                    │      new-consumer          │
           └────────────┬─────────────┘                    └─────────────┬──────────────┘
                        │  hard failure                                   │  hard failure
                        ▼                                                ▼
                        └───────────────► DLQ (auto-replayed) ◄──────────┘
                                              │
                        results + spans ──────┴──────► metrics-aggregator ──► control-plane ──► dashboard
```

## Quick start

Requires Docker, Java 11+, [Clojure CLI](https://clojure.org/guides/install_clojure),
and Node.js.

```bash
./scripts/run-all.sh
```

This brings up the isolated Redis/Kafka stack, starts all 5 backend services, and
starts the frontend dev server. Logs land in `logs/*.log`. Stop everything with
`./scripts/stop-all.sh` (infra is left running; `docker compose down` to stop that
too).

Or run pieces individually:

```bash
docker compose up -d
clojure -M -m swym.services.control-plane        # in its own terminal
clojure -M -m swym.services.metrics-aggregator
clojure -M -m swym.services.legacy-consumer
clojure -M -m swym.services.new-consumer
clojure -M -m swym.services.event-generator
cd frontend && npm install && npm run dev
```

Control-plane API: `http://localhost:8901` — `GET /api/health` to confirm it's up.

## Prove the zero-loss claim yourself

```bash
./scripts/chaos-test.sh
```

Kills the legacy path, watches the backlog grow for 10 seconds, restores it, and
confirms it drains back to zero rather than being lost — because a Redis List is
durable, not because nothing happened.

## Suggested demo order

1. **Migration Control** — show all 5 queues on `legacy`, point out the SLA dashboard
   already showing breach incidents for standard-tier merchants under the shared,
   unisolated queue.
2. Toggle `wishlist-plus` to `dual-write`, then `new` — watch its entry in the SLA
   dashboard start reporting from the Kafka path, and the "N/5 core queues migrated"
   counter tick up.
3. **Live Architecture View** — point at the legacy backlog number live-updating.
4. **Chaos Control** — kill the legacy path. Merchants already on `new` keep flowing;
   the legacy backlog visibly grows on the sparkline. Restore it, watch it drain.
5. **Trace Explorer** — pick a recent trace, show the enqueue → dequeue waterfall
   spanning two separate JVM processes, correlated by trace ID.
6. **Incidents** — show the P1–P4 feed and the runbook reference panel next to it.

## Project structure

```
deps.edn                    single Clojure project, multiple service mains
docker-compose.yml           isolated Redis + Kafka + Zookeeper
src/swym/common/             shared: config, event schema, Redis, Kafka, tracing,
                              routing, DLQ, merchant catalog
src/swym/services/           the 5 runnable services (each has a -main)
frontend/                    Vite + React dashboard
docs/architecture.md         design decisions, before/after, trade-offs, what I'd
                              change at real scale
docs/migration-playbook.md   the actual cutover procedure this system implements
docs/runbook.md              P1–P4 incident definitions and response
scripts/                     run-all / stop-all / chaos-test
```

## Why Clojure

The role's stated core requirement includes Clojure specifically. Every service here
is written in it, including non-trivial JVM interop that would be easy to fake or
avoid: raw Kafka client usage, and a custom OpenTelemetry `SpanExporter` implemented
via `reify` against the Java SDK interface.

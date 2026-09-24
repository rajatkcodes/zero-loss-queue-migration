# Architecture

## The problem being modeled

Swym's real pipeline: a restock/price-drop event fans out to thousands of per-shopper
alerts across email/SMS/push. The original implementation is a single Redis-backed
queue with no per-merchant or per-tier isolation — one merchant's burst can delay
every other merchant's alerts, because there's one FIFO line and a fixed worker pool
that doesn't know whose message is whose.

This project simulates that exact topology, at a scale that runs on a laptop, and
migrates it live to a partitioned, tier-isolated architecture without dropping a
message or requiring a maintenance window.

## Before: the legacy path

```
event-generator --> [ Redis List: swym:legacy:queue ] --> N legacy-consumer workers --> simulated delivery
                       (single shared queue, no isolation)
```

One list, one worker pool, strict FIFO. A 4,000-subscriber enterprise restock event
occupies a worker for as long as it takes to process — everyone behind it in the list
waits, regardless of their own tier or urgency. This is structural, not simulated: the
noisy-neighbor problem *emerges* from the topology rather than being hardcoded.

## After: the new path

```
event-generator --> Kafka topic (standard, 6 partitions)    --> new-consumer-standard group (6 workers)
                 --> Kafka topic (enterprise, 3 partitions)  --> new-consumer-enterprise group (4 workers)
```

Two topics, two consumer groups, two independent worker pools. Enterprise traffic
cannot be delayed by standard traffic because it never shares a queue or a worker with
it — again, structural isolation, not throttling logic layered on top. Partitioning by
merchant-id also keeps a given merchant's events strictly ordered.

## The migration mechanism (the actual point of this project)

Every one of the 5 core queues (`wishlist-plus`, `back-in-stock`, `price-drop`,
`back-in-stock-enterprise`, `price-drop-enterprise`) has an independent feature flag
in Redis (`swym:migration:queues`), with an optional per-merchant override
(`swym:migration:flags`) for staged rollout within a queue. Three modes:

- **`legacy`** — all traffic on the old path (starting state).
- **`dual-write`** — every event is written to *both* paths. The new path's consumers
  process it for real (so you can watch its SLA numbers before trusting it), while the
  legacy path stays authoritative. This is the parity-verification step before a real
  cutover — you'd normally diff outcomes between paths here; in this scaled-down
  version, watching both paths' dashboards side by side under real traffic serves the
  same purpose.
- **`new`** — the queue is fully cut over.

Toggling a flag takes effect on the *next* event with zero process restarts, which is
what makes this a live, zero-downtime cutover rather than a deploy.

## Failure handling

- **Per-recipient soft failures** (bounced email, blocked push token) are tracked as an
  error-rate metric but don't require redelivery of the whole event.
- **Whole-event hard failures** (processing crash, malformed payload) push the full
  envelope to a DLQ (`swym:dlq:legacy`), auto-replayed every 10s using the merchant's
  *current* routing mode — so a message that failed on `legacy` before a cutover gets
  redelivered on `new` after one, rather than looping back into the path that just
  failed it. See `swym.common.dlq`.

## Distributed tracing

Each service runs its own `SdkTracerProvider` (real OpenTelemetry SDK, not a
hand-rolled substitute) exporting through a custom `SpanExporter` — implemented via
Clojure `reify` against the Java interface — straight into a Redis Stream
(`swym:traces:stream`). Because the transport between hops is a Redis list or a Kafka
record rather than an HTTP call, there's no W3C `traceparent` header to piggyback on;
instead the trace/span ids are carried manually inside the message envelope itself,
and the next hop reconstructs a remote parent context with
`SpanContext/createFromRemoteParent` before starting its own child span. Same idea the
W3C header encodes, just carried in our own payload. This was verified directly: a
span created in one process and a span created in a completely separate JVM process
share the same trace ID with correct parent/child linkage (see git history / session
transcript for the smoke test).

## Observability

`metrics-aggregator` consumes the delivery-outcome stream (`swym:results:stream`),
keeps a 60s rolling window per merchant and per queue, and computes p50/p95/p99
latency, error rate, and SLA breach rate against tier-appropriate targets (5s standard,
1.5s enterprise). It also emits the P1–P4 incident feed (see `docs/runbook.md`). The
control-plane exposes all of this over HTTP + a WebSocket feed for the dashboard.

## Why these specific technology choices

- **Clojure** for every backend service — matches the role's stated requirement, and
  the JVM interop was used directly and non-trivially (Kafka clients, OpenTelemetry
  SDK, a custom `SpanExporter` via `reify`) rather than hidden behind a wrapper
  library, since that interop is exactly the kind of code this role will touch.
- **Redis** for the legacy path (matches "originally built on Redis") and for
  cross-service bookkeeping (feature flags, DLQ, results/trace streams, metrics
  snapshot) — it's already the right tool for low-latency shared state between
  independent JVM processes.
- **Kafka** for the new path — partitioned topics and independent consumer groups are
  the most direct way to get structural multi-tenant isolation and durable, replayable
  delivery, which is the whole point of the migration.
- **Isolated docker-compose stack** (ports 6390/19092/2192) rather than reusing any
  shared local infrastructure — this project is fully reproducible with a single
  `docker compose up` and never risks colliding with anything else running on the
  host.

## What I'd do differently at 48,000-merchant scale

This is an honest simulation, not a claim that it's production-ready as-is:

- Real parity verification during `dual-write` needs an actual diff of outcomes
  between paths (delivered/failed/latency), not just side-by-side dashboards.
- Kafka partition count (6/3) would need to scale with actual merchant cardinality and
  be revisited under real throughput, not fixed at design time.
- Trace export via a Redis Stream is fine at demo scale; at production volume this
  would be a real OTLP exporter to a collector, with sampling.
- The chaos test here is a single induced outage; a real reliability program needs
  scheduled game days and automated chaos across more failure modes (network
  partition, slow consumer, partial broker loss).
- Service-to-service SLAs and noisy-neighbor isolation would need real quota
  enforcement (e.g. Kafka client quotas per merchant group) rather than relying purely
  on topic-level separation.

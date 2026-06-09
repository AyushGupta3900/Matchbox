# Non-Functional Requirements — MatchBox Exchange

> Status: **Draft v1** · Last updated: 2026-06-09
> Read [concepts/06-non-functional-requirements.md](../concepts/06-non-functional-requirements.md) first.
> Targets are for a **single-node dev/benchmark setup** (laptop + Docker). They're learning
> goals to engineer toward, not production SLAs. Each is tagged with the phase that *proves* it.

## How to read this
Every NFR is **NFR-n**, a measurable number, and points at the design decision it justifies.
Numbers escalate by phase: v1 (Phase 0–1) is correctness-first with modest load; the
aggressive latency/throughput numbers are *Phase 5* targets.

## Latency

| ID | Indicator (SLI) | Target (SLO) | Proven in | Justifies |
|----|-----------------|--------------|-----------|-----------|
| **NFR-1** | In-engine match decision (command in → events out) | p99 < **100 µs**, p999 < **1 ms** | Phase 5 | single-writer in-memory engine, object pooling, GC tuning |
| **NFR-2** | End-to-end order placement (gateway receive → `202` ack), local | p50 < **2 ms**, p99 < **5 ms**, p999 < **20 ms** | Phase 5 | reserve-before-match, ring buffer, no DB on hot path |
| **NFR-3** | Cancel latency | p99 < **5 ms** | Phase 5 | same hot path as placement |
| **NFR-4** | Market-data depth delta → WS subscriber | p99 < **50 ms** | Phase 3 | Redis cache + WS fan-out; eventual is fine |
| **NFR-5** | Read query (`GET /orders`, `GET /trades`) | p99 < **50 ms** | Phase 3 | indexed Postgres, read replica |

> We engineer for **percentiles, not averages** (NFR-1/2 are the heart of Phase 5). Success =
> holding p99 *and* p999 **under sustained load**, not in a quiet benchmark.

## Throughput

| ID | Indicator | Target | Proven in | Justifies |
|----|-----------|--------|-----------|-----------|
| **NFR-6** | Sustained order ingest, single symbol | **10,000 orders/sec** | Phase 5 | ring-buffer handoff, batching, no per-order sync DB write |
| **NFR-7** | Engine match rate, single symbol | ≥ **50,000 matches/sec** burst | Phase 5 | in-memory book, primitive (`long`) data, no allocation |
| **NFR-8** | Concurrent WS market-data subscribers | **1,000+** | Phase 3 | virtual-thread WS layer, snapshot+delta, shared cache |
| **NFR-9** | v1 correctness baseline | ≥ **500 orders/sec**, zero correctness errors | Phase 1 | proves the path works before we optimize it |

## Consistency (decided per data type — this is the key table)

| ID | Data | Model | Why |
|----|------|-------|-----|
| **NFR-10** | **Balances & ledger** | **Strong** | never double-spend; reserve must be immediately visible |
| **NFR-11** | **Order acceptance / matching** | **Strong + deterministic** | the engine is the single source of order truth; same input → same output |
| **NFR-12** | **Market data** (depth, trades, candles) | **Eventual** (≤ ~50 ms lag) | a slightly-stale book is harmless; enables caching + replicas |
| **NFR-13** | **Read models / projections** (settlement ledger from events, Phase 2) | **Eventual** | rebuilt from the log; converge, don't block the match |

> CAP choice: for **money** we pick **C over A** (reject rather than double-spend); for
> **market data** we lean **A over C** (serve slightly-stale rather than nothing).

## Durability

| ID | Requirement | Mechanism | Phase |
|----|-------------|-----------|-------|
| **NFR-14** | No **acknowledged** order is ever lost | durable event log (**Kafka**, replicated), engine replayable from log + snapshot | Phase 2 |
| **NFR-15** | Ledger is durable and **append-only** (never UPDATE/DELETE) | Postgres, WAL, fsync; corrections = compensating entries | Phase 0 |
| **NFR-16** | Engine state recoverable after crash | replay Kafka from last snapshot → identical book (the Phase-2 "done when") | Phase 2 |

## Availability

| ID | Target | Notes |
|----|--------|-------|
| **NFR-17** | Dev/benchmark: best-effort (single node). Target posture **99.9%** as a design goal | not a real SLA; we *design* for failover (Postgres replica, engine replay) without operating 24/7 |
| **NFR-18** | A single read-model consumer failing must **not** stop matching | async boundary: projections are decoupled from the hot path |
| **NFR-19** | Engine restart recovers via replay without manual intervention | Phase 2 deliverable |

## Capacity & data growth (sizing, with rough math)

Assume the Phase-5 benchmark sustains ~10k orders/sec for bursts, ~1k/sec average in a load test.

| ID | Dimension | Estimate | Implication |
|----|-----------|----------|-------------|
| **NFR-20** | Orders/day (load test) | ~1k/s × peak windows → **tens of millions/day** at full tilt | `orders` table needs good indexes + eventual partitioning |
| **NFR-21** | Trades/day | ≤ orders/day (≤1 trade per crossing) | same; `trades` is append-only, partition by time later |
| **NFR-22** | Ledger entries/day | ≥ 2 × (deposits + withdraws + fills) | grows fastest; **partition by month** when it hurts |
| **NFR-23** | Candle/OLAP rows | 1 per symbol per interval | tiny; belongs in **TimescaleDB** (Phase 3) |
| **NFR-24** | Concurrent connections | 1k+ WS + REST | virtual-thread gateway (Phase 4) |

> These are *order-of-magnitude* targets for a benchmark, not production traffic. The point is
> to feel real volume so Phase 5's optimizations have something to bite on.

## Retention

| ID | Data | Retention | Why |
|----|------|-----------|-----|
| **NFR-25** | Ledger, trades, orders (terminal) | **indefinite** (archive cold) | audit / reconciliation truth |
| **NFR-26** | Kafka event log | retain to last snapshot + buffer; compact/archive beyond | bounded replay cost (snapshots cap it) |
| **NFR-27** | Raw order-book deltas / market-data stream | **days** | only needed for recent replay/debug |
| **NFR-28** | Application logs | **weeks** | debugging window; cost-bounded |
| **NFR-29** | Metrics (Prometheus) | **15 days** raw, downsample older | dashboard history without unbounded storage |

## Resource budget (dev box — keep us honest)
- Heap target for the engine: small + **low-GC** (Phase 5 goal: minimal allocation per match).
- The matching thread is a **pinned platform thread**; everything I/O-bound uses virtual
  threads (Phase 4). Mixing these up violates NFR-1.

## Traceability: NFR → infrastructure (the justification map)

| NFR | Infra/design it justifies |
|-----|---------------------------|
| NFR-1/2/6/7 | in-memory single-writer engine, ring buffer, object pooling, ZGC/Shenandoah |
| NFR-10/11 | reserve-in-Postgres before match; **no balance caching on the hot path** |
| NFR-12/13/4 | Redis cache, WebSocket fan-out, read replica, async projections |
| NFR-14/15/16 | **Kafka** durable log, Postgres WAL, snapshots + replay |
| NFR-8/24 | virtual-thread gateway + WS tier |
| NFR-20–23 | indexing strategy, time-partitioning, TimescaleDB |

If any NFR above stops pointing at a real decision, cut it. If we ever add infra that traces
to no NFR here, question it.

## Open questions
- [ ] Are the Phase-5 latency numbers (p99 < 5 ms e2e) realistic on the target dev hardware,
      or set after a first baseline measurement? (Proposal: treat as *aspirational*; lock real
      numbers after the Phase-1 baseline benchmark, per "profile first.")
- [ ] Retention for the Kafka log vs snapshot cadence — coupled; finalize in Phase 2.

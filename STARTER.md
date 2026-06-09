# Trading Exchange — Backend Learning Project

A spot order-matching exchange built to force the *hard* versions of advanced backend
concepts: lock-free concurrency, in-memory data-structure design, low-GC memory
engineering, event sourcing, and latency observability. This is the depth-focused
counterpart to a broad integration project — here, correctness and latency are the
whole point.

> **Stack:** Java 21+ (virtual threads, structured concurrency), Spring Boot 3.2+,
> Postgres, Redis, Kafka, TimescaleDB/ClickHouse, Docker Compose.
> **Build philosophy:** every phase deliberately creates a problem that a *later*
> phase's tooling exists to solve. Don't shortcut the ordering.

---

## 1. Learning objectives

By the end you should be able to reason about and implement:

- An in-memory order book with price-time priority (the data structure *is* the system)
- A single-writer, lock-free matching hot path — and articulate *when not* to use locks/virtual threads
- Event sourcing + CQRS with Kafka as the durable log, plus replay and snapshots
- Latency engineering: object pooling, GC tuning, holding p99/p999 under load
- Pre-trade risk, double-entry settlement, exactly-once via sequencing
- Observability built around latency percentiles, not just throughput

---

## 2. High-level architecture

```
            ┌─────────────┐     signed REST / WebSocket
   client ──▶  Gateway    │  (auth, rate limit, pre-trade risk)
            └──────┬──────┘
                   │ command (validated, balance-reserved)
                   ▼
            ┌─────────────┐     append-only
            │  Sequencer  │────────────────────┐
            └──────┬──────┘                     ▼
                   │ ordered commands     ┌───────────┐
                   ▼                       │  Kafka    │  event log (source of truth)
        ┌────────────────────┐  events    │ (topics)  │
        │  Matching Engine   │───────────▶└─────┬─────┘
        │  single-writer,    │                  │
        │  in-memory book    │     ┌────────────┼─────────────┬──────────────┐
        └────────────────────┘     ▼            ▼             ▼              ▼
                              ┌──────────┐ ┌──────────┐ ┌──────────┐  ┌────────────┐
                              │Settlement│ │ Market   │ │Surveillance│ │ Analytics  │
                              │  ledger  │ │  data /  │ │  / risk   │ │  candles / │
                              │ (Postgres)│ │  WS fan- │ │           │ │ OLAP store │
                              └──────────┘ │  out +   │ └──────────┘  └────────────┘
                                           │  Redis   │
                                           └──────────┘
```

Data flow: a command is authenticated, risk-checked, and has funds reserved at the
gateway; the sequencer assigns a monotonic sequence number; the matching engine
consumes commands single-threaded, mutates the in-memory book, and emits events;
every downstream concern (settlement, market data, candles, surveillance) is a
*read model* projected from the event stream.

---

## 3. Module / package structure

```
exchange/
├── gateway/            REST + WebSocket entry, auth, rate limit, pre-trade risk
├── engine/             matching engine, order book, sequencer (no Spring on hot path)
│   ├── book/           OrderBook, PriceLevel, intrusive order lists
│   ├── match/          matching algorithm, trade generation
│   └── pool/           object pools, ring-buffer plumbing
├── events/             command + event schemas, serialization, outbox
├── settlement/         double-entry ledger, balance reservation, reconciliation
├── marketdata/         depth/trade/candle projections, WS fan-out, caching
├── analytics/          candle rollups, OLAP writes, surveillance
├── security/           HMAC signing, nonce/replay, JWT, API keys, roles
├── observability/      Micrometer config, custom metrics, tracing, health
└── common/             shared types, time, ids
```

Keep `engine` framework-free so the hot path stays allocation- and lock-light.
Spring wires everything *around* it.

---

## 4. Domain model (sketch)

**Order book** — price-time priority. Bids descending, asks ascending; each price
level is a FIFO queue of resting orders.

```java
// One side of the book. TreeMap to start; profile, then consider an array
// price-ladder for the hot range once you measure.
class BookSide {
    NavigableMap<Long, PriceLevel> levels;   // priceTicks -> level
}
class PriceLevel {
    long priceTicks;
    Deque<Order> resting;   // FIFO; consider an intrusive doubly-linked list
    long totalQty;          // maintained incrementally for fast depth
}
class Order {
    long id, accountId, priceTicks, qty, remaining, seq;
    Side side; OrderType type;   // LIMIT, MARKET, IOC, FOK
}
```

**Events** (append-only, the source of truth):
`OrderAccepted`, `OrderRejected`, `Trade`, `OrderCanceled`, `OrderExpired`.
The engine state at any time = fold over these events.

**Ledger** (Postgres, double-entry): every balance change is two rows
(debit + credit) that must sum to zero; reservations on order placement,
settlement on fill, release on cancel/expire.

---

## 5. Core design principles (get these right or the project loses its point)

1. **Single-writer matching path.** One thread owns the book. No locks on the hot
   path. Inter-thread handoff via a ring buffer (study the LMAX Disruptor pattern).
2. **Determinism.** Given the same ordered command stream, the engine must produce
   the identical event stream. This makes replay, testing, and recovery trivial —
   and is impossible if you sprinkle in `now()`, randomness, or concurrent mutation.
3. **Event sourcing as truth.** Postgres/ledger is a *projection*, not the master.
   Snapshot periodically so replay isn't unbounded.
4. **Virtual threads belong at the edges.** Use them for the I/O-bound gateway and
   WS fan-out. The matching thread stays a pinned platform thread. Knowing the
   difference is half the lesson.
5. **Reserve before you match.** Pre-trade risk reserves funds at the gateway so the
   engine never has to talk to the DB mid-match.

---

## 6. Infrastructure (docker-compose services)

- `postgres-primary` + `postgres-replica` (streaming replication) — ledger, settlement
- `redis` — balances cache, rate limiting, hot order-book depth, nonces
- `kafka` (+ `zookeeper`/`kraft`) — event log, command stream
- `timescaledb` (or `clickhouse`) — trades, OHLC candles, surveillance
- `prometheus` + `grafana` — metrics + dashboards
- `tempo`/`zipkin` — distributed tracing
- (later) a load generator container for benchmarking

---

## 7. Phased roadmap

Each phase lists: **Build / Learn / Done when**.

### Phase 0 — Skeleton + auth + wallet
- **Build:** Boot 3.2+/Java 21 app, Postgres, docker-compose, accounts, double-entry
  ledger, JWT login, actuator up.
- **Learn:** project layout, Spring Security basics, double-entry invariants.
- **Done when:** you can deposit funds and the ledger always balances to zero.

### Phase 1 — Matching engine (single symbol, in-memory)
- **Build:** order book structures, price-time matching, LIMIT/MARKET/IOC/FOK,
  REST order entry, balance reservation before accept.
- **Learn:** data-structure design, matching algorithm, determinism.
- **Done when:** a deterministic test replays a fixed order sequence to a fixed
  trade sequence; crossing orders match correctly.

### Phase 2 — Event sourcing + CQRS
- **Build:** commands → sequencer → events → Kafka; engine consumes commands,
  emits events; settlement ledger built as a projection; snapshots + replay.
- **Learn:** event sourcing, CQRS, idempotency, exactly-once via sequence numbers.
- **Done when:** you can kill the engine, replay from Kafka (+ snapshot), and land
  in the exact same book state.

### Phase 3 — Market data + WebSocket fan-out
- **Build:** depth/trade projections, WS streaming to many subscribers, OHLC candles
  to TimescaleDB, Redis-cached depth snapshots + deltas.
- **Learn:** caching strategy, fan-out, time-series storage, read replicas for queries.
- **Done when:** clients get live depth/trades over WS with cached snapshot + delta.

### Phase 4 — Concurrency hardening
- **Build:** ring buffer / Disruptor-style handoff into the engine, virtual-thread
  gateway + WS layer, multi-symbol (one engine thread per symbol or sharded).
- **Learn:** lock-free handoff, single-writer at scale, VT pinning pitfalls
  (`synchronized`, `ThreadLocal`), backpressure.
- **Done when:** gateway sustains high concurrent order entry without lock contention
  on the matching path.

### Phase 5 — Memory & latency engineering
- **Build:** object pooling for orders/events, GC tuning (G1 → ZGC/Shenandoah),
  allocation profiling (async-profiler), load test with a generator.
- **Learn:** low-GC hot paths, escape analysis, latency percentiles, mechanical
  sympathy.
- **Done when:** you hold a target p99 / p999 latency under sustained load and can
  show the GC pauses you removed.

### Phase 6 — Observability, surveillance, settlement
- **Build:** Micrometer latency timers (p50/p99/p999), order-lifecycle tracing across
  Kafka, consumer-lag + sequence-gap metrics, custom health indicators, settlement
  cron (ShedLock-guarded), ledger-vs-trade reconciliation, basic surveillance rules
  on the analytics store.
- **Learn:** percentile-driven observability, distributed tracing through async hops,
  scheduled reconciliation, anomaly detection.
- **Done when:** a Grafana dashboard shows latency percentiles + lag, a trace follows
  one order end-to-end, and nightly reconciliation proves ledger == trades.

---

## 8. Things people get wrong (watch for these)

- Using floating point for prices/quantities → use integer ticks / fixed-point.
- Letting the engine read the DB mid-match → reserve up front instead.
- Non-deterministic engine (clock, random, concurrency) → replay/tests break.
- Treating Postgres as the source of truth → it's a projection of the event log.
- Reaching for virtual threads on the matching path → keep it a single pinned thread.
- Optimizing the data structure before measuring → profile first, then specialize.

---

## 9. Stretch goals

- Stop orders / iceberg orders / post-only / self-trade prevention
- Perpetual futures: funding rate cron, mark price, liquidation engine
- Cross-region replica + failover of the engine via event-log replay
- A FIX or binary protocol gateway alongside REST/WS
- Chaos testing: kill the engine mid-stream and prove clean recovery

---

## 10. Reference concepts to read

- LMAX Disruptor / single-writer principle and mechanical sympathy
- Event sourcing + CQRS (command vs read models, snapshots)
- Price-time priority matching and order-book microstructure
- JVM GC tuning (G1 vs ZGC/Shenandoah) and low-latency allocation
- Double-entry accounting for ledgers

---

*Start at Phase 0. Resist building Phase 5/6 tooling early — the value is in using it
to debug the problems you create in Phases 1–4.*
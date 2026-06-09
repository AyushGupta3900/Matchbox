# 05 — System Architecture

## What an architecture diagram is for

A data model shows what you *store*. An architecture diagram shows what *runs*: the
processes, the data stores, the queues, the external systems — and the **arrows** between
them. Its job is to answer, at a glance: *where does a request go, where does state live, and
what talks to what?*

The most valuable thing it forces you to decide is **boundaries**: what's one deployable unit
vs many, and which calls are synchronous vs asynchronous. Those are expensive to change later,
so we draw them now.

## Zoom levels (the C4 idea, simplified)

Don't cram everything into one picture. Draw at the level you need:

1. **Context** — your system as one box, with the outside actors/systems around it. ("Who
   uses us, what do we depend on?")
2. **Container** — the deployable pieces *inside*: the app(s), the database, the cache, the
   queue. ("What processes run, and what state stores exist?") ← we live here most.
3. **Component** — inside one container: the modules/packages. ("How is the app organized?")

We'll draw container + component. Context is just "traders/bots → MatchBox → Postgres/Redis/Kafka."

## The big fork: monolith vs microservices

- **Monolith** — one deployable app. Everything runs in one process; modules call each other
  as function calls. *Pros:* simple to build, deploy, debug; no network between parts; easy
  transactions. *Cons:* one big thing to scale, one failure domain.
- **Microservices** — many small apps, each its own process/deploy, talking over the network.
  *Pros:* scale/​deploy/​fail independently. *Cons:* network failures, distributed
  transactions, way more operational complexity. You pay this tax *constantly*.

The senior take: **start with a monolith.** Microservices solve *organizational* and
*scaling* problems you mostly don't have yet, and they add distributed-systems pain you can't
avoid once you've split. Splitting later is easy *if you drew the boundaries well*; merging
back is brutal.

## The sweet spot: a modular monolith

One deployable app, but internally split into **modules with clean boundaries** — each module
owns its data and exposes a narrow interface, as if it *could* be a separate service. You get
monolith simplicity now, and the option to "lift a module out into its own service" later by
swapping in-process calls for network calls.

That's exactly our plan: build a modular monolith (Phases 0–3), then in Phase 4 lift the
matching **engine** out as its own thing when concurrency demands it.

## Sync vs async — the boundary that shapes everything

- **Synchronous (request/response)** — caller waits for the answer. ("Place order" → "accepted,
  here are your fills.") Simple, immediate, but the caller is *blocked* and failures propagate
  directly.
- **Asynchronous (fire-and-forget / event-driven)** — caller drops a message and moves on; the
  work happens later; results arrive via events. (An order's **event** flows to settlement,
  market data, analytics — none of which the trader waits for.)

Where you put this boundary decides whether you need a **queue**. The rule: keep the user-
facing path synchronous and *short*; push everything that doesn't need an immediate answer
(settlement, projections, analytics) **behind an async boundary** so it can't slow down or
break the response.

## CQRS: the write side and the read side are different shapes

**Command Query Responsibility Segregation** = separate the path that *changes* state
(commands: place/cancel order) from the paths that *read* state (queries: depth, trades,
candles). On an exchange they have wildly different needs:

- The **write** side must be one fast, correct, single-writer path.
- The **read** sides are many, can be eventually-consistent, and are built as independent
  **projections** off the event stream (a depth view, a candle view, the ledger).

This is why the architecture has *one* engine and *many* read models hanging off Kafka.

## Where our state lives (and why each store)

| Store | Holds | Why this store |
|-------|-------|----------------|
| **Postgres** | accounts, ledger, orders, trades | relational, transactional, durable, queryable |
| **Redis** | nonces, rate limits, hot caches | in-memory, fast, auto-expiring, ephemeral-ok |
| **Kafka** | the event log + command stream | durable, ordered, replayable append-only log |
| **TimescaleDB** | candles / time-series (later) | optimized for time-bucketed queries |
| **In-memory (engine)** | the live order book | the hot path can't touch disk per match |

Picking the right store per job — instead of forcing everything into one database — is a core
senior skill. Each store above earns its place by doing one thing the others do badly.

## The single-writer engine: the unusual part

Most web apps are "many threads hit the database." Ours has a deliberately *weird* core: the
matching engine is **one thread** that owns the order book in memory, fed commands through a
**ring buffer** (a lock-free queue — study the LMAX Disruptor). No locks, no DB reads mid-
match, fully deterministic. Everything else in the architecture exists to *feed* that thread
cleanly and *project* its output. Keep that center sacred; wire Spring and I/O *around* it.

## Reading our diagrams

The next doc has two: the **v1** architecture (a modular monolith, Phases 0–2) and the
**target** architecture (the split-out, queue-driven system of Phases 3–6). Drawing both shows
the *evolution* — and proves the boundaries we pick now survive the split later.

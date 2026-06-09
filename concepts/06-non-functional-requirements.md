# 06 — Non-Functional Requirements (NFRs)

## What they are

Functional requirements say *what* the system does ("a user can place an order"). NFRs say
*how well* it must do it: how fast, how many, how reliable, how consistent, how durable. They
are the qualities, not the features — and they're what actually drive your **infrastructure**
choices. You don't add Kafka because it's cool; you add it because an NFR ("no acknowledged
order may ever be lost") demands a durable log.

The discipline: **every NFR is a number with a unit, not an adjective.** "Fast" is useless.
"p99 order-placement latency < 5 ms at 2,000 orders/sec" is a target you can test and fail.

## Latency vs throughput — don't confuse them

- **Latency** — how long *one* operation takes. Measured in time (ms, µs). "How long until my
  order is acknowledged?"
- **Throughput** — how *many* operations per second the system handles. Measured in rate
  (orders/sec). "How many orders can we match per second?"

They trade off and they're independent: a system can be high-throughput but high-latency
(a batch pipeline) or low-latency but low-throughput. An exchange needs **both** — low latency
*and* high throughput — which is the hard combination, and the whole point of this project.

## Percentiles — why the average is a lie

Never describe latency with an average. Averages hide the pain. Use **percentiles**:

- **p50 (median)** — half of requests are faster than this. The "typical" experience.
- **p99** — 99% are faster; the slowest 1% are worse. The "bad day" experience.
- **p999** (p99.9) — the slowest 0.1%. The "tail."

Why the tail matters more than the average on an exchange: a trader placing 1,000 orders hits
your p999 *on average once*. A page that makes 100 backend calls is as slow as its *slowest*
call — so the p99 of one service becomes the p50 of the page. **Tail latency compounds.**
"Average is 2 ms" can hide a p999 of 800 ms that wrecks real users. This is why Phase 5 of
this project is "hold p99/p999 under load," not "improve the average."

> Mental model: optimizing the average is improving the common case; optimizing the tail is
> *removing the worst case*. Removing worst cases (GC pauses, lock contention) is senior work.

## SLI / SLO / SLA — the vocabulary

- **SLI** (Indicator) — the *thing you measure*. "p99 order-placement latency."
- **SLO** (Objective) — your *internal target* for it. "p99 < 5 ms, 99.9% of the time."
- **SLA** (Agreement) — a *promise to a customer*, usually with penalties. (We have none —
  this is a learning project — but the SLO is what we engineer toward.)

You pick SLIs that reflect user pain, set SLOs you can defend, and measure relentlessly
(that's the observability doc).

## Consistency: strong vs eventual

- **Strong consistency** — every read sees the latest write, immediately. Required where being
  wrong for even a moment is unacceptable. **Balances are strong**: you must never let two
  orders spend the same dollar.
- **Eventual consistency** — reads may briefly see stale data, but converge. Fine where a tiny
  lag is harmless. **Market data is eventual**: a depth snapshot that's 50 ms behind is fine;
  a trader's *balance* being 50 ms behind is a bug that loses money.

Deciding strong-vs-eventual *per piece of data* is a core senior judgment. It directly sets
where you can cache, replicate, and go async — and where you absolutely cannot.

## CAP, briefly

When the network between your nodes breaks (a "partition"), you can keep either **Consistency**
or **Availability**, not both. For *money* (balances, the ledger) we choose **consistency** —
better to reject an order than to double-spend. For *market data* we lean **availability** —
serving slightly-stale depth beats serving nothing. Same system, different choice per concern.

## Durability & availability — different promises

- **Durability** — once we *say yes*, the data survives crashes. "No acknowledged order is
  ever lost." Achieved with durable writes, replication, the Kafka log.
- **Availability** — the system is *up and answering*. Measured in "nines": 99.9% ≈ 8.7 h
  downtime/year. More nines = exponentially more cost/complexity (replicas, failover).

A subtle truth: you can be durable but unavailable (data safe, but you're down), or available
but not durable (answering, but losing writes). Name which one each part needs.

## Capacity & growth — size for the future, not just today

NFRs include *how much*: peak orders/sec, concurrent connections, **data volume growth**
(rows/day × retention), and headroom. These set DB sizing, partitioning, and whether trades
need a time-series store. A table growing 10 M rows/day needs a very different plan than one
growing 10 k/day — and you decide that *before* it's a 2 AM incident.

## Retention — how long you keep things

Not all data lives forever. **Retention** = how long each kind is kept before archival or
deletion. The ledger and trades are likely kept indefinitely (audit/legal); raw order-book
deltas might be kept days; logs weeks. Retention drives storage cost and partitioning, and
sometimes legal obligations.

## The payoff: NFRs justify infrastructure

Each NFR should point at a design decision:
- "no acknowledged order lost" → **durable event log (Kafka)** + replication.
- "p99 match < 1 ms" → **in-memory single-writer engine**, object pooling, GC tuning.
- "balances strongly consistent" → reserve-in-Postgres, **no caching balances on the hot path**.
- "market data eventually consistent, high fan-out" → **Redis cache + WebSocket**, read replicas.
- "10k orders/sec" → ring-buffer handoff, batching, no per-order DB write on the hot path.

If an NFR points at *nothing*, it's decoration. If a piece of infra traces to *no* NFR, you
probably don't need it yet.

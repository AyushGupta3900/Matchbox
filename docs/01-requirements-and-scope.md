# Requirements & Scope — MatchBox Exchange

> Status: **Draft v1** · Owner: you · Last updated: 2026-06-09
> Read [concepts/01-requirements-and-scope.md](../concepts/01-requirements-and-scope.md) first.

## 1. Purpose

MatchBox is a **spot order-matching exchange**: it accepts buy and sell orders for a
trading pair, matches them by price-time priority, records the resulting trades, and keeps
every account's balance correct to the cent (well — to the integer tick). It is the engine
that sits underneath something like Binance or a stock exchange, minus the front-end and
the regulatory surface.

"Spot" = you trade the asset itself for immediate settlement (buy BTC with USD, get BTC).
No leverage, no futures, no borrowing — those are stretch goals.

The point of building it is to learn the *hard* backend problems: a correct in-memory
matching data structure, a deterministic single-writer hot path, event sourcing as the
source of truth, and holding tail latency under load.

## 2. Actors (who consumes the system)

| Actor | Description | Primary needs |
|-------|-------------|---------------|
| **Trader** | Authenticated user placing/cancelling orders, depositing/withdrawing. | Correct balances, fast acknowledgement, clear rejection reasons. |
| **Market-data consumer** | Bot/UI subscribing to live order-book depth, trades, candles. | High-throughput stream, stable schema, snapshot + deltas. |
| **Settlement / ledger (internal)** | Read model that turns trade events into double-entry balance changes. | Exactly-once processing, reconcilable against trades. |
| **Analytics / surveillance (internal)** | Read models for candles, OLAP, anomaly rules. | Replayable event stream, no impact on the hot path. |
| **Ops / admin** | Operator running reconciliation, inspecting state, halting a symbol. | Overrides, audit trail, health visibility. |

> Note: traders and market-data consumers hit the **public API**. The internal actors
> consume the **event stream**, not the API. This split is the heart of CQRS (later doc).

## 3. Functional requirements

Numbered so schema fields, endpoints, and tests can cite them. Tagged **[v1]** (build now)
or **[later]** (a named phase will pick it up).

### Accounts & wallet
- **FR-1 [v1]** A user can register and authenticate; each user has exactly one account.
- **FR-2 [v1]** An account holds a balance per asset (e.g. USD, BTC), tracked as integers.
- **FR-3 [v1]** A user can deposit funds; the balance increases and the ledger records it.
- **FR-4 [v1]** A user can withdraw available (non-reserved) funds.
- **FR-5 [v1]** Every balance change is recorded as **double-entry** (debit + credit rows
  that sum to zero). The ledger must *always* balance to zero globally.

### Orders & matching
- **FR-6 [v1]** A user can place a **limit** order (buy/sell, price, quantity) for a symbol.
- **FR-7 [v1]** A user can place a **market** order (quantity, no price).
- **FR-8 [v1]** Supported time-in-force / types: **LIMIT, MARKET, IOC** (immediate-or-cancel),
  **FOK** (fill-or-kill).
- **FR-9 [v1]** The engine matches by **price-time priority**: best price first; within a
  price level, oldest order first (FIFO).
- **FR-10 [v1]** Before an order is accepted, the required funds are **reserved** (pre-trade
  risk). An order that can't be funded is **rejected**, not partially accepted.
- **FR-11 [v1]** A user can cancel a resting (unfilled/partially-filled) order; reserved
  funds for the unfilled remainder are released.
- **FR-12 [v1]** Prices and quantities are **integer ticks / fixed-point** — never floats.
- **FR-13 [v1]** The engine is **deterministic**: the same ordered command stream always
  produces the same event stream (no clock/random/concurrency in the match path).
- **FR-14 [later · Phase 4]** Support **multiple symbols** concurrently.
- **FR-15 [later · stretch]** Stop orders, iceberg, post-only, self-trade prevention.

### Events & history
- **FR-16 [v1]** Every state change emits an append-only event: `OrderAccepted`,
  `OrderRejected`, `Trade`, `OrderCanceled`, `OrderExpired`.
- **FR-17 [later · Phase 2]** Events are durably logged (Kafka) and are the **source of
  truth**; the engine state = fold over events.
- **FR-18 [later · Phase 2]** The engine can be killed and **replayed** from the log
  (+ snapshot) back to the identical book state.
- **FR-19 [later · Phase 2]** Settlement ledger is built as a **projection** of trade events.

### Market data
- **FR-20 [later · Phase 3]** Consumers can fetch a current order-book **depth snapshot**.
- **FR-21 [later · Phase 3]** Consumers can subscribe over **WebSocket** to live depth
  deltas and trades.
- **FR-22 [later · Phase 3]** **OHLC candles** are produced and stored as time-series.

### Operations
- **FR-23 [later · Phase 6]** Nightly **reconciliation** proves ledger balances == sum of
  trades.
- **FR-24 [later · Phase 6]** Health, latency percentiles (p50/p99/p999), and consumer-lag
  metrics are observable.
- **FR-25 [later · Phase 6]** An operator can halt/resume trading on a symbol.

## 4. Out of scope (deliberately not building)

- Margin / leverage / futures / liquidation (mark price, funding) — *stretch only*.
- Fiat on/off-ramp, KYC, regulatory reporting, custody of real assets.
- A front-end UI (we expose APIs + streams; a UI is a separate consumer).
- Mobile apps, notifications, email.
- Multi-tenant / white-label concerns.
- Cross-exchange arbitrage, smart order routing.

If any of these come back, they re-enter through *this doc* first.

## 5. v1 definition of done (the spine)

v1 = **Phases 0–2** of [STARTER.md](../STARTER.md):

1. A user can register, deposit, and the ledger balances to zero. *(FR-1…FR-5)*
2. A user can place LIMIT/MARKET/IOC/FOK orders on one symbol; crossing orders match by
   price-time priority; funds are reserved before accept. *(FR-6…FR-13)*
3. A deterministic test replays a fixed order sequence into a fixed trade sequence.
   *(FR-13)*
4. Events flow through a durable log; killing and replaying the engine lands in the same
   state; settlement is a projection. *(FR-16…FR-19)*

Phases 3–6 (market data, concurrency, latency, observability) are **post-v1** and each has
its own "done when" in STARTER.md.

## 6. Key constraints & assumptions (these shape everything downstream)

- **Money is integers.** All prices/quantities are fixed-point integer ticks. A "tick" is
  the smallest price increment; quantities are in the smallest unit of the asset. (Drives
  the data model: `long`, not `BigDecimal`/`double`.)
- **One writer owns the book.** The matching path is single-threaded and lock-free. (Drives
  architecture: a ring-buffer handoff, not a thread pool, on the hot path.)
- **DB is a projection, not the master.** Postgres holds the ledger as a read model derived
  from events. (Drives the data model + migration strategy.)
- **Reserve before match.** Risk/funds are checked at the gateway; the engine never reads
  the DB mid-match. (Drives the API + auth flow + balance cache.)

## 7. Glossary (so the docs share one vocabulary)

| Term | Meaning |
|------|---------|
| **Order book** | The set of all resting (unmatched) orders for a symbol, organized by price. |
| **Bid / Ask** | A buy order (bid) / a sell order (ask). |
| **Resting order** | An order sitting in the book waiting to be matched. |
| **Spread** | Gap between the best bid and best ask. |
| **Price-time priority** | Match best price first; ties broken by who arrived first. |
| **Tick** | Smallest allowed price increment (we store prices as integer multiples of it). |
| **Fill** | A (partial or full) execution of an order against a counter-order → a Trade. |
| **IOC / FOK** | Immediate-or-cancel (fill what you can now, cancel rest) / Fill-or-kill (all now or nothing). |
| **Reservation** | Funds locked when an order is placed, so they can't be double-spent. |
| **Double-entry** | Accounting rule: every change is a balanced pair of debit + credit. |
| **Event sourcing** | Storing the *sequence of changes* as truth, deriving current state by replaying them. |
| **Projection / read model** | A queryable view (e.g. the ledger) built by consuming events. |

## 8. Open questions (resolve before they block a downstream doc)

- [ ] Which exact symbol(s) for v1? (Proposal: a single `BTC-USD` pair.)
- [ ] Auth mechanism for traders: JWT only, or JWT + HMAC-signed orders? (Decide in auth doc.)
- [ ] Tick size + quantity precision for the v1 symbol? (Needed for the data model.)
- [ ] Do withdrawals settle instantly in v1, or is there a pending state? (Affects ledger.)

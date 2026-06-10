# 17 — The Order Book Data Structure

> Phase 1 opens here. The order book is the in-memory heart of the exchange. Get the data
> structure right and matching becomes easy; get it wrong and nothing else matters. This builds
> directly on the bids/asks/spread picture from [docs/00](../docs/00.business-idea.md).

## "The data structure IS the system"

Most of a normal web app is "move data between the DB and JSON." The matching engine is
different: its **in-memory data structure is the product**. Orders rest in it, match against
it, and leave it — thousands of times a second. So we design the structure around the exact
operations it must do *fast*:

| Operation | When | Must be |
|-----------|------|---------|
| find the **best price** on a side | every incoming order | O(1) / very fast |
| **add** a resting order at a price | order doesn't fully match | O(log n) |
| **match** an incoming order against the best prices | every order | fast, in price order |
| **remove** a filled/cancelled order | constantly | fast |
| read **depth** (qty at each price) | market data | fast |

Everything below is chosen to make those cheap.

## Price-time priority → a two-level structure

Recall the matching rule: **best price first; ties broken by who arrived first (FIFO).** That's
literally two levels of ordering, so the structure has two levels:

```
BookSide (one per side: bids, asks)
  └─ sorted by PRICE ───────────────┐
       price 60010 → PriceLevel ──┐  │
       price 60000 → PriceLevel   │  │   each PriceLevel:
       price 59990 → PriceLevel   │  │     └─ a FIFO queue of orders (by TIME of arrival)
                                   │  │          [order#1] → [order#2] → [order#3]
```

- **Outer level — price.** A sorted map from price → the level at that price. Sorted so the
  **best price is at the front**.
- **Inner level — time.** Within one price, a **FIFO queue**: first order in is first to match.

That's price-time priority, encoded as a structure.

## Two sides, sorted opposite ways

There are two books, one per side, sorted so "best" is always at the front:
- **Bids** (buys): sorted **high → low** (best bid = highest price).
- **Asks** (sells): sorted **low → high** (best ask = lowest price).

The best bid and best ask sit facing each other; the gap between them is the **spread**. An
incoming order always matches starting from the *front* (the best price) of the opposite side.

## The Java structures (what we'll use)

### Per side: a `NavigableMap<Long, PriceLevel>` (a `TreeMap`)
- **Key = price in ticks** (a `long` — integer money, never a float).
- **Value = the `PriceLevel`** at that price.
- A `TreeMap` keeps keys **sorted** and gives O(log n) insert/remove and **O(1)-ish access to
  the first/last key** (`firstKey`/`lastKey`) — that's your best price.
- Bids use **descending** order, asks **ascending**, so the best is always `firstEntry()`.

> The brief's advice: *"TreeMap to start; profile, then consider an array price-ladder for the hot
> range once you measure."* That's the **profile-first** rule — we start simple and correct,
> and only specialize the data structure after we *measure* a bottleneck (Phase 5). Don't
> pre-optimize.

### Per price level: a FIFO queue — `ArrayDeque<Order>`
- New orders join the **back**; matching takes from the **front** → FIFO = time priority.
- Keep a running `long totalQty` on the level, updated incrementally, so "how much is available
  at this price" is O(1) (needed for depth + fast matching), not a re-sum every time.

### The `Order` (plain fields, integer money)
```
id          long     unique order id
accountId   long     who placed it
side        Side     BUY | SELL
type        OrderType LIMIT | MARKET | IOC | FOK
priceTicks  long     limit price in ticks (ignored for MARKET)
originalQty long     requested quantity (in the asset's smallest unit)
remainingQty long    unfilled quantity (shrinks as it fills)
seq         long     sequence number assigned on entry (see determinism below)
```

## Determinism: use a sequence number, NOT the clock

This is the subtle, crucial part. To break time ties (FIFO) we must order orders by arrival —
but we must **never use `System.now()`** in the engine. Why? **Determinism** (a core project
principle): given the same ordered list of commands, the engine must produce the *exact same*
trades, every time — so we can replay, test, and recover. Wall-clock time is non-deterministic
(it differs on replay), and so is randomness or concurrent mutation.

So instead of a timestamp, every order gets a **monotonic `seq`** — a counter that increments by
one as each order enters the engine. FIFO within a price level is really "lowest `seq` first."
Because the `seq` comes purely from the order stream (not the clock), replaying the same stream
reproduces the same book and the same trades. **The clock is banned from the hot path; the
sequence number replaces it.**

(This is also why the engine is **framework-free** — no Spring, no I/O, no `now()` — so nothing
sneaks non-determinism into the match.)

## Where this lives

A new, deliberately Spring-free package (from [docs/05](../docs/05-architecture.md)):
```
com.matchbox.engine
├── book/    Order, Side, OrderType, PriceLevel, BookSide, OrderBook
├── match/   (next concept: the matching algorithm)
└── ...
```
Plain Java objects, no annotations. Spring will wire *around* the engine later; the engine
itself stays a pure, testable, deterministic core.

## What we build first (this step)
Just the **structures**, no matching yet: `Side`, `OrderType`, `Order`, `PriceLevel`,
`BookSide`, `OrderBook` — with operations to add a resting order, peek the best price, and read
depth. Once the container is right, the matching algorithm (next concept) is short.

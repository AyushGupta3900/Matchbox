# Syntax 09 — Building the Order Book in Java

> Reference for **Phase 1, step 1.1 — the order book structures**. Read
> [concepts/17](../concepts/17-order-book-data-structure.md) first. `PriceLevel` and `BookSide`
> are worked (the data-structure mechanics); the enums, `Order`, and `OrderBook` are yours.
> **Plain Java — no Spring annotations in `engine/`** (keeps the hot path framework-free).

## Package layout
```
src/main/java/com/matchbox/engine/book/
├── Side.java          enum BUY, SELL
├── OrderType.java     enum LIMIT, MARKET, IOC, FOK
├── Order.java         a mutable order (remainingQty shrinks as it fills)
├── PriceLevel.java    FIFO queue of orders at one price  (worked below)
├── BookSide.java      sorted price -> level for one side  (worked below)
└── OrderBook.java     bids + asks together
```
Lombok is fine here (it's compile-time codegen, not a framework) — use `@Getter`/`@Setter` to
cut boilerplate.

## 1. Enums (your turn — trivial)
```java
package com.matchbox.engine.book;
public enum Side { BUY, SELL }
```
```java
package com.matchbox.engine.book;
public enum OrderType { LIMIT, MARKET, IOC, FOK }
```

## 2. `Order` (your turn — a mutable holder)
Fields from concept 17: `long id, accountId, priceTicks, originalQty, remainingQty, seq;`
`Side side; OrderType type;`. Money is `long` (ticks), never float.
```java
package com.matchbox.engine.book;
import lombok.Getter; import lombok.Setter;

@Getter @Setter
public class Order {
    private long id;
    private long accountId;
    private Side side;
    private OrderType type;
    private long priceTicks;      // limit price; ignored for MARKET
    private long originalQty;
    private long remainingQty;    // shrinks as it fills
    private long seq;             // assigned on entry (determinism — NOT a timestamp)
    // a constructor that sets the fields + remainingQty = originalQty is handy
}
```

## 3. `PriceLevel` — FIFO queue at one price (worked)
```java
package com.matchbox.engine.book;

import java.util.ArrayDeque;
import java.util.Deque;

/** All resting orders at a single price, in time (FIFO) order. */
public class PriceLevel {
    private final long priceTicks;
    private final Deque<Order> orders = new ArrayDeque<>();
    private long totalQty;          // sum of remainingQty, maintained incrementally

    public PriceLevel(long priceTicks) { this.priceTicks = priceTicks; }

    public long priceTicks() { return priceTicks; }
    public long totalQty()   { return totalQty; }
    public boolean isEmpty() { return orders.isEmpty(); }

    public void add(Order o) {            // new orders join the BACK (time priority)
        orders.addLast(o);
        totalQty += o.getRemainingQty();
    }
    public Order peekFirst() {            // the next order to match (front = oldest)
        return orders.peekFirst();
    }
    public void removeFirst() {           // when the front order is fully filled
        Order o = orders.pollFirst();
        if (o != null) totalQty -= o.getRemainingQty();
    }
    /** Call when a resting order at the front partially fills, to keep totalQty correct. */
    public void reduceTotal(long filledQty) { totalQty -= filledQty; }
}
```
Why `ArrayDeque`: fast add-to-back / take-from-front, no per-node allocation like a linked list.
`totalQty` is kept up to date on every change so depth is O(1) (concept 17).

## 4. `BookSide` — sorted prices for one side (worked)
The key trick: **the comparator makes "best price" always `firstEntry()`** — descending for
bids, natural (ascending) for asks.
```java
package com.matchbox.engine.book;

import java.util.Comparator;
import java.util.NavigableMap;
import java.util.TreeMap;

public class BookSide {
    private final Side side;
    private final NavigableMap<Long, PriceLevel> levels;

    public BookSide(Side side) {
        this.side = side;
        // BIDS: highest price is best -> sort keys DESCENDING.
        // ASKS: lowest price is best  -> sort keys ASCENDING (natural).
        this.levels = (side == Side.BUY)
                ? new TreeMap<>(Comparator.reverseOrder())
                : new TreeMap<>();
    }

    /** Rest an order at its price (creating the level if needed). */
    public void addOrder(Order o) {
        levels.computeIfAbsent(o.getPriceTicks(), PriceLevel::new).add(o);
    }

    /** The best level on this side (best bid / best ask), or null if empty. */
    public PriceLevel bestLevel() {
        var e = levels.firstEntry();
        return e == null ? null : e.getValue();
    }
    public Long bestPrice() {
        return levels.isEmpty() ? null : levels.firstKey();
    }

    /** Drop a level once it has no orders left. */
    public void removeLevelIfEmpty(long priceTicks) {
        PriceLevel lvl = levels.get(priceTicks);
        if (lvl != null && lvl.isEmpty()) levels.remove(priceTicks);
    }

    public boolean isEmpty() { return levels.isEmpty(); }
    public NavigableMap<Long, PriceLevel> levels() { return levels; }   // for depth/iteration
}
```
`computeIfAbsent` creates the `PriceLevel` only the first time a price is seen. `firstEntry()`
is the best price for *both* sides because the comparator already put "best" first.

## 5. `OrderBook` (your turn — holds both sides)
```java
package com.matchbox.engine.book;

public class OrderBook {
    private final String symbol;                 // e.g. "BTC-USD"
    private final BookSide bids = new BookSide(Side.BUY);
    private final BookSide asks = new BookSide(Side.SELL);
    // getters; helpers like bestBid()/bestAsk(); the matching method comes NEXT concept.
}
```
For now it just *holds* a resting order (route to `bids`/`asks` by side) and exposes best
bid/ask + depth. **No matching yet** — that's the next step.

## 6. Sanity-check it (a throwaway `main` or a JUnit test)
Prove the structure orders correctly before we add matching:
```java
OrderBook book = new OrderBook("BTC-USD");
// add a few resting bids/asks at different prices, then:
//   assert bids.bestPrice() == the HIGHEST bid price
//   assert asks.bestPrice() == the LOWEST ask price
//   assert a price level's totalQty == sum of its orders' remainingQty
```
Add two bids at 60000 and 59990 → best bid must be **60000**. Add two asks at 60010 and 60050 →
best ask must be **60010**. That confirms the comparators are right (the bug-prone part).

## Gotchas
- **Bids descending, asks ascending** — mix these up and "best price" is wrong, and matching
  will cross the wrong orders. The `main` check above catches it.
- **Keep `totalQty` in sync** on *every* add/fill/remove, or depth drifts.
- **No `Instant.now()` anywhere in `engine/`** — order arrival is the `seq` counter
  (assigned where orders enter, next step), not the clock. Determinism depends on it.
- Money is `long` ticks — no `double`/`BigDecimal` on the hot path.

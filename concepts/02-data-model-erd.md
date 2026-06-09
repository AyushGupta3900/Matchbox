# 02 — Data Model & ERD

## What it is

A **data model** answers: *what "things" does our system store, and how are they related?*
An **ERD** (Entity-Relationship Diagram) is the picture of that — boxes for things,
lines for relationships.

This is the **most expensive doc to get wrong**. You can rewrite a function in an afternoon.
But once real data lives in a table, changing that table's shape means a *migration*: moving
live data, possibly downtime, possibly bugs that corrupt money. So we think hard here, on
paper, while it's free.

## The three building blocks

1. **Entity** — a "thing" worth storing. Becomes a table. (User, Order, Trade.)
2. **Attribute** — a fact about an entity. Becomes a column. (An Order has a `price`, a
   `side`, a `status`.)
3. **Relationship** — how two entities connect. Becomes a foreign key (or a join table).
   (An Order *belongs to* an Account.)

## Cardinality — the single most important idea here

Cardinality = *how many* of one thing relate to *how many* of another. Three shapes:

- **One-to-one (1:1)** — one User has exactly one... thing. Rare. (We use it for User↔Account.)
- **One-to-many (1:N)** — one Account has *many* Orders, but each Order belongs to *one*
  Account. This is the most common relationship. The "many" side holds a foreign key
  (`orders.account_id`).
- **Many-to-many (M:N)** — Students↔Courses. Needs a third "join" table in between. We
  mostly avoid these in v1.

Getting cardinality right *is* getting the schema right. "Can an order belong to two
accounts?" No → 1:N → foreign key on `orders`. Ask that question for every line.

## Primary keys and foreign keys

- **Primary key (PK)** — the unique ID of a row. Every table needs one. (`orders.id`.)
- **Foreign key (FK)** — a column that points at another table's PK, creating the
  relationship and letting the database *enforce* it (you can't have an order for an account
  that doesn't exist). (`orders.account_id → accounts.id`.)

## Normalization (just enough)

**Normalization** = don't store the same fact in two places. If an account's email lived on
every one of its orders, changing the email means updating thousands of rows — and they can
drift out of sync. So: store the email once on `accounts`, and orders just *point* to the
account. One fact, one home.

The rule of thumb: **each fact lives in exactly one place.** You'll sometimes *deliberately*
break this for speed (called denormalization) — but only after measuring, never by default.

## Three flavors of data (this shapes how we treat each table)

- **Reference / master data** — rarely changes, defines the world. (`assets`, `markets`.)
  You seed it once.
- **Transactional data** — the events of the business, append-heavy. (`orders`, `trades`,
  `ledger_entries`.) Grows forever; this is where volume and indexing matter.
- **State / balance data** — the *current* value of something, updated in place.
  (`balances`.) Small, hot, read and written constantly.

## Indexing strategy — *which* columns, and why

An **index** is like the index at the back of a book: instead of scanning every page (row)
to find "Order #4827 by account 12 that is still OPEN," the database jumps straight there.

The rule: **index the columns you filter, join, or sort by frequently.** For each table, ask
"what are the top queries?" then index for them.

- We'll constantly fetch *"all OPEN orders for account X"* → index `(account_id, status)`.
- We'll fetch *"recent trades for market BTC-USD"* → index `(market_id, executed_at)`.
- We look up an account by login email → unique index on `email`.

Indexes aren't free: they speed up reads but slow down writes (every insert must update every
index) and use space. So you index for *real* query patterns, not "just in case." On a hot,
append-heavy table, every extra index is a tax on every insert.

## Money: never floats (a data-model rule, not a style preference)

`0.1 + 0.2` is `0.30000000000000004` in floating point. On a system that moves money, that
rounding error is a bug that loses real money and breaks the "ledger sums to zero" invariant.

So: **every price and quantity is a `long` (64-bit integer)** counted in the smallest unit.
Price in **cents** ($60,000.00 → `6_000_000`). BTC quantity in **satoshis**
(1 BTC → `100_000_000`). The "scale" (how many decimals) lives on the `assets` table, used
only when *displaying* to a human.

## Double-entry, modeled

Accountants have a 500-year-old trick for never losing money: **every change is two entries
that sum to zero.** Deposit $100 → one entry +$100 to your balance, one entry −$100 to the
"external world / cash" account. They always balance. If you ever sum *all* ledger entries
and don't get zero, you have a bug — and you can detect it automatically.

In the model this becomes: a `ledger_entries` table where related entries share a
`transaction_id`, and the entries in each transaction sum to zero per asset.

## How to read our ERD

The next file ([docs/02-data-model-erd.md](../docs/02-data-model-erd.md)) has a Mermaid
diagram. Each box is a table; `PK` marks the primary key, `FK` marks a foreign key, and the
lines show cardinality (the crow's-foot `}o--||` notation: the "many" end has the splayed
foot).

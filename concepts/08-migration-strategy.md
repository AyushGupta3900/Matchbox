# 08 — Database Migration Strategy

## The problem migrations solve

Your schema changes over time — you add a column, a table, an index. The question is: *how
does that change get applied to every database (your laptop, a teammate's, staging, prod)
consistently, in order, without anyone running SQL by hand?* Hand-editing a live database is
how you get "works on my machine," missing columns in prod, and 2 AM data corruption.

A **migration** is a versioned, ordered, repeatable instruction for changing the schema. A
**migration tool** applies them, tracks which have run, and refuses to apply the same one
twice or run them out of order.

## How a migration tool works (Flyway/Liquibase)

- You write small change files, each with a **version**: `V1__create_accounts.sql`,
  `V2__add_orders.sql`, `V3__index_trades.sql`.
- The tool keeps a table in the DB (e.g. `flyway_schema_history`) recording which versions
  have been applied.
- On startup/deploy, it looks at that table, finds the unapplied migrations, and runs them
  **in version order, exactly once**, inside transactions.
- Every environment converges to the same schema by replaying the same ordered list. The
  schema becomes **code in your repo**, reviewable in pull requests, not a thing someone did
  by hand.

This is the whole idea: **the schema's history lives in version control**, and any database
can be brought to the current version deterministically.

## The golden rule: migrations are forward-only and immutable

Once a migration has run anywhere beyond your own machine, **you never edit it**. It's part of
history. Need to change something? Write a *new* migration. Editing an applied migration means
different databases have silently different schemas with the same version number — a
nightmare to debug.

So: small, frequent, append-only migrations. `V8` fixes what `V5` got wrong; you don't touch
`V5`.

## Rollback: forward-fix beats "down" scripts

Tools let you write a "down"/undo migration, but in practice **rolling back schema changes in
production is dangerous** — if data was written using the new column, undoing it loses data.
The mature approach is **roll forward**: if `V8` was bad, ship `V9` that corrects it, the same
way you'd never `git revert` by editing history. Keep migrations small so a bad one is cheap
to fix forward.

## Backward compatibility & zero-downtime: expand → migrate → contract

The trap: rename a column `price` → `price_ticks` in one migration, deploy, and for a few
seconds the *old* running app code looks for `price` (gone) while the *new* code looks for
`price_ticks`. Something breaks. The fix is the **expand–contract** (a.k.a. parallel-change)
pattern, done across *multiple* deploys:

1. **Expand** — add the new column `price_ticks` *alongside* the old one. Both exist. (Backward
   compatible: old code still works.)
2. **Migrate** — backfill `price_ticks` from `price`; deploy code that writes/reads the new
   column.
3. **Contract** — once nothing uses `price`, a later migration drops it.

Each step is independently safe. The principle: **a migration should never break the currently-
running version of the app.** This is *the* senior database skill, and it's why you decide the
strategy before you have data to lose.

## Reference / seed data

Some rows aren't user data — they *define the system*: our `assets` (USD, BTC) and `markets`
(BTC-USD). These are seeded by migrations too (`V_..__seed_assets.sql`), so every environment
starts with the same world. Keep seed data idempotent and versioned like everything else.

## Migrations vs. event-sourcing projections (our special case)

We have two kinds of state, and they migrate differently:

- **The ledger** (and other source-of-truth tables) — real, irreplaceable data. Changes go
  through careful expand–contract migrations. You cannot "rebuild" a ledger.
- **Projections / read models** (Phase 2: order/trade views derived from the Kafka event log) —
  these are *disposable*. To change their shape, you can often just **drop and rebuild them by
  replaying the event log**, no delicate migration needed. That's a hidden superpower of event
  sourcing: read-model schema changes get much cheaper.

Knowing which tables are precious (migrate carefully) vs rebuildable (drop & replay) is part of
the design — and it's why the data-model doc tagged some tables "projection (Phase 2)."

## Where migrations run

Migrations run **at deploy/startup, automatically**, before the app serves traffic — so the
code and the schema it expects always ship together. In dev that's on app boot; in CI it's
part of the test setup; in prod it's a gated deploy step. Never a human typing `ALTER TABLE`
against prod.

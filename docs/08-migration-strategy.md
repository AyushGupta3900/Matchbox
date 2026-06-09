# Migration Strategy — MatchBox Exchange

> Status: **Draft v1** · Last updated: 2026-06-09
> Read [concepts/08-migration-strategy.md](../concepts/08-migration-strategy.md) first.

## Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Tool | **Flyway** (versioned SQL migrations) | simple, SQL-first, great Spring Boot integration |
| Form | plain **`.sql`** files in the repo | reviewable in PRs; no ORM-generated surprises |
| Direction | **forward-only**; no down scripts | roll-forward fixes; down-migrations are unsafe with real data |
| When applied | **automatically at app startup** (and in CI test setup) | schema + code always ship together |
| Compatibility | **expand → migrate → contract** for any change to a live table | never break the running app version |
| Source of truth for schema | the migration files in `git` | not a hand-edited DB |

## File layout & naming

```
src/main/resources/db/migration/
├── V1__create_users_accounts.sql
├── V2__create_assets_markets.sql
├── V3__seed_assets_markets.sql          # reference data: USD, BTC, BTC-USD
├── V4__create_balances.sql
├── V5__create_ledger.sql                # transactions + ledger_entries
├── V6__create_orders.sql
├── V7__create_trades.sql
├── V8__create_api_keys.sql
└── R__refresh_some_view.sql             # (R = repeatable, runs when checksum changes)
```

- `V<n>__<snake_description>.sql` — **versioned**, runs **once**, in order.
- `R__<name>.sql` — **repeatable** (views, functions); re-runs when its content changes.
- Numbers are gap-friendly (`V1, V2, …`); never renumber an applied migration.

## The golden rules (enforced as team conventions)

1. **Never edit a migration that has run anywhere but your own machine.** Fix forward with a
   new `V<n+1>`.
2. **One logical change per migration**, small and focused — a bad one is then cheap to correct.
3. **Every migration leaves the DB in a working state** for the *currently deployed* app
   version (backward compatible). No "rename in place."
4. **Money tables (`ledger_entries`, `transactions`) are append-only** — migrations may add
   columns/indexes but must never rewrite historical rows. Corrections are new data, not schema
   surgery.

## Expand–contract playbook (use for any rename/type-change on a live table)

Example: change `orders.price` → `orders.price_ticks`.

| Step | Migration | App code | Safe because |
|------|-----------|----------|--------------|
| 1. Expand | `V_a` add nullable `price_ticks` | still reads/writes `price` | new column unused; old app fine |
| 2. Backfill + dual-write | `V_b` backfill `price_ticks = price` | deploy code that writes **both**, reads `price_ticks` | both columns valid |
| 3. Contract | `V_c` drop `price` (after no code uses it) | reads/writes only `price_ticks` | nothing references old column |

Each step is a separate migration **and** deploy. Never collapse them into one.

## Precious vs rebuildable tables (migrate differently)

| Table(s) | Class | Change strategy |
|----------|-------|-----------------|
| `users`, `accounts`, `api_keys`, `balances`, `transactions`, `ledger_entries` | **precious** (source of truth) | careful expand–contract; never lose data |
| `orders`, `trades` *(once they become Phase-2 projections of the event log)* | **rebuildable** | can **drop + replay** from Kafka to change shape |
| `candles` / OLAP (Phase 3, TimescaleDB) | **rebuildable** | recompute from trade events |

> In Phases 0–1, `orders`/`trades` are written directly and are *precious*. When Phase 2 makes
> them projections of the durable log, their migration cost drops — that's the event-sourcing
> payoff noted in the concept file.

## Seed / reference data
- `assets` (USD scale 2, BTC scale 8) and the `BTC-USD` `market` are seeded via a versioned
  migration (`V3`), so every environment boots with the same world.
- Seed migrations are written **idempotently** (`INSERT … ON CONFLICT DO NOTHING`) so a re-run
  is harmless.

## Environment behavior
- **dev:** Flyway runs on app boot against local Postgres (docker-compose).
- **test (CI):** migrations run against an ephemeral Postgres (Testcontainers) before tests —
  this also *tests the migrations themselves*.
- **prod (later):** migrations are a **gated deploy step** that runs before the new app version
  takes traffic. `flyway validate` in CI catches drift/edited migrations.

## How this interacts with other docs
- Implements the schema in [02-data-model-erd.md](02-data-model-erd.md) (each table → a `V` file).
- The append-only ledger rule echoes NFR-15 in [06-non-functional-requirements.md](06-non-functional-requirements.md).
- The rebuildable-projection idea comes from the event-sourcing architecture (Phase 2,
  [05-architecture.md](05-architecture.md)).

## Open questions
- [ ] Use **Testcontainers** for migration tests from Phase 0? (Proposal: yes — tests run
      against real Postgres, catching migration bugs early.)
- [ ] Baseline strategy if we ever adopt Flyway on an existing DB? (N/A now — greenfield.)
- [ ] Naming for Phase-2 event-store / snapshot tables — defer to that phase.

# Syntax 02 — Flyway Migrations & Postgres DDL

> Reference for **Step 0.2**. Read [concepts/12](../concepts/12-sql-ddl-and-constraints.md) and
> [concepts/08](../concepts/08-migration-strategy.md) first. The `users` table below is a
> worked example — use it as the template and write the rest yourself from
> [docs/02](../docs/02-data-model-erd.md).

## 1. Where migrations live & how they're named

```
src/main/resources/db/migration/
├── V1__create_users_and_accounts.sql
├── V2__create_assets.sql
├── V3__seed_assets.sql
├── V4__create_balances.sql
└── V5__create_ledger.sql
```
- `V<n>__<snake_description>.sql` — **two underscores** after the version. Runs once, in order.
- Flyway picks them up automatically on app start (we set `locations: classpath:db/migration`).
- **Never edit a file once it has run** — add a new `V<n+1>` instead (the golden rule, doc 08).

## 2. `CREATE TABLE` anatomy (Postgres)

```sql
CREATE TABLE table_name (
    column_name  TYPE  [constraints],          -- column-level constraints
    ...,
    CONSTRAINT name CHECK (...),               -- table-level constraints (can span columns)
    PRIMARY KEY (col_a, col_b)                 -- composite PK form
);
```

Type + constraint quick reference:
```sql
id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
email       text        NOT NULL UNIQUE,
status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
amount      bigint      NOT NULL CHECK (amount <> 0),
account_id  bigint      NOT NULL REFERENCES accounts(id),     -- FK, default ON DELETE RESTRICT
created_at  timestamptz NOT NULL DEFAULT now()
```

## 3. Worked example — `V1__create_users_and_accounts.sql`

This is the full, idiomatic shape. Study it, then write V2–V5 the same way.

```sql
-- V1: identity (users) and the financial actor (accounts), 1:1.
-- See docs/02-data-model-erd.md.

CREATE TABLE users (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         text        NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    status        text        NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE','DISABLED')),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint      NOT NULL UNIQUE REFERENCES users(id),  -- UNIQUE => enforces 1:1
    status     text        NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
```
> Note how `user_id ... UNIQUE REFERENCES users(id)` encodes the **1:1** cardinality from the
> ERD: the FK says "points at a real user," the `UNIQUE` says "no two accounts share one user."

## 4. Your turn — V2–V5 (write these; specs from docs/02)

**`V2__create_assets.sql`** — reference data. Columns (per docs/02 `ASSETS`):
`id int identity PK`, `symbol text NOT NULL UNIQUE`, `scale smallint NOT NULL`,
`name text NOT NULL`. (Markets/orders/trades come in Phase 1 — not now.)

**`V3__seed_assets.sql`** — insert the two assets, **idempotently** so a re-run is safe:
```sql
INSERT INTO assets (symbol, scale, name) VALUES
    ('USD', 2, 'US Dollar'),
    ('BTC', 8, 'Bitcoin')
ON CONFLICT (symbol) DO NOTHING;
```

**`V4__create_balances.sql`** — current holdings, one row per account+asset (docs/02 `BALANCES`):
- `account_id bigint NOT NULL REFERENCES accounts(id)`
- `asset_id int NOT NULL REFERENCES assets(id)`
- `available bigint NOT NULL DEFAULT 0`
- `reserved  bigint NOT NULL DEFAULT 0`
- `version   bigint NOT NULL DEFAULT 0`  (optimistic lock)
- composite `PRIMARY KEY (account_id, asset_id)`
- a `CHECK` enforcing **both** `available >= 0` and `reserved >= 0` (invariant #2 from docs/02)

**`V5__create_ledger.sql`** — the double-entry ledger (docs/02 `TRANSACTIONS` + `LEDGER_ENTRIES`):
- `transactions`: `id bigint identity PK`, `type text NOT NULL CHECK (...)`,
  `created_at timestamptz NOT NULL DEFAULT now()`.
- `ledger_entries`: `id`, `transaction_id bigint NOT NULL REFERENCES transactions(id)`,
  `account_id`, `asset_id` (FKs), `amount bigint NOT NULL CHECK (amount <> 0)`,
  `entry_type text NOT NULL CHECK (entry_type IN ('DEPOSIT','WITHDRAW','RESERVE','RELEASE','SETTLE'))`,
  `ref_id bigint` (nullable), `created_at timestamptz NOT NULL DEFAULT now()`.
- Add the indexes from docs/02 §indexing: `(account_id, asset_id, created_at)` and
  `(transaction_id)`.

> Reminder (concept 12): the **sum-to-zero per transaction** invariant can't be a `CHECK`
> (it spans rows) — that lives in the deposit service in 0.3. The schema enforces the
> single-row rules (`amount <> 0`, FKs, non-negative balances); the app enforces the rest.

## 5. Index syntax
```sql
CREATE INDEX idx_ledger_entries_account_asset_time
    ON ledger_entries (account_id, asset_id, created_at);
```
`UNIQUE`/`PRIMARY KEY` already create their own indexes — don't double up.

## 6. Run & verify

Migrations apply on app start, but you can also run them via the Flyway Maven plugin or just
boot the app:
```bash
docker compose up -d postgres-primary
./mvnw spring-boot:run            # Flyway runs V1..V5 on startup; watch the log
```
Look for log lines: `Migrating schema "public" to version "1 - create users and accounts"` …

Inspect the result with psql (password is `localdev`):
```bash
PGPASSWORD=localdev psql -h localhost -p 5433 -U matchbox -d matchbox

\dt                         -- list tables
\d+ ledger_entries          -- describe a table (columns, constraints, indexes)
SELECT * FROM flyway_schema_history;   -- what Flyway has applied
SELECT * FROM assets;       -- confirm the seed
```

## 7. When a migration fails (you will hit this)

- Flyway runs each migration in a transaction; a syntax error rolls that file back and the app
  won't start. **Fix the SQL and re-run** — Flyway retries the failed (unapplied) version.
- If a *bad* migration got recorded as failed, Flyway marks it; for local dev the fast reset is
  to wipe the DB and start clean:
  ```bash
  docker compose down -v        # -v drops the volume (all data) — DEV ONLY
  docker compose up -d postgres-primary
  ```
- **Never** `down -v` anything but local dev. In real environments you fix forward (doc 08).
- `Validate failed: ... checksum mismatch` → you edited an already-applied migration. Don't;
  add a new one. (Locally: `down -v` and rebuild.)

## 8. Common gotchas
- Forgetting the **double underscore** in the filename (`V1_create...` won't be recognized).
- Referencing a table before it's created — **order matters**; FKs require the target table to
  already exist (so `accounts` after `users`, `balances` after both).
- Using bare `timestamp` instead of `timestamptz`.
- Quoting: don't wrap identifiers in double quotes unless you must — unquoted names fold to
  lowercase, which is what we want.

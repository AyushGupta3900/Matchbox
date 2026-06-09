# 12 — SQL Schema Design & Constraints (DDL in practice)

## DDL vs DML

- **DDL** (Data *Definition* Language) — statements that define *structure*: `CREATE TABLE`,
  `ALTER TABLE`, `CREATE INDEX`. This is what our Flyway migrations contain.
- **DML** (Data *Manipulation* Language) — statements that move *data*: `INSERT`, `UPDATE`,
  `SELECT`, `DELETE`. This is what the app runs at runtime.

Step 0.2 is pure DDL: we turn the entities from [docs/02](../docs/02-data-model-erd.md) into
real tables.

## The big idea: the database is your last line of defense

Application code has bugs. The database doesn't care about your bugs — if you tell it
"`available` can never be negative," it will *refuse* the write, no matter what the app does.
**Constraints push correctness down to the one place every write must pass through.** A rule
enforced only in Java can be bypassed by a second service, a migration, a manual `UPDATE`, or
a bug. A rule in the schema cannot. So we encode as many invariants as the schema can express.

This is the practical payoff of the invariants we listed in
[docs/02 §invariants](../docs/02-data-model-erd.md): each one should become either a
constraint (if the DB can express it) or an explicit app-level check (if it can't).

## Postgres types we'll use (and why)

| Need | Type | Why |
|------|------|-----|
| money / quantity | **`bigint`** (8-byte int) | the "money is integers" rule (FR-12); holds ±9.2×10¹⁸ — satoshis & cents fit easily |
| surrogate id | **`bigint` identity** | big enough to never run out; see identity below |
| small fixed set (asset id) | `int` / `smallint` | few rows; saves nothing critical but signals intent |
| short text (email, symbol) | **`text`** | in Postgres `text` has no perf penalty vs `varchar`; add a `CHECK` for length if needed |
| enum-like (status, side) | **`text` + `CHECK`** | flexible to evolve; see "enums" below |
| timestamp | **`timestamptz`** | stores an instant in UTC; **never** use bare `timestamp` (it has no zone and bites you) |
| encrypted bytes (api secret) | `bytea` | raw binary |

> Rule: **money and ids are `bigint`; time is `timestamptz`; never `float`/`numeric` for
> money on the hot path; never bare `timestamp`.**

## Primary keys & identity

Every table needs a primary key. For surrogate keys, modern Postgres uses **identity
columns**:

```sql
id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY
```
Prefer this over the older `bigserial`. `GENERATED ALWAYS` means the DB assigns the value and
the app *cannot* override it by accident — safer. (`BY DEFAULT` allows override, used when
you import existing ids.)

For some tables the natural key is **composite** — e.g. `balances` is one row per
account+asset, so the PK is the *pair*:
```sql
PRIMARY KEY (account_id, asset_id)
```

## The constraint toolbox (this is the heart of 0.2)

| Constraint | Enforces | Example |
|------------|----------|---------|
| `NOT NULL` | a value must exist | `email text NOT NULL` |
| `UNIQUE` | no duplicates | `email text NOT NULL UNIQUE` |
| `PRIMARY KEY` | unique + not null + the row's identity | `... PRIMARY KEY` |
| `FOREIGN KEY ... REFERENCES` | the value must exist in another table | `account_id bigint NOT NULL REFERENCES accounts(id)` |
| `CHECK` | an arbitrary boolean rule | `available bigint NOT NULL CHECK (available >= 0)` |
| `DEFAULT` | a value when none is given | `created_at timestamptz NOT NULL DEFAULT now()` |

These turn our doc-level invariants into *enforced* ones:
- "balances never go negative" → `CHECK (available >= 0 AND reserved >= 0)`
- "an order belongs to a real account" → `FOREIGN KEY (account_id) REFERENCES accounts(id)`
- "one balance row per account+asset" → composite `PRIMARY KEY`
- "a ledger amount is never zero" → `CHECK (amount <> 0)`

## What the schema can't enforce (and where that logic goes)

Some invariants span *multiple rows* and a simple `CHECK` can't see them. The big one:
**"every transaction's ledger entries sum to zero."** A `CHECK` only sees one row at a time,
so it can't sum a group. Options:
1. **Enforce in the application** inside the same DB transaction (our v1 choice — simplest,
   and the service that writes the ledger is the only writer).
2. A **deferred constraint trigger** that checks the sum at `COMMIT` (more advanced; later).

Knowing *which* invariants the schema enforces vs which the app must enforce is a core skill.
Rule of thumb: **single-row rules → `CHECK`; cross-row/cross-table rules → app logic in a
transaction** (and document them, like we did in doc 02).

## Foreign keys & referential integrity

A foreign key makes the DB guarantee a reference is valid — you can't insert a balance for a
non-existent account, and (by default) you can't delete an account that still has balances.
You can tune the delete behavior:
- `ON DELETE RESTRICT` (default) — block the delete if children exist. **Right for money** —
  you never want to delete an account out from under its ledger.
- `ON DELETE CASCADE` — delete the children too. Convenient, dangerous for financial data;
  we avoid it on ledger/account tables.

## Enum-like columns: `text` + `CHECK`

For fields with a small fixed set (`side`, `status`, `entry_type`), we use `text` plus a
`CHECK`:
```sql
side text NOT NULL CHECK (side IN ('BUY','SELL'))
```
Why not a Postgres `ENUM` type? Enums are rigid — adding a value needs a migration and they're
awkward across tools. `text + CHECK` is easy to read, easy to extend (a one-line migration),
and plays nicely with JPA. (A lookup table is the third option, for when the set is large or
carries metadata — overkill here.)

## Indexes (DDL side; the *why* is in doc 02)

You declare indexes in migrations too:
```sql
CREATE INDEX idx_orders_account_status ON orders (account_id, status);
```
Unique constraints create an index automatically. Add the rest per the **query patterns** in
[docs/02 §indexing](../docs/02-data-model-erd.md) — index what you filter/join/sort by, not
"just in case."

## Naming conventions (pick one, never deviate)

- Tables: **plural snake_case** — `users`, `ledger_entries`.
- Columns: **singular snake_case** — `account_id`, `created_at`.
- PK: `id`. FK: `<referenced_singular>_id` — `account_id` → `accounts(id)`.
- Indexes: `idx_<table>_<cols>`. Constraints can be named for clearer errors:
  `CONSTRAINT chk_balance_nonneg CHECK (...)`.

Consistency here means you can guess a name without looking it up — which you'll do a thousand
times.

## How JPA relates (preview of 0.3)
Flyway **creates** the tables; in 0.3 you'll write JPA `@Entity` classes that **map** to them.
Because we set `ddl-auto: validate` (doc 09), Hibernate will *check* your entities match these
migrated tables on startup and fail loudly if they drift — Flyway owns the schema, entities
just mirror it.

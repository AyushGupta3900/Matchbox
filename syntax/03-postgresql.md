# Syntax 03 — PostgreSQL & psql Cheat-Sheet

> A practical reference for poking at our database during development. `psql` is the official
> command-line client for Postgres. Our dev DB runs in Docker on **host port 5433** (see
> [syntax/01](01-project-scaffold.md)); user/db/password are `matchbox` / `matchbox` / `localdev`.

## 1. Connecting

```bash
# Interactive session (prompts stay open until \q)
PGPASSWORD=localdev psql -h localhost -p 5433 -U matchbox -d matchbox

# One-shot: run a command and exit (-c)
PGPASSWORD=localdev psql -h localhost -p 5433 -U matchbox -d matchbox -c "\dt"
PGPASSWORD=localdev psql -h localhost -p 5433 -U matchbox -d matchbox -c "SELECT * FROM assets;"

# Run a whole .sql file (-f)
PGPASSWORD=localdev psql -h localhost -p 5433 -U matchbox -d matchbox -f some_script.sql
```
Flags: `-h` host · `-p` port · `-U` user · `-d` database · `-c` command · `-f` file ·
`-t` tuples-only (no headers) · `-A` unaligned (good for scripting).

> Tip: set `export PGPASSWORD=localdev` once in your shell session to drop the prefix.
> Or create a `~/.pgpass` file so you never type it.

## 2. psql meta-commands (start with `\`, sent to psql, not the DB)

| Command | Shows |
|---------|-------|
| `\dt` | list tables |
| `\dt+` | tables + size + description |
| `\d <table>` | a table's columns + types |
| `\d+ <table>` | + constraints, indexes, defaults (use this to verify a migration) |
| `\di` | indexes |
| `\dn` | schemas |
| `\l` | databases |
| `\du` | roles / users |
| `\dv` | views |
| `\df` | functions |
| `\x` | toggle expanded display (one column per line — great for wide rows) |
| `\timing` | toggle showing how long each query took |
| `\e` | open the last query in your `$EDITOR` |
| `\conninfo` | what you're connected to |
| `\! <cmd>` | run a shell command without leaving psql |
| `\?` | help for meta-commands |
| `\h <SQL>` | SQL syntax help, e.g. `\h CREATE TABLE` |
| `\q` | quit |

## 3. Common SQL (DML — runtime data operations)

```sql
-- Read
SELECT * FROM balances WHERE account_id = 1;
SELECT asset_id, available FROM balances WHERE account_id = 1 ORDER BY asset_id;
SELECT count(*) FROM ledger_entries;

-- Insert (RETURNING gives you back generated columns like id)
INSERT INTO transactions (type) VALUES ('DEPOSIT') RETURNING id;

-- Update
UPDATE balances SET available = available + 100
 WHERE account_id = 1 AND asset_id = 1;

-- Delete (rare for us — ledger is append-only!)
DELETE FROM some_table WHERE id = 5;
```

### Aggregation we'll actually use (the reconciliation query)
```sql
-- Invariant #1: the whole ledger must sum to zero per asset
SELECT asset_id, sum(amount) AS net
FROM ledger_entries
GROUP BY asset_id;
-- every row should show net = 0
```

## 4. Transactions (atomic groups — preview of the deposit logic)

```sql
BEGIN;                                   -- start a transaction
  INSERT INTO transactions (type) VALUES ('DEPOSIT') RETURNING id;   -- say it's id 42
  INSERT INTO ledger_entries (transaction_id, account_id, asset_id, amount, entry_type)
       VALUES (42, 0, 1, -10000, 'DEPOSIT'),     -- system account: -100.00
              (42, 1, 1, +10000, 'DEPOSIT');     -- user account:   +100.00
  UPDATE balances SET available = available + 10000
   WHERE account_id = 1 AND asset_id = 1;
COMMIT;                                  -- all of it lands, or...
-- ROLLBACK;                            -- ...none of it does
```
Everything between `BEGIN` and `COMMIT` is **all-or-nothing**. A crash before `COMMIT` leaves
the DB untouched. This is what keeps the books balanced (concept: database transactions, 0.3).

## 5. Inspecting our project state

```sql
-- What migrations has Flyway applied?
SELECT version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank;

-- Confirm the seed
SELECT * FROM assets;

-- See a table's full definition (psql meta-command)
\d+ ledger_entries
```

## 6. Data type quick reference (what we use)

| Type | Use |
|------|-----|
| `bigint` | money/quantity (smallest unit) and ids — 8 bytes |
| `int` / `smallint` | small reference ids / scales |
| `text` | strings (no length penalty in Postgres) |
| `timestamptz` | an instant in UTC — **always** this, never bare `timestamp` |
| `boolean` | true/false flags |
| `bytea` | raw binary (e.g. encrypted secrets) |
| `numeric(p,s)` | exact decimals — we **avoid** for money (we use integer `bigint`) |

## 7. Working with the Docker container

```bash
docker compose up -d postgres-primary      # start
docker compose ps                          # status + health
docker compose logs -f postgres-primary    # follow DB logs
docker compose down                        # stop (keeps data)
docker compose down -v                     # stop + WIPE data (dev reset only!)

# psql *inside* the container (no host port needed)
docker exec -it matchbox-postgres psql -U matchbox -d matchbox
```

## 8. Handy session settings
```sql
\x auto        -- auto-switch to expanded display for wide results
\timing on     -- show query durations
SET search_path TO public;   -- default schema (already public for us)
```

## 9. Gotchas
- **`localhost` resolves to IPv6 `::1` first on macOS** — if a *local* Postgres also runs on
  5432, you'll hit the wrong one. We use **5433** for the container to avoid this.
- Unquoted identifiers fold to **lowercase** (`MyTable` becomes `mytable`). Don't double-quote
  names unless you must.
- A missing semicolon in interactive psql leaves you on a `...>` continuation prompt — finish
  the statement or press `Ctrl+C`.
- `psql` not installed? It ships with the Postgres client tools (you have `postgresql@16` via
  Homebrew), or use the in-container form in §7.

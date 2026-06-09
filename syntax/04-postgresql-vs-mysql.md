# Syntax 04 — PostgreSQL vs MySQL (dialects, commands & Postgres-only syntax)

> Reference. Both are relational databases that speak SQL, but each has its own **dialect** —
> the common SQL core is portable, the useful extras are not. We use **PostgreSQL**; this file
> explains how it differs from **MySQL** (the other database we considered) and shows syntax
> that exists in Postgres only. See also [syntax/03-postgresql.md](03-postgresql.md).

## 1. The big picture

| | **PostgreSQL** | **MySQL** |
|--|----------------|-----------|
| Type | "object-relational" DB, standards-focused | relational DB, speed/simplicity-focused |
| Reputation | richer features, stricter correctness | very fast reads, ubiquitous in web apps |
| Default port | **5432** | **3306** |
| Client CLI | `psql` | `mysql` |
| Our use | **chosen** (correctness-critical money ledger) | not chosen |
| DDL in transactions | **yes** — a failed migration rolls back cleanly | **no** (mostly) — DDL auto-commits, can leave you half-migrated |

That last row is a real reason we picked Postgres: remember V4 failing and rolling back with
*no trace*? On MySQL, a failed `CREATE TABLE` mid-migration can leave partial changes behind.

## 2. Data types

| Need | PostgreSQL | MySQL |
|------|------------|-------|
| auto-increment id | `bigint GENERATED ALWAYS AS IDENTITY` (or `bigserial`) | `bigint AUTO_INCREMENT` |
| integer (8-byte) | `bigint` | `BIGINT` |
| exact decimal | `numeric(p,s)` | `DECIMAL(p,s)` |
| string | `text` (no length cost) | `VARCHAR(n)` / `TEXT` |
| timestamp w/ zone | **`timestamptz`** (true UTC instant) | `TIMESTAMP` (limited tz), `DATETIME` (none) |
| boolean | `boolean` (`true`/`false`) | `TINYINT(1)` (0/1; no real boolean) |
| binary | `bytea` | `BLOB` / `VARBINARY` |
| JSON | `json` and **`jsonb`** (indexed, binary) | `JSON` (no `jsonb` equivalent) |
| array | **native arrays** `int[]`, `text[]` | ❌ none (emulate with JSON) |
| enum | `text + CHECK`, or `CREATE TYPE ... AS ENUM` | `ENUM('a','b')` inline |
| UUID | `uuid` (native) | `CHAR(36)` / `BINARY(16)` |

## 3. Command / syntax differences (side by side)

### Auto-increment column
```sql
-- PostgreSQL
id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY
-- MySQL
id BIGINT AUTO_INCREMENT PRIMARY KEY
```

### Insert + get the new id back
```sql
-- PostgreSQL: RETURNING (in ONE statement)
INSERT INTO transactions (type) VALUES ('DEPOSIT') RETURNING id;
-- MySQL: separate call
INSERT INTO transactions (type) VALUES ('DEPOSIT');
SELECT LAST_INSERT_ID();
```

### Upsert / insert-or-ignore
```sql
-- PostgreSQL
INSERT INTO assets (symbol, scale, name) VALUES ('USD', 2, 'US Dollar')
ON CONFLICT (symbol) DO NOTHING;
INSERT INTO assets (symbol, scale, name) VALUES ('USD', 2, 'US Dollar')
ON CONFLICT (symbol) DO UPDATE SET name = EXCLUDED.name;     -- upsert
-- MySQL
INSERT IGNORE INTO assets (symbol, scale, name) VALUES ('USD', 2, 'US Dollar');
INSERT INTO assets (symbol, scale, name) VALUES ('USD', 2, 'US Dollar')
ON DUPLICATE KEY UPDATE name = VALUES(name);                 -- upsert
```

### Current timestamp default
```sql
-- PostgreSQL
created_at timestamptz NOT NULL DEFAULT now()
-- MySQL
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
```

### String concatenation
```sql
-- PostgreSQL (standard SQL): || operator, or concat()
SELECT 'a' || 'b';            SELECT concat('a','b');
-- MySQL: || means OR by default(!); must use concat()
SELECT concat('a','b');
```

### Case-insensitive match
```sql
-- PostgreSQL: ILIKE (case-insensitive LIKE)
SELECT * FROM users WHERE email ILIKE 'ADA@%';
-- MySQL: LIKE is case-insensitive by default (depends on collation)
SELECT * FROM users WHERE email LIKE 'ADA@%';
```

### Limit / pagination (same here, both support LIMIT/OFFSET)
```sql
SELECT * FROM trades ORDER BY id DESC LIMIT 50 OFFSET 100;   -- both
```

### Quoting identifiers
```sql
-- PostgreSQL: double quotes for identifiers, single quotes for strings
SELECT "myColumn" FROM "myTable" WHERE name = 'value';
-- MySQL: backticks for identifiers
SELECT `myColumn` FROM `myTable` WHERE name = 'value';
```

### Boolean
```sql
-- PostgreSQL: real booleans
is_system boolean NOT NULL DEFAULT false        -- ... WHERE is_system IS TRUE
-- MySQL: TINYINT(1), compare to 0/1
is_system TINYINT(1) NOT NULL DEFAULT 0         -- ... WHERE is_system = 1
```

## 4. PostgreSQL-only (or Postgres-standout) syntax worth knowing

These are features we can lean on *because* we chose Postgres — MySQL has no equivalent or a
weaker one.

### `RETURNING` — get data back from a write
```sql
INSERT INTO accounts (user_id) VALUES (1) RETURNING id, created_at;
UPDATE balances SET available = available + 100
 WHERE account_id = 1 AND asset_id = 1
 RETURNING available;          -- get the new balance without a second query
DELETE FROM nonces WHERE expires_at < now() RETURNING id;
```

### `ON CONFLICT` — true upsert (see §3) — atomic insert-or-update.

### `jsonb` — indexed binary JSON (useful later for event payloads)
```sql
CREATE TABLE events (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payload jsonb);
SELECT * FROM events WHERE payload->>'type' = 'Trade';        -- ->> extracts text
SELECT * FROM events WHERE payload @> '{"side":"BUY"}';       -- @> contains
CREATE INDEX idx_events_payload ON events USING gin (payload); -- index inside JSON
```

### Native arrays
```sql
CREATE TABLE t (tags text[]);
INSERT INTO t (tags) VALUES (ARRAY['a','b']);
SELECT * FROM t WHERE 'a' = ANY(tags);
```

### CTEs (`WITH`) — name a subquery, even recursively
```sql
WITH recent AS (
  SELECT * FROM trades WHERE executed_at > now() - interval '1 hour'
)
SELECT market_id, count(*) FROM recent GROUP BY market_id;
```
(MySQL 8 has CTEs too, but Postgres's are more capable — e.g. writable CTEs with `RETURNING`.)

### Window functions (`OVER`) — running totals, rankings
```sql
SELECT id, amount,
       sum(amount) OVER (ORDER BY id) AS running_balance
FROM ledger_entries WHERE account_id = 1;
```
(Both have these now, but they're a Postgres strength — great for candles/analytics later.)

### `DISTINCT ON` — first row per group (Postgres-only)
```sql
-- latest trade per market, in one line
SELECT DISTINCT ON (market_id) market_id, price_ticks, executed_at
FROM trades ORDER BY market_id, executed_at DESC;
```

### `GENERATED ALWAYS AS (...) STORED` — computed columns
```sql
total bigint GENERATED ALWAYS AS (available + reserved) STORED
```

### Partial & expression indexes (Postgres-only flexibility)
```sql
-- index only the rows we query, smaller + faster
CREATE INDEX idx_open_orders ON orders (account_id)
  WHERE status IN ('NEW','PARTIALLY_FILLED');
-- index on an expression
CREATE INDEX idx_users_lower_email ON users (lower(email));
```

### Range & interval types, `EXCLUDE` constraints, materialized views, full-text search,
extensions (`CREATE EXTENSION`), `LISTEN/NOTIFY` (pub/sub) — all Postgres features MySQL lacks
or implements differently. We'll meet some (e.g. `interval` for candle windows) later.

### Transactional DDL (the one that saved us)
```sql
BEGIN;
  CREATE TABLE foo (...);
  ALTER TABLE bar ADD COLUMN x int;
ROLLBACK;          -- both undone cleanly. MySQL would have auto-committed each.
```

## 5. Client CLI: `psql` vs `mysql`

| Task | psql (Postgres) | mysql (MySQL) |
|------|-----------------|---------------|
| connect | `psql -h host -p 5432 -U user -d db` | `mysql -h host -P 3306 -u user -p db` |
| list databases | `\l` | `SHOW DATABASES;` |
| list tables | `\dt` | `SHOW TABLES;` |
| describe table | `\d+ t` | `DESCRIBE t;` / `SHOW CREATE TABLE t;` |
| switch database | `\c otherdb` | `USE otherdb;` |
| quit | `\q` | `quit` / `\q` |
| run file | `\i file.sql` or `-f file.sql` | `source file.sql` or `< file.sql` |

Note: psql's `\`-commands are unique to psql. MySQL uses `SHOW`/`DESCRIBE` **SQL** statements
instead of client meta-commands.

## 6. Why PostgreSQL for MatchBox
- **Transactional DDL** — migrations fail cleanly (we saw this).
- **Rich constraints** — the `CHECK`s guarding our balances/ledger are first-class.
- **`timestamptz` done right** — correct UTC instants for trades/events.
- **Strong concurrency (MVCC)** + serializable isolation — matters for a money ledger.
- **`jsonb`, arrays, partial indexes, window functions** — pay off in the event store, market
  data, and analytics phases.
MySQL is excellent for many web apps; Postgres is the better fit for **correctness-critical
financial data**, which is the whole point of this project.

## 7. Portability rule of thumb
- **Standard SQL core** (`CREATE TABLE`, `SELECT`, `JOIN`, `WHERE`, `NOT NULL`, `CHECK`,
  `PRIMARY KEY`, `FOREIGN KEY`, `LIMIT`) → works on both.
- **Anything in §4, plus identity/upsert/types in §2–3** → dialect-specific.
- A migration targets **one** engine. Switching engines = rewriting the dialect-specific parts
  + swapping driver, URL, Flyway module, and Docker image (a matched set).

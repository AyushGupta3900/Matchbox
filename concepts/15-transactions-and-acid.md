# 15 — Database Transactions & ACID

## Why this is the most important concept in Phase 0

A deposit isn't one action — it's **several** writes that must all happen together:
1. insert a `transactions` row,
2. insert two `ledger_entries` (system −100, user +100),
3. update the user's `balances`.

What if the app crashes after step 2 but before step 3? You'd have ledger entries but a stale
balance — the books wouldn't add up. Money would appear lost or invented. **A transaction makes
those writes all-or-nothing**, so that half-done state can never exist. For a system that moves
money, this isn't optional — it's the whole game.

## What a database transaction is

A **transaction** is a group of operations the database treats as a single, indivisible unit:
```sql
BEGIN;
  ... several INSERTs / UPDATEs ...
COMMIT;     -- everything becomes permanent, together
-- or
ROLLBACK;   -- everything is undone, as if it never happened
```
Until `COMMIT`, nothing is permanent. A crash before `COMMIT` = automatic `ROLLBACK`. After
`COMMIT`, it survives crashes. There is no in-between.

## ACID — the four guarantees

Transactions give you **ACID**:

- **A — Atomicity.** All-or-nothing. Every write in the transaction happens, or none does. (This
  is the one that protects our deposit.)
- **C — Consistency.** The transaction takes the DB from one valid state to another — all your
  constraints (`CHECK`, `FK`, `PK`) hold at commit. A transaction that would violate a
  constraint is rejected whole.
- **I — Isolation.** Concurrent transactions don't see each other's half-finished work. Two
  deposits running at once behave as if they ran one after another (to the degree your
  *isolation level* promises — see below).
- **D — Durability.** Once committed, it survives power loss / crash (Postgres writes it to a
  durable log first).

Memorize ACID — it's the vocabulary for *why* a database is safe to put money in, and the thing
the matching engine deliberately can't rely on (it's in-memory) which is why events are the
truth there.

## `@Transactional` in Spring — the easy button

You rarely write `BEGIN/COMMIT` by hand. You annotate the **service method**:
```java
@Service
class DepositService {
    @Transactional                       // <- wraps the whole method in one DB transaction
    public void deposit(long accountId, int assetId, long amount) {
        // insert transaction row
        // insert two ledger entries
        // update balance
    }   // method returns normally -> COMMIT;  throws an exception -> ROLLBACK
}
```
Spring opens a transaction when the method starts, **commits** if it returns normally, and
**rolls back** if it throws a `RuntimeException`. So if *any* step throws, *all* steps undo.
That's atomicity with one annotation.

Key rules / gotchas:
- Put `@Transactional` on the **service**, not the controller or repository — the service is the
  business operation, which is the right transaction boundary.
- By default Spring rolls back on `RuntimeException`/`Error`, **not** on checked exceptions.
  Prefer unchecked exceptions for failures, or configure `rollbackFor`.
- **Self-invocation doesn't work**: calling another `@Transactional` method on `this` bypasses
  the proxy. Call across beans.
- Keep transactions **short** — they hold locks. Never do slow I/O (HTTP calls) inside one.

## Isolation levels (just enough for now)

Isolation has levels that trade safety for concurrency. Postgres default is **READ COMMITTED**
(you only see committed data). Stronger levels (`REPEATABLE READ`, `SERIALIZABLE`) prevent more
anomalies at the cost of more conflicts/retries. For Phase 0 the default is fine; we'll revisit
when concurrent orders hit the same balance.

## Optimistic locking — our `version` column

Two requests try to update the same balance at the same time. Without protection, one can
silently overwrite the other ("lost update"). Our `balances.version` column enables **optimistic
locking**:
```java
@Version
long version;     // Hibernate auto-increments it and checks it on update
```
How it works: every update does `... WHERE id = ? AND version = ?` and bumps `version`. If
someone else changed the row first, the version won't match, **zero rows update**, and Hibernate
throws `OptimisticLockException` — you retry. "Optimistic" = assume conflicts are rare, detect
them instead of locking up front. (The alternative, **pessimistic** locking with `SELECT ... FOR
UPDATE`, locks the row immediately — heavier; for later.)

## How this enforces the invariant the schema can't

Remember from concept 13: the DB can't `CHECK` that a transaction's ledger entries sum to zero
(it spans rows). So the **deposit service enforces it in code, inside the transaction**:
- compute the two entries so they sum to zero,
- write them + the balance update,
- if anything is wrong, throw → the whole transaction rolls back → no unbalanced state ever
  persists.

The transaction is what makes app-enforced invariants *safe*: either the complete, correct set
of changes commits, or nothing does.

## The mental model
> A transaction draws a box around several writes and says "all of these, or none." `@Transactional`
> draws that box around a service method. Inside the box you can build a multi-step change (like a
> balanced double-entry deposit) and trust it will never be left half-done.

# Tech-Debt & Deferred Improvements

> A running log of deliberate shortcuts and "do it better later" decisions. Each entry says
> **what**, **why deferred**, **what to do**, and **where** — so a future session can pick it up
> with full context. Add to this whenever we knowingly defer an improvement.

| # | Item | Status | Logged |
|---|------|--------|--------|
| 1 | Replace `String` status/type fields with Java enums | ⏳ deferred | 2026-06-09 |

---

## 1. Use Java enums for status/type fields (instead of `String`)

**What:** Several entity fields that hold a small fixed set of values are currently mapped as
plain `String`:
- `LedgerEntry.entryType`  → should be `enum EntryType { DEPOSIT, WITHDRAW, RESERVE, RELEASE, SETTLE }`
- `LedgerTransaction.type` → should be `enum TxType { DEPOSIT, WITHDRAW, ORDER_RESERVE, RELEASE, TRADE_SETTLE }`
- `User.status`, `Account.status` → should be `enum AccountStatus { ACTIVE, DISABLED }`
- (later) `Order.side`, `Order.type`, `Order.status` when those entities exist (Phase 1)

**Why it's better:** a `String` lets a typo (`"DEPOSITt"`) compile and only fail at runtime
against the DB `CHECK`. A Java enum gives **compile-time safety**, autocomplete, a fixed set,
and refactor-safety. The DB stays `text + CHECK` (defense in depth) — the two layers reinforce
each other.

**Why deferred:** to keep the first Phase-0 cut minimal and moving. Strings work; this is a
quality/safety upgrade, not a correctness blocker.

**What to do (when we pick it up):**
1. Define each enum in the relevant feature's `domain/` package.
2. Change the entity field type and annotate:
   ```java
   @Enumerated(EnumType.STRING)   // store the NAME, NOT ordinal — ordinal corrupts on reorder
   @Column(nullable = false)
   private EntryType entryType;
   ```
3. Update services/tests that set these to use the enum constants (`EntryType.DEPOSIT`).
4. Enum constant names must **exactly match** the strings in the DB `CHECK` lists (since
   `STRING` stores `name()`).
5. **No DB migration needed** — the columns stay `text`. Boot afterward; `ddl-auto: validate`
   confirms the mapping still fits.

**Where:** `account/domain/`, `settlement/domain/`, and the `DepositService`.
See [concepts/14-orm-jpa-entities.md](../concepts/14-orm-jpa-entities.md) (enum mapping) and
[concepts/12-sql-ddl-and-constraints.md](../concepts/12-sql-ddl-and-constraints.md) (text+CHECK).

# Syntax 06 — Service Layer & Transactions (the deposit)

> Reference for **Step 0.3**. Read [concepts/15](../concepts/15-transactions-and-acid.md) and
> [concepts/13](../concepts/13-double-entry-and-system-accounts.md) first. This is where the
> double-entry deposit comes together. You write the service; the shapes below guide you.

## 0. First: seed the system account (migration V6)

The "outside world" account from concept 13. Add a migration, e.g.
`V6__seed_system_account.sql`. We need a system user + a system account with a **known id** so
the deposit code can reference it. One simple approach — reserve id 1 by seeding first:

```sql
-- a system user + account representing "the outside world" (concept 13)
INSERT INTO users (email, password_hash, status)
VALUES ('system@matchbox.internal', 'x', 'DISABLED')
ON CONFLICT (email) DO NOTHING;

INSERT INTO accounts (user_id, status)
SELECT id, 'ACTIVE' FROM users WHERE email = 'system@matchbox.internal'
ON CONFLICT (user_id) DO NOTHING;
```
Then the deposit code looks up the system account id once (or you store it in config). The
system account is the one that may go "negative" in the ledger — and per concept 13 (Option A)
we **don't** keep a `balances` row for it; it lives only in `ledger_entries`.

> Tip: a `boolean is_system` column on `accounts` would make this explicit. If you want it,
> that's another small migration (`ALTER TABLE accounts ADD COLUMN is_system boolean NOT NULL
> DEFAULT false;`) — optional for v1.

## 1. The service skeleton (`@Service` + constructor injection)

```java
package com.matchbox.settlement.service;

import lombok.RequiredArgsConstructor;       // generates the constructor for final fields
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor                     // DI with zero boilerplate (concept 10)
public class DepositService {

    private final LedgerTransactionRepository txRepo;
    private final LedgerEntryRepository entryRepo;
    private final BalanceRepository balanceRepo;
    // + a way to know the system account id (config value or a lookup)

    @Transactional                           // the whole method = ONE DB transaction (concept 15)
    public void deposit(long userAccountId, int assetId, long amount) {
        // ... steps below ...
    }
}
```

## 2. What `deposit(...)` must do (you implement; logic spelled out)

All inside the one `@Transactional` method, so it's atomic:

1. **Validate** `amount > 0` (else throw — rolls back). Cheap guard up front (fail fast, doc 07).
2. **Create the transaction row:**
   ```java
   LedgerTransaction tx = new LedgerTransaction();
   tx.setType("DEPOSIT");
   tx.setCreatedAt(Instant.now());
   tx = txRepo.save(tx);                 // save() returns the entity WITH its generated id
   long txId = tx.getId();
   ```
3. **Create the two balanced entries** (they must sum to zero — concept 13):
   ```java
   // system account: -amount   (value leaves the outside world)
   entryRepo.save(newEntry(txId, systemAccountId, assetId, -amount, "DEPOSIT"));
   // user account:   +amount   (value arrives for the user)
   entryRepo.save(newEntry(txId, userAccountId,  assetId, +amount, "DEPOSIT"));
   ```
4. **Update the user's balance** (create the row if first time for this asset):
   ```java
   BalanceId id = new BalanceId(); id.setAccountId(userAccountId); id.setAssetId(assetId);
   Balance bal = balanceRepo.findById(id).orElseGet(() -> { /* new Balance with 0/0 */ });
   bal.setAvailable(bal.getAvailable() + amount);
   balanceRepo.save(bal);
   ```
   (We do **not** touch a balance row for the system account — Option A, concept 13.)

If any step throws, Spring rolls the whole thing back → no half-done deposit ever persists.

> Note: `Instant.now()` is fine **here** (settlement is not the deterministic hot path). The
> *matching engine* must never call `now()` — that rule is Phase 1, not this service.

## 3. `@Transactional` rules to respect (concept 15)
- It's on the **service** method (the business operation), not the controller/repository.
- Rolls back on `RuntimeException` by default. Throw unchecked exceptions for failures (e.g. a
  `ValidationException extends RuntimeException`).
- Don't call one `@Transactional` method from another method *in the same class* (self-call
  bypasses the proxy — concept 15).
- Keep it short: no HTTP calls or sleeps inside.

## 4. A deposit endpoint (controller — thin)

```java
@RestController
@RequestMapping("/v1/wallet")
@RequiredArgsConstructor
public class WalletController {
    private final DepositService depositService;

    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(@RequestBody @Valid DepositRequest req /*, auth principal */) {
        depositService.deposit(/* accountId from auth */, req.assetId(), req.amount());
        return ResponseEntity.status(201).build();
    }
}
```
The controller just parses/validates and delegates — **no business logic** (doc 10). Real auth
(getting the account id from the JWT) arrives in step 0.4; for now you can pass a test account id.

> Amounts on the wire are **strings** of integers (API contract, doc 03) — parse to `long` at
> the edge. A `record DepositRequest(int assetId, @NotNull String amount)` + parse works.

## 5. Proving it (the Phase-0 "done when")

After a deposit, the ledger must still sum to zero per asset:
```java
@Query("select coalesce(sum(e.amount),0) from LedgerEntry e where e.assetId = :assetId")
long sumAmountByAsset(int assetId);   // must return 0 for every asset, always
```
Or check directly in SQL (syntax/03):
```sql
SELECT asset_id, sum(amount) AS net FROM ledger_entries GROUP BY asset_id;  -- net must be 0
```
Write a test (next: integration testing) that deposits, then asserts `sumAmountByAsset == 0`
and the user's `available` increased by the amount.

## 6. Optimistic-lock retry (when concurrent updates collide)
Because `Balance` has `@Version`, a concurrent update can throw `OptimisticLockException`. For
v1, a small retry around the service call is enough:
```java
// pseudo: try deposit; on OptimisticLockingFailureException, retry a couple of times
```
We'll formalize this when concurrent orders hit the same balance (later phase). Just know *why*
it can happen.

## Order to build it
1. `V6` system-account migration → boot, confirm it seeded.
2. Entities + repositories (syntax/05) → boot, `validate` passes.
3. `DepositService` → unit/integration test the zero-sum invariant.
4. `WalletController` → hit it with `curl` (mind the security 401 until 0.4; you can permit
   `/v1/wallet/**` temporarily or test the service directly).

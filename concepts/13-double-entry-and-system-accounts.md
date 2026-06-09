# 13 — Double-Entry & the System Account

## The law double-entry enforces

Double-entry accounting has one iron rule: **money is never created or destroyed, only
moved.** Every transaction moves value *from* somewhere *to* somewhere. So every transaction
is a set of entries that **sum to zero** — what one account loses, another gains.

That's why we modeled `ledger_entries` with a *signed* `amount`: `+100` means "this account
gained 100" (a credit), `-100` means "this account lost 100" (a debit). A valid transaction's
entries add up to exactly `0`.

## The puzzle

A user deposits $100. The obvious entry is:

```
user account:  +100      (their balance goes up)
```

But that's **one** entry, and it sums to `+100`, not `0`. It looks like we just *created*
$100 out of nothing — which double-entry forbids. So where's the matching `−100`?

The instinct "just add the balance" is single-entry thinking. It works until it doesn't: with
single entries you can never answer "does the whole system balance?" because there's nothing
to balance *against*.

## The answer: an account for "the outside world"

The $100 didn't appear from nowhere — it came **from outside the exchange** (a bank, a card,
in our v1 a simulated deposit). So we need an account that *represents the outside world*.
Call it the **system account** (a.k.a. house account, external account, control account).

A deposit becomes two entries that balance:

```
transaction: DEPOSIT
  system account:  -100      (value flowed OUT of "the outside world")
  user account:    +100      (value flowed IN to the user)
  -----------------------
  sum:                0      ✅
```

Read it as a *movement*: $100 moved **from** the outside world **into** the user's account.
Nothing was created — it was transferred across the boundary of the exchange. A withdrawal is
the mirror:

```
transaction: WITHDRAW
  user account:    -100
  system account:  +100
  sum:                0      ✅
```

## What the system account's balance *means*

If you sum the system account's entries over time, you get the **negative of everything the
exchange is currently holding for users**. Deposits push it more negative; withdrawals pull it
back toward zero. So:

```
system account balance  =  −(total user holdings)
```

That's not a bug — it's a feature. It means the books always balance globally:
**`SUM(every account's balance) = 0`** for each asset, at all times. If that sum is ever
non-zero, money leaked or was invented — and you can detect it with one query (this is
invariant #1 from [docs/02](../docs/02-data-model-erd.md), and the nightly reconciliation in
FR-23).

## The wrinkle: our non-negative `CHECK`

We put `CHECK (available >= 0)` on `balances` — a user can't go negative (you can't spend money
you don't have). But the **system account is supposed to go negative** (it mirrors all user
holdings). Those two facts collide. Two clean ways to resolve it:

**Option A (v1 choice): the system account lives only in the ledger, not in `balances`.**
- `balances` holds **user** positions only — the non-negative rule stays meaningful and
  protects real users.
- The system account appears in `ledger_entries` (so every transaction still balances), but we
  don't materialize a `balances` row for it.
- Global check still works: `SUM(ledger_entries.amount)` per asset = 0 (all entries, user and
  system, are in the ledger).

**Option B: give the system account a balance row but exempt it from the check.**
- More uniform (every account has a balance), but the constraint needs to know which account is
  the system one (a flag + a partial/conditional check). More machinery.

We take **Option A** for v1: simplest, keeps the user-protecting invariant clean, and the
system account's position is always derivable from the ledger when we need it.

## How this lands in the schema/code (preview of 0.3)

- **Seed one system account** (a migration) — a reserved `accounts` row representing the
  outside world. Optionally tag accounts with `is_system boolean` so code/queries can tell them
  apart.
- The **deposit service** writes, in one DB transaction: a `transactions` row + two
  `ledger_entries` (system `−amount`, user `+amount`), then bumps the user's `balances`.
- The whole thing is **atomic**: both entries and the balance update commit together, or none
  do. (That's the next concept — database transactions.)

## The mental model to keep
> Every movement of money touches **two** accounts. A deposit isn't "+100 from nowhere"; it's
> "+100 to the user, −100 from the outside world." Put the outside world in the books as the
> **system account**, and the whole system always sums to zero — which is exactly what lets you
> *prove* you never lost or invented a cent.

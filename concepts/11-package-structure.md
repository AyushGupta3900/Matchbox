# 11 — Package Structure: Feature-based vs Role-based

## The two ways to organize packages

**Role-based (package-by-layer)** — top-level packages are the *technical roles*; every class
of a kind lives together:
```
com.matchbox
├── controller/   OrderController, AccountController, LedgerController, MarketController
├── service/      OrderService, AccountService, LedgerService, MarketService
├── repository/   OrderRepository, AccountRepository, LedgerRepository, ...
└── model/        Order, Account, LedgerEntry, Market
```

**Feature-based (package-by-feature)** — top-level packages are the *domains*; each owns its
own layers:
```
com.matchbox
├── account/      AccountController, AccountService, AccountRepository, Account
├── order/        OrderController, OrderService, OrderRepository, Order
├── ledger/       LedgerService, LedgerRepository, LedgerEntry, Transaction
└── market/       MarketController, MarketService, Market
```

## Why feature-based wins (for a system like this)

**1. Cohesion — related code lives together.** To change "how orders work," everything is in
`order/`. In the role-based layout, one feature change forces you to jump across
`controller/`, `service/`, `repository/`, `model/` — four packages for one idea. Things that
change together should live together; that's the definition of good cohesion.

**2. It matches our module → service plan.** Our architecture
([docs/05](../docs/05-architecture.md)) is *already* organized by modules — `gateway`,
`engine`, `settlement`, `marketdata` — that will become separate services in Phase 4.
Feature packages map 1:1 onto that. Lifting `engine/` out into its own process is then
moving a folder, not untangling four layer-packages. Role-based layout fights this split.

**3. Encapsulation becomes possible.** With everything for a feature in one package, you can
make internals **package-private** (no modifier) and expose only a narrow public interface.
`OrderRepository` can be package-private — nothing outside `order/` should touch it directly.
In the role-based layout, every class must be `public` (they're in different packages), so
there are no boundaries — anyone can call anything. Feature packages let you *enforce* the
boundary, not just hope for it.

**4. It scales.** With 5 classes, both look fine. With 200, the role-based `service/` package
is a junk drawer of 60 unrelated services, while feature packages stay small and navigable.
You feel the difference exactly when the project gets big enough to matter.

## When role-based is acceptable
- Tiny apps / throwaway prototypes / tutorials (the layout you see in beginner content —
  which is *why* it feels "normal," not because it's better).
- A single, genuinely thin layer with almost no domain logic.

For anything that will grow — and ours is explicitly built to grow across 6 phases — go
feature-based.

## The recommended hybrid: feature-first, layered inside

You don't lose layers — you just make **feature the outer dimension and layer the inner** one:
```
com.matchbox
├── settlement/                  ← feature/module
│   ├── api/        controllers (HTTP edge)
│   ├── service/    business logic + @Transactional boundaries
│   ├── domain/     entities + value objects
│   └── repo/       repositories
├── order/
│   ├── api/ service/ domain/ repo/
└── common/         shared: ids, fixed-point money types, time
```

This is what [syntax/01 §2](../syntax/01-project-scaffold.md) already lays out. You get domain
cohesion *and* a clear layer per file — the best of both. For a small feature, you can flatten
the inner layers (just put the few classes directly in `order/`) and split them out only when
it grows. Structure should match size.

## A rule of thumb to remember
> **Package by what it *is about* (the domain), not by what it *is* (the technical role).**
> Screaming "this is the trading exchange's order/ledger/account logic" from the folder names
> beats screaming "this app has controllers and services" — every app has those.

## Our decision for MatchBox
**Feature-based, feature-first with internal layers**, with packages named to mirror the
modules in [docs/05](../docs/05-architecture.md): `gateway`/`api` edges, `engine`,
`settlement`, `marketdata`, `security`, `events`, `analytics`, `observability`, `common`.
Keep `engine` especially clean and framework-free so Phase 4 can extract it untouched.

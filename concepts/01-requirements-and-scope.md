# 01 — Requirements & Scope

## What it is

A **requirements / scope doc** answers three questions before any code exists:

1. **What must the system do?** (functional requirements — features, behaviors)
2. **Who uses it, and how?** (actors / consumers)
3. **What are we *not* building?** (explicit out-of-scope — just as important)

It is the single source of truth that every later decision points back to. When someone
asks "why does the schema have a `reservations` table?" the answer should trace to a line
in this doc.

## Functional vs non-functional requirements

This trips people up, so nail it now:

- **Functional requirement** = *what* the system does. "A user can place a limit order."
  "The engine matches a buy against the lowest-priced sell." Behavior you could write a
  test for.
- **Non-functional requirement (NFR)** = *how well* it does it. "Match within 1ms at p99."
  "Handle 10,000 orders/sec." "No order is ever lost." Qualities, not features.

We capture functional requirements here, and the numbers (NFRs) get their own doc later —
because NFRs are what drive infrastructure choices (do we need Kafka? a cache? a replica?).

## Why "out of scope" matters as much as "in scope"

Scope creep kills projects. Writing down "**we are NOT building** margin trading, fiat
on-ramps, or a mobile app" does two things:
- Stops you from gold-plating Phase 0 with Phase 6 features.
- Makes the boundary a *decision*, not an accident. Later you can revisit it deliberately.

A good scope doc is as proud of its "Out of scope" list as its feature list.

## Actors / consumers — name them

Every system has *who* talks to it. List them, because each actor implies different needs:

- A **trader** (human via UI) needs latency they can feel and clear error messages.
- A **market-data subscriber** (bot/algo) needs a firehose and stable contracts.
- An **internal ops/admin** needs reconciliation and overrides.
- A **downstream service** (settlement, analytics) consumes events, not the API.

Different actors → different APIs, different SLAs, different auth. Naming them up front
stops you from designing one-size-fits-nobody interfaces.

## How to write a good one

- **Be concrete and testable.** "Fast" is useless; "p99 < 5ms under 5k orders/sec" is a
  target. (Numbers go in the NFR doc, but write them *somewhere*.)
- **Number your requirements** (FR-1, FR-2…). Then a schema field, an API endpoint, or a
  test can cite "implements FR-7." Traceability is a senior habit.
- **Separate "v1 / must-have" from "later / nice-to-have."** Ship the spine first.
- **Keep it living.** When scope changes, change the doc *first*, then the code.

## What this doc is NOT

- Not a design. It says *what*, never *how*. No tables, no endpoints, no classes here.
- Not permanent. It's the most-edited doc early on, then stabilizes.

## For our exchange

The brief ([STARTER.md](../STARTER.md)) already implies the requirements — our job is to
make them explicit, numbered, and scoped into "v1 vs later." See
[docs/01-requirements-and-scope.md](../docs/01-requirements-and-scope.md).

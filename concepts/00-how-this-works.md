# 00 — How this learning project works

## Why docs before code

Junior engineers open an editor and start typing. Senior engineers spend the first
chunk of any non-trivial project *not writing code* — they write **docs**. Here's why:

- **Code is the most expensive place to think.** Changing a sentence in a doc costs
  seconds. Changing a database schema after launch can cost weeks (data migrations,
  downtime, bugs). So we make the expensive-to-change decisions on paper first.
- **Docs force you to find the holes.** You can hand-wave in your head. You cannot
  hand-wave when you have to write "the API returns X when Y happens" — you'll hit a
  question you hadn't thought about. That's the doc doing its job.
- **Docs let people work in parallel.** Once the API contract is locked, a frontend dev
  can build against it while you build the backend. The doc is the agreement.

## The order we'll write docs (and why this order)

Everything flows downhill from requirements:

```
Requirements / Scope          ← what must the system do? (everything else depends on this)
        │
        ├──▶ Non-functional requirements   ← how fast / how much / how reliable?
        │
        ├──▶ Data model / ERD              ← the entities + relationships (hardest to change later)
        │
        ├──▶ API contract                  ← the interface consumers depend on
        │
        ├──▶ Auth & authz design           ← who can do what
        │
        ├──▶ Architecture diagram          ← how the pieces fit (monolith vs services)
        │
        └──▶ Cross-cutting plans           ← errors/logging, migrations, config/env
```

You can't design a schema without knowing requirements. You can't design an API without
knowing the schema. You can't size infrastructure without the non-functional numbers.
So we go top-down.

## How to use each folder

- Read the matching `concepts/NN-*.md` **before** we write a `docs/NN-*.md`.
- When you sit down to implement, check `syntax/` for the "how do I write this in Java?"
  reference. The reference shows you the *shape*; you write the real thing.
- Don't read ahead too far. Each phase deliberately creates a problem the next phase
  solves — feeling the problem is the point.

## Our loop, every time

1. I explain the concept (here + in chat).
2. We write the doc together.
3. I prompt you to **commit**.
4. We move to the next doc/phase.

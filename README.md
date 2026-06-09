# MatchBox — Trading Exchange (Backend Learning Project)

A spot order-matching exchange, built slowly and deliberately to learn **senior-level
backend engineering**: order-book data structures, lock-free concurrency, event sourcing,
latency engineering, and observability.

The full project brief lives in [STARTER.md](STARTER.md).

## How this repo is organized

| Folder | What lives here | Who writes it |
|--------|-----------------|---------------|
| [`docs/`](docs/) | Design docs for *our* exchange — requirements, data model, API contract, etc. These are the blueprints we build from. | We write together |
| [`concepts/`](concepts/) | One file per backend **concept**, explained in plain language with the *why*. Reference material — read before building the thing that uses it. | Claude writes |
| [`syntax/`](syntax/) | "How do I express X in Java/Spring/SQL?" — syntax references and small isolated snippets. **Not** project code. | Claude writes |
| (source code) | The actual exchange implementation. | **You** write it; Claude guides, never writes it for you. |

## The learning method

1. **Concept first.** Before we build a thing, there's a `concepts/` file explaining what it is and why it matters.
2. **Doc the design.** We capture the decision in a `docs/` file so future-you knows *why*, not just *what*.
3. **You write the code.** Claude gives you syntax help in `syntax/` and reviews/guides — but the implementation is yours. That's how it sticks.
4. **Commit per phase.** When a phase or doc-set is done, we commit. Small, labeled commits = a readable history of your own learning.

## Progress

- [x] **Design docs** — requirements, data model, API, auth, architecture, NFRs, error/logging, migrations, config ✅
- [ ] **Phase 0 — Skeleton + auth + wallet** (next — first code)
- [ ] Phase 1 — Matching engine
- [ ] Phase 2 — Event sourcing + CQRS
- [ ] Phase 3 — Market data + WebSocket
- [ ] Phase 4 — Concurrency hardening
- [ ] Phase 5 — Memory & latency
- [ ] Phase 6 — Observability & settlement

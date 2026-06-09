# 07 — Error Handling & Logging

## Why standardize this up front

Error handling and logging feel like things you "add later." They're not — they're
*conventions*, and conventions only work if everyone (every module, every endpoint) follows
the same one from line one. Retrofitting a consistent error shape or correlation ID across a
codebase that grew without them is miserable. So we decide the rules now.

## Error handling: three categories of failure

Not all errors are equal. Sort every failure into one of three buckets, because each is
handled differently:

1. **Client errors (4xx)** — the caller did something wrong: bad input, insufficient funds,
   not authorized. *Expected.* You return a clear, structured error and **do not log it as an
   error** (it's not a bug — it's the system working). At most `INFO`/`DEBUG`.
2. **Domain/business errors** — a rule was violated: order can't be filled per its TIF, market
   halted. Also expected, also a clean 4xx (usually `422`). These are *first-class outcomes*,
   not exceptions to panic over.
3. **System errors (5xx)** — *we* broke: a null pointer, a DB down, a bug. *Unexpected.* These
   get logged at `ERROR` with a full stack trace and should page someone (eventually). The
   client gets a generic message + a `request_id` — never your stack trace.

The cardinal rule: **never leak internal details to the client** (stack traces, SQL, file
paths — they're an information-disclosure security hole), and **never silently swallow a system
error** (log it with context).

## Fail fast, validate at the edge

Validate input as early as possible — at the gateway, before it touches business logic or the
engine. A bad order should be rejected at the door, not three layers deep after funds are half-
reserved. "Fail fast" means: detect the problem at the earliest point you can, with the most
context, and stop. The deeper an error travels before you catch it, the harder it is to
diagnose and the more half-done state you have to unwind.

## Exceptions vs. return values

Two styles for signaling failure:
- **Exceptions** — throw; something up the stack catches. Good for *truly exceptional* /
  unexpected things (system errors). Cheap to write, but they're invisible in a function's
  signature and have a (small) performance cost.
- **Result/Either types** — return a value that's *either* success or a typed error. Good for
  *expected* outcomes (an order being rejected isn't exceptional — it's a normal result). The
  failure is visible in the type, so callers can't forget to handle it.

Senior heuristic: **expected outcomes are return values; unexpected failures are exceptions.**
"Insufficient funds" is a return value. "Database connection died" is an exception.

## One error shape, mapped centrally

We already defined the wire format in the API contract (the `error` envelope with `code`,
`message`, `request_id`). The implementation rule: have **one** central place that turns any
exception into that envelope + the right status code (in Spring, a global exception handler).
No endpoint formats its own errors. One mapping = total consistency.

## The three pillars of observability (logs ≠ metrics ≠ traces)

People say "logging" but mean three different things:

- **Logs** — discrete, timestamped *events with detail*. "Order 4827 rejected: insufficient
  funds." Great for *what happened to this one thing*. Expensive at volume.
- **Metrics** — *aggregated numbers* over time. "orders_rejected_total = 1,203", "p99 latency
  = 4 ms." Great for *trends and alerting*. Cheap, but no per-event detail.
- **Traces** — the *path of one request* across services/async hops, with timing per step.
  Great for *where did the time go* in a distributed flow.

You need all three. This doc is mostly about **logs**; metrics + traces get built in Phase 6.
But the thing that ties all three together is the next idea.

## Correlation IDs — the thread through everything

Generate one **request ID** at the edge for every incoming request. Then:
- Put it in every **log line** for that request.
- Return it to the client (in the `error` envelope and an `X-Request-Id` header).
- **Propagate it across async boundaries** — when the command goes onto Kafka, the event
  carries the same ID, so the settlement log line and the original gateway log line share it.

Now "show me everything that happened to order 4827" is one query, even though the order
touched the gateway, the engine, Kafka, and settlement — three processes, two async hops. This
is the single highest-leverage logging practice. (A *trace ID* is the distributed-tracing
version of the same idea; they often coincide.)

## Structured logging — log JSON, not prose

`log.info("order " + id + " rejected for " + reason)` produces a string a machine can't
query. Instead log **structured** key-value pairs:

```json
{ "ts":"2026-06-09T10:15:00Z", "level":"WARN", "msg":"order rejected",
  "request_id":"req_01HX", "account_id":"1", "order_id":"4827",
  "reason":"INSUFFICIENT_FUNDS", "market":"BTC-USD" }
```

Now you can query "all rejected orders for account 1 today" instead of grepping prose. The
fields are the data; the message is just a label. (In Java: SLF4J + a JSON encoder, with
per-request fields in the **MDC** — Mapped Diagnostic Context — so they attach automatically.)

## Log levels — use them on purpose

- **ERROR** — a system error; something is broken and needs attention. (Not "user typo.")
- **WARN** — something unexpected but handled; worth noticing. (Retry succeeded; degraded mode.)
- **INFO** — significant business events. (Order accepted, deposit made.) The default in prod.
- **DEBUG** — detailed flow for diagnosing; off in prod, on when investigating.
- **TRACE** — firehose; rarely on.

The discipline: a 4xx client error is **not** an `ERROR` log. If your error logs are full of
"user sent bad input," real bugs drown in noise and alerts become worthless.

## Never log these

- Passwords, API secrets, JWTs, HMAC signatures.
- Full card/bank numbers, personal data beyond what's needed.
- Whole request bodies if they contain the above.

A logged secret is a leaked secret (logs get shipped, indexed, and read widely). Redact at the
logging layer so it can't happen by accident.

## The hot-path exception: the engine must not log

The matching engine is **single-writer and deterministic** (NFR-1, NFR-11). Logging inside the
match loop would (a) allocate and do I/O on the hot path, blowing the latency budget, and (b)
risk non-determinism. So the engine **doesn't log mid-match** — it *emits events*, and all
observability about matching is derived from those events and from metrics recorded *around*
the loop, not inside it. "Don't put I/O on the hot path" includes logging.

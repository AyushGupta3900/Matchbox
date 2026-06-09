# Error Handling & Logging Conventions — MatchBox Exchange

> Status: **Draft v1** · Last updated: 2026-06-09
> Read [concepts/07-error-handling-and-logging.md](../concepts/07-error-handling-and-logging.md) first.
> The wire error shape is defined in [03-api-contract.md](03-api-contract.md); this doc covers
> how we *produce* it and how we *log*.

## Error taxonomy (every failure is exactly one of these)

| Category | HTTP | Logged at | Stack trace? | Example |
|----------|------|-----------|--------------|---------|
| **Validation** (bad input) | 400 | DEBUG | no | missing field, bad enum, price not a tick multiple |
| **Auth** | 401/403 | INFO | no | bad JWT, bad signature, nonce replay, not owner |
| **Domain/business** | 409/422 | INFO | no | insufficient funds, FOK unfillable, market halted |
| **Not found** | 404 | DEBUG | no | order isn't yours / doesn't exist |
| **Rate limit** | 429 | INFO (sampled) | no | bucket exceeded |
| **System** (our bug / dep down) | 500/503 | **ERROR** | **yes** | NPE, DB down, serialization failure |

Rule: only **System** errors are `ERROR` logs with stack traces. The rest are expected
outcomes — clean responses, low-noise logs. If `ERROR` logs fill with client mistakes, alerts
become worthless.

## Exception → response mapping (one central handler)

- A **single** global handler (Spring `@RestControllerAdvice`) converts every exception to the
  `error` envelope + status. **No controller formats its own errors.**
- Internal exception hierarchy:
  - `ValidationException` → 400 `VALIDATION_ERROR`
  - `AuthException` (sub: `BadSignature`, `NonceReplay`) → 401
  - `ForbiddenException` → 403
  - `DomainException` (sub: `InsufficientFunds`, `FokUnfillable`, `MarketHalted`, …) → 422/409
  - `NotFoundException` → 404
  - anything else → 500 `INTERNAL` (generic message; details only in logs)
- **Expected outcomes inside the engine/settlement are return values, not exceptions**
  (e.g. an order result of `REJECTED(reason)`); exceptions are reserved for the unexpected.

## The error envelope (recap — must match the API contract exactly)
```json
{ "error": { "code": "INSUFFICIENT_FUNDS", "message": "…",
             "details": { "required":"6000000", "available":"5000000" },
             "request_id": "req_01HX…" } }
```
- `code` is **stable + machine-readable**; `message` is for humans; `details` is optional.
- `message` for a 500 is **always generic** ("Internal error") — never the exception text.
- `request_id` is present on **every** error and matches the `X-Request-Id` response header.

## Structured logging

### Format
JSON, one object per line (SLF4J + Logback JSON encoder). No prose concatenation.

### Standard fields (on every log line, via MDC where possible)
| Field | Meaning |
|-------|---------|
| `ts` | ISO-8601 UTC timestamp |
| `level` | ERROR/WARN/INFO/DEBUG/TRACE |
| `logger` | class/module |
| `msg` | short static label (not interpolated data) |
| `request_id` | correlation id (see below) — **always present on a request path** |
| `account_id` | when known |
| `service` | module name (`gateway`, `settlement`, …) |

### Domain fields (added where relevant)
`order_id`, `client_order_id`, `market`, `side`, `type`, `reason`, `trade_id`, `seq`,
`amount`, `asset`, `latency_ms`.

### Example
```json
{ "ts":"2026-06-09T10:15:00.123Z","level":"INFO","logger":"gateway.OrderController",
  "service":"gateway","msg":"order accepted","request_id":"req_01HX",
  "account_id":"1","order_id":"4827","client_order_id":"c-7f3a","market":"BTC-USD",
  "side":"BUY","type":"LIMIT","filled_qty":"20000000","latency_ms":3 }
```

## Correlation IDs — generation & propagation

1. **Generate** at the gateway for every request: if the client sent `X-Request-Id`, honor it;
   else create one (`req_` + ULID). Put it in the **MDC** immediately.
2. **Return** it: `X-Request-Id` response header **and** `error.request_id`.
3. **Propagate across async hops:** the command carries `request_id`; when the engine emits an
   event, the event carries it; Kafka message headers carry it; every consumer (settlement,
   market data) restores it into its MDC before processing. → one ID stitches the gateway log,
   the event, and the settlement log together.
4. **Trace ID (Phase 6):** when distributed tracing lands, the W3C `traceparent` becomes the
   spine; `request_id` aligns with it. Designed for now, wired later.

> Acceptance test for this convention: "show me every log line for order 4827, across all
> modules" returns the full lifecycle via a single `request_id`/`order_id` filter.

## Log-level policy

| Level | Use for | In prod? |
|-------|---------|----------|
| ERROR | system errors only (5xx, unhandled) — with stack trace | yes, **alerts** |
| WARN | handled anomaly worth noticing (retry, degraded, lag spike) | yes |
| INFO | business events: order accepted/canceled, deposit, login | yes (default) |
| DEBUG | detailed flow for diagnosis | no (toggle on to investigate) |
| TRACE | firehose | no |

- A 4xx is **never** an ERROR log.
- High-frequency lines (rate-limit hits) are **sampled** to avoid flooding.

## Sensitive-data rules (redact at the logging layer)
**Never log:** passwords, API secrets, JWTs, HMAC signatures, full request bodies that contain
them. Redact in a logging filter so a stray `log.debug(request)` can't leak. Amounts and
order data are fine to log.

## Hot-path rule (the engine)
The matching loop **does not log** (no I/O, no allocation, preserves determinism — NFR-1/11).
- The engine **emits events**; observability about matching is derived from events + metrics
  recorded *around* the loop.
- Per-match timing is captured with pre-allocated counters/timers (Phase 6 Micrometer), not log
  lines.

## Retention
Per [06-non-functional-requirements.md](06-non-functional-requirements.md): application logs
**~weeks** (NFR-28). Logs are shipped to a central store (Phase 6); locally they go to stdout
(container-friendly) — see config doc.

## Open questions
- [ ] Log sink for v1: stdout only (Docker captures it), or also a file? (Proposal: stdout
      only; aggregation is a Phase-6 concern.)
- [ ] ULID vs UUID for `request_id`? (Proposal: ULID — time-sortable, still unique.)
- [ ] Do we emit an audit log (separate, immutable) for money movements distinct from app logs?
      (Proposal: the **ledger itself** is the audit log; no separate stream in v1.)

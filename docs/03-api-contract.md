# API Contract — MatchBox Exchange (v1)

> Status: **Draft v1 — lock before implementing** · Last updated: 2026-06-09
> Read [concepts/03-api-contract.md](../concepts/03-api-contract.md) first.
> All endpoints are prefixed `/v1`. Amounts (prices, quantities, balances) are **strings of
> integers** in the smallest unit (cents, satoshis). Times are ISO-8601 UTC.

## Conventions

- **Base path:** `/v1`
- **Content type:** `application/json`
- **Auth levels** (stated per endpoint):
  - `Public` — no auth.
  - `JWT` — `Authorization: Bearer <token>`.
  - `Signed` — JWT **plus** an HMAC signature (see [Auth doc] — for order placement/cancel).
    Headers: `X-API-Key: <key_id>`, `X-Signature: <hmac>`, `X-Timestamp: <unix_ms>`,
    `X-Nonce: <unique>`.
- **Correlation:** every response carries `X-Request-Id`; it also appears in `error.request_id`.
- **Pagination:** cursor-based — `?limit=50&after=<id>`. Responses include
  `"page": { "next_cursor": "<id|null>", "limit": 50 }`.
- **Idempotency:** any order placement carries `client_order_id` (unique per account).

## Error envelope (every 4xx/5xx)

```json
{
  "error": {
    "code": "INSUFFICIENT_FUNDS",
    "message": "Not enough USD available to place this order",
    "details": { "required": "6000000", "available": "5000000" },
    "request_id": "req_01HX..."
  }
}
```

### Canonical error codes

| HTTP | `code` | When |
|------|--------|------|
| 400 | `VALIDATION_ERROR` | malformed body / missing field / bad enum |
| 401 | `UNAUTHENTICATED` | missing/invalid JWT |
| 401 | `BAD_SIGNATURE` | HMAC signature invalid/expired |
| 403 | `FORBIDDEN` | authenticated but not allowed |
| 404 | `NOT_FOUND` | resource doesn't exist (or isn't yours) |
| 409 | `DUPLICATE_CLIENT_ORDER_ID` | idempotency key reused with different params |
| 409 | `NONCE_REPLAY` | nonce already seen |
| 422 | `INSUFFICIENT_FUNDS` | not enough available balance to reserve |
| 422 | `MARKET_HALTED` | trading disabled for the symbol |
| 422 | `INVALID_PRICE` / `INVALID_QTY` | not a multiple of tick/lot, or ≤ 0 |
| 422 | `FOK_UNFILLABLE` / `IOC_NO_LIQUIDITY` | order couldn't fill per its TIF |
| 429 | `RATE_LIMITED` | too many requests (includes `Retry-After`) |
| 500 | `INTERNAL` | unexpected server error |
| 503 | `UNAVAILABLE` | engine/dependency down |

---

## Auth & keys

### `POST /v1/auth/register` — `Public`
Create a user + account.
```json
// req
{ "email": "ada@example.com", "password": "••••••••" }
// 201
{ "user_id": "1", "account_id": "1" }
```
Errors: `400 VALIDATION_ERROR`, `409` (email exists).

### `POST /v1/auth/login` — `Public`
```json
// req
{ "email": "ada@example.com", "password": "••••••••" }
// 200
{ "access_token": "eyJ...", "token_type": "Bearer", "expires_in": 900 }
```
Errors: `401 UNAUTHENTICATED`.

### `POST /v1/auth/api-keys` — `JWT`
Mint an HMAC key for signed order requests. **Secret returned once, never again.**
```json
// req
{ "label": "trading-bot-1" }
// 201
{ "key_id": "ak_01HX...", "secret": "sk_live_9f3...", "label": "trading-bot-1" }
```

### `GET /v1/auth/api-keys` — `JWT`
List your keys (no secrets). `200 → { "keys": [ { "key_id", "label", "status", "created_at" } ] }`

---

## Wallet

### `GET /v1/balances` — `JWT`
```json
// 200
{ "balances": [
  { "asset": "USD", "available": "5000000", "reserved": "1000000" },
  { "asset": "BTC", "available": "100000000", "reserved": "0" }
] }
```

### `POST /v1/wallet/deposit` — `JWT`
v1 simulated deposit (no real banking). Writes a balanced ledger transaction.
```json
// req
{ "asset": "USD", "amount": "10000000", "idempotency_key": "dep_abc123" }
// 201
{ "transaction_id": "42", "asset": "USD", "amount": "10000000", "available": "15000000" }
```
Errors: `400`, `409` (idempotency key reused).

### `POST /v1/wallet/withdraw` — `JWT`
Instant in v1. Fails if `available` is insufficient.
```json
// req
{ "asset": "USD", "amount": "2000000", "idempotency_key": "wd_xyz789" }
// 201
{ "transaction_id": "43", "asset": "USD", "amount": "2000000", "available": "13000000" }
```
Errors: `422 INSUFFICIENT_FUNDS`.

---

## Orders

### `POST /v1/orders` — `Signed`
Place an order. Funds are reserved **before** acceptance. Returns `202 Accepted` because the
order is accepted into the book — fills may happen synchronously or stream later.
```json
// req
{
  "client_order_id": "c-7f3a",      // idempotency key, unique per account
  "market": "BTC-USD",
  "side": "BUY",                     // BUY | SELL
  "type": "LIMIT",                   // LIMIT | MARKET | IOC | FOK
  "price": "6001000",                // cents; omit/null for MARKET
  "quantity": "50000000"             // satoshis
}
// 202
{
  "order_id": "4827",
  "client_order_id": "c-7f3a",
  "status": "PARTIALLY_FILLED",      // NEW | PARTIALLY_FILLED | FILLED | REJECTED ...
  "market": "BTC-USD",
  "side": "BUY", "type": "LIMIT",
  "price": "6001000",
  "original_qty": "50000000",
  "filled_qty": "20000000",
  "remaining_qty": "30000000",
  "fills": [
    { "trade_id": "9001", "price": "6000500", "qty": "20000000", "role": "TAKER" }
  ],
  "created_at": "2026-06-09T10:15:00Z"
}
```
Errors: `401 BAD_SIGNATURE`, `409 DUPLICATE_CLIENT_ORDER_ID`, `409 NONCE_REPLAY`,
`422 INSUFFICIENT_FUNDS | INVALID_PRICE | INVALID_QTY | FOK_UNFILLABLE | IOC_NO_LIQUIDITY |
MARKET_HALTED`.

> A `REJECTED` order (e.g. insufficient funds) returns a **422 with the error envelope**, not
> a 202. Acceptance into the book is the only 202 path.

### `DELETE /v1/orders/{order_id}` — `Signed`
Cancel a resting order; releases reserved funds for the remainder.
```json
// 200
{ "order_id": "4827", "status": "CANCELED", "remaining_qty": "30000000" }
```
Errors: `404 NOT_FOUND` (not yours / already terminal), `422` (already filled).

### `GET /v1/orders/{order_id}` — `JWT`
`200` → the full order object (same shape as the place response, current state).

### `GET /v1/orders` — `JWT`
List your orders. Filters: `?status=OPEN|FILLED|CANCELED&market=BTC-USD&limit=&after=`.
`OPEN` = `NEW` + `PARTIALLY_FILLED`.
```json
// 200
{ "orders": [ { /* order */ } ], "page": { "next_cursor": "4810", "limit": 50 } }
```

---

## Trades (your fills)

### `GET /v1/trades` — `JWT`
Your executed trades. Filters: `?market=BTC-USD&limit=&after=`.
```json
// 200
{ "trades": [
  { "trade_id": "9001", "order_id": "4827", "market": "BTC-USD",
    "side": "BUY", "role": "TAKER", "price": "6000500", "qty": "20000000",
    "executed_at": "2026-06-09T10:15:00Z" }
], "page": { "next_cursor": null, "limit": 50 } }
```

---

## Market data *(read-only; basic in v1, streaming is Phase 3)*

### `GET /v1/markets` — `Public`
```json
// 200
{ "markets": [
  { "symbol": "BTC-USD", "base": "BTC", "quote": "USD",
    "tick_size": "1", "lot_size": "1", "status": "TRADING" }
] }
```

### `GET /v1/markets/{symbol}/depth` — `Public`
Aggregated book snapshot. `?limit=` levels per side.
```json
// 200
{ "market": "BTC-USD", "sequence": "100482",
  "bids": [ ["6000000","30000000"], ["5999000","10000000"] ],   // [price, total_qty]
  "asks": [ ["6001000","5000000"],  ["6002000","20000000"] ] }
```

### `GET /v1/markets/{symbol}/trades` — `Public`
Recent public trades (anonymous): `[ { "trade_id", "price", "qty", "side", "executed_at" } ]`.

### `[later · Phase 3]` `WS /v1/stream`
WebSocket: subscribe to `depth` deltas and `trades` per symbol; server sends a snapshot then
deltas keyed by `sequence`. Full design in the market-data phase.

---

## Rate limiting

- Per-account and per-IP token buckets. Exceeding → `429 RATE_LIMITED` with `Retry-After`.
- Order placement/cancel have a tighter bucket than reads. Concrete numbers live in the NFR doc.

## What's locked vs open

**Locked for v1:** resource URLs, methods, the error envelope + codes, amount-as-string rule,
cursor pagination shape, the order/trade/balance object shapes.

**Open questions**
- [ ] Should `POST /orders` return `202` with fills inline, or `201` + fills only via stream?
      (Proposal: `202` + inline `fills[]` for v1 — simplest for a REST-only client.)
- [ ] Batch order placement / batch cancel in v1? (Proposal: no — single only.)
- [ ] Expose `GET /v1/orders` to include terminal orders by default or only with a filter?
      (Proposal: default to OPEN; require `?status=` for history.)

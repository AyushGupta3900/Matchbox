# 03 — API Contract

## What it is

An **API contract** is the precise promise your backend makes to whoever calls it: *here are
the exact URLs, the methods, what you send, what you get back, and what every error looks
like.* It's a contract because once published, people *depend* on it — breaking it breaks
them.

The payoff: once the contract is locked, a frontend dev (or a trading bot author) can build
against it **in parallel** with you, using fake/mock responses, before your backend even
works. The contract is the agreement that lets a team move at once.

## REST in one paragraph

REST models your system as **resources** (nouns) that you act on with **HTTP methods**
(verbs). A resource has a URL (`/orders/4827`). You don't invent verbs in the URL
(`/createOrder` is wrong); you use the method:

| Method | Means | Example |
|--------|-------|---------|
| `GET` | read, no side effects | `GET /orders/4827` |
| `POST` | create / do an action | `POST /orders` |
| `PUT` / `PATCH` | replace / partially update | `PATCH /orders/4827` |
| `DELETE` | remove | `DELETE /orders/4827` |

Rule of thumb: **URLs are nouns, methods are verbs.** Collections are plural (`/orders`).

## Status codes — say the right thing

The HTTP status code is the *first* thing a client checks. Use them correctly:

- **2xx success** — `200 OK` (read/ok), `201 Created` (made a thing), `202 Accepted`
  (queued, not done yet — relevant for us: an order is *accepted* before it's matched).
- **4xx — the client's fault** — `400` (malformed), `401` (not authenticated), `403`
  (authenticated but not allowed), `404` (not found), `409` (conflict, e.g. duplicate),
  `422` (well-formed but semantically invalid — e.g. insufficient funds), `429` (rate
  limited).
- **5xx — the server's fault** — `500` (we broke), `503` (overloaded/down).

The split that matters: **4xx = you messed up, don't retry the same thing; 5xx = we messed
up, retrying might work.** Clients build retry logic on this distinction — get it wrong and
they hammer you on un-retryable errors.

## One error shape, everywhere

Nothing makes an API miserable like every endpoint returning errors differently. Pick **one**
error envelope and use it for *every* failure. A good shape:

```json
{
  "error": {
    "code": "INSUFFICIENT_FUNDS",
    "message": "Not enough USD to place this order",
    "details": { "required": "6000000", "available": "5000000" },
    "request_id": "req_01H..."
  }
}
```

- `code` — a **stable, machine-readable** string clients can branch on. (Never make clients
  parse `message`.)
- `message` — human-readable, for logs/debugging.
- `request_id` — a correlation ID that also appears in your server logs, so a user can paste
  it in a bug report and you find the exact request. (This ties into the logging doc later.)

(The formal standard for this is RFC 9457 "Problem Details." A custom envelope like above is
fine too — the point is *pick one and never deviate*.)

## Idempotency — the "did my order go through?" problem

The client sends "place order," the network hiccups, they don't get a reply. Did it work? If
they retry, do they now have **two** orders? On an exchange that's real money lost.

Fix: the client sends a unique **idempotency key** (we call it `client_order_id`). The server
remembers it; a retry with the same key returns the *original* result instead of acting
twice. Any "create" that costs money needs this.

## Pagination — never return "all" of an unbounded list

`GET /trades` could be millions of rows. You must page. Two styles:

- **Offset** (`?page=3&limit=50`) — simple, but slow on big tables and *shifts* if rows are
  inserted while paging.
- **Cursor** (`?after=<id>&limit=50`) — you pass the last item you saw; the server returns
  the next slice. Stable under inserts, fast. **Preferred** for append-heavy feeds like
  trades.

## Versioning — so you can change without breaking

Put a version in the path: `/v1/orders`. When you must make a breaking change, you ship
`/v2/...` and keep `/v1` alive while clients migrate. Cheap insurance; do it from day one.

## Money over the wire: send amounts as strings

JSON numbers are IEEE floats in many clients (JavaScript can't safely hold integers above
2^53). To protect our "money is exact integers" rule end-to-end, send amounts as **strings**:
`"price": "6000000"` not `"price": 6000000`. The client parses to its own integer type. Ugly,
correct.

## Auth requirements belong in the contract

Every endpoint states what it needs: nothing (public), a **JWT** (logged-in user), or a
**signed request** (HMAC, for order placement). The contract says which, and what header
carries it. (Full design is the auth doc; the contract just *references* it per endpoint.)

## OpenAPI — the standard artifact

The industry-standard way to *write down* all of the above is an **OpenAPI** (formerly
Swagger) spec — a YAML/JSON file describing every endpoint. It's worth it because it's not
just docs: it can **generate** client SDKs, server stubs, and a mock server, and it renders
as interactive docs (Swagger UI). Our markdown contract is the human version; we can express
it as OpenAPI when we implement.

## What "locked" means

Once we publish v1 of this contract, we treat changes as a big deal: additive changes (new
optional field, new endpoint) are fine; **removing or renaming a field, changing a type, or
changing status-code meaning is a breaking change** → needs `/v2`. Discipline here is what
makes you trustworthy to everyone building on you.

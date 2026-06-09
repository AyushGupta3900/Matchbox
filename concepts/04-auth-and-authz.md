# 04 — Authentication & Authorization

## Two different words people confuse

- **Authentication (authn)** — *who are you?* Proving identity. (Login with a password.)
- **Authorization (authz)** — *what are you allowed to do?* Checking permission. (Can this
  user halt the market? Cancel *that* order?)

You authenticate **once** per request to know the caller; you authorize **every** action to
decide if they may. Mixing them up is a classic security hole ("we checked they were logged
in, but not that the order was *theirs*").

## Passwords: never store them

You store a **hash**, never the password. A hash is one-way: you can verify a guess but can't
reverse it. Use a *slow*, salted algorithm built for passwords — **bcrypt** or **argon2** —
not SHA-256 (too fast, brute-forceable). "Salted" = each password gets random bytes mixed in,
so two users with the same password get different hashes and precomputed-table attacks fail.

## JWT — a token that proves you logged in

After login, the server hands the client a **JWT** (JSON Web Token): a string with three
dot-separated parts — `header.payload.signature`.

- **payload** ("claims") — JSON like `{ "sub": "account-1", "roles": ["TRADER"], "exp": ... }`.
  *Readable by anyone* — it's just base64, **not encrypted**. Never put secrets in it.
- **signature** — the server signs `header.payload` with a secret key. Anyone can read the
  token, but only the server can *produce a valid signature*, so nobody can forge or tamper
  with one.

The magic: the server can verify a JWT **without a database lookup** — it just re-checks the
signature. That makes auth fast and stateless, which matters on a hot path.

- **HS256** — signed with one shared secret (simple; fine for a single app).
- **RS256** — signed with a private key, verified with a public key (use when many separate
  services need to verify but shouldn't be able to *mint* tokens).

## Token lifecycle: access vs refresh

A stateless JWT has a catch: once issued, you can't easily *un*-issue it. So:

- **Access token** — short-lived (e.g. 15 min). Sent on every request. If stolen, it expires
  fast.
- **Refresh token** — long-lived (e.g. 7 days), stored server-side, used *only* to get a new
  access token. On use, you **rotate** it (issue a new one, invalidate the old) so a stolen
  refresh token is detectable. Logout = delete the refresh token.

This is the standard trade-off: stateless speed for access, a small bit of state for the
ability to revoke.

## HMAC request signing — stronger than a bearer token

For high-value actions (placing orders = moving money), a JWT alone is weak: anyone who
*captures* the token can replay it. Exchanges add **request signing**.

The idea (this is how AWS, Binance, etc. work):

1. You get an **API key**: a public `key_id` and a secret `secret`.
2. For each request, the client builds a **canonical string** from the request's parts —
   method + path + timestamp + nonce + body — and computes
   `signature = HMAC-SHA256(secret, canonical_string)`.
3. It sends `key_id`, `signature`, `timestamp`, `nonce` as headers.
4. The server looks up the secret for that `key_id`, **recomputes** the signature the same
   way, and compares. Match → the request is authentic *and* untampered (change one byte of
   the body and the signature won't match).

Why it's stronger: the secret itself **never travels** on the wire — only a signature derived
from it. There's nothing for an eavesdropper to steal and reuse.

> ⚠️ **Storage consequence:** to *recompute* the signature, the server needs the **actual
> secret**, so an API secret must be stored **encrypted (reversible)** at rest — *not* one-way
> hashed like a password. (This corrects an earlier guess in the data model doc — catching
> that mismatch is exactly why we write this doc.)

## Replay protection: timestamp + nonce

Signing proves authenticity, but a captured *valid* signed request could be **replayed**
(sent again). Two guards, used together:

- **Timestamp window** — the request includes `X-Timestamp`; the server rejects anything more
  than a few seconds old. Shrinks the replay window to near-zero.
- **Nonce** — a unique value per request (`X-Nonce`). The server remembers nonces it has seen
  (in **Redis**, with a TTL equal to the timestamp window) and rejects repeats. Within the
  window, each request can only be used **once**.

## Authorization model: RBAC

**Role-Based Access Control** = users have **roles**, roles grant **permissions**, endpoints
require permissions. Simple and standard:

- `TRADER` (default) — place/cancel own orders, deposit/withdraw own funds, read own data.
- `ADMIN` / `OPS` — halt a market, run reconciliation, read any account.

Plus **ownership checks**, which RBAC alone doesn't cover: a TRADER can cancel orders — but
only *their own*. So authorization is two questions: *does your role allow this action?* and
*do you own this specific resource?* Both must pass.

## A quick threat model (why all this exists)

- Stolen password → hashing + (later) 2FA limit the blast radius.
- Stolen JWT → short expiry limits the window.
- Tampered/forged request → signature catches it.
- Replayed request → timestamp + nonce catch it.
- Acting on someone else's resource → ownership checks catch it.

Each mechanism stops a specific attack. Knowing *which attack each one stops* is the
difference between cargo-culting security and understanding it.

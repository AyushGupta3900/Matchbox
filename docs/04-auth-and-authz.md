# Auth & Authz Design — MatchBox Exchange

> Status: **Draft v1** · Last updated: 2026-06-09
> Read [concepts/04-auth-and-authz.md](../concepts/04-auth-and-authz.md) first.
> Makes the `Signed` / `JWT` endpoints in [03-api-contract.md](03-api-contract.md) concrete.

## Summary of choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Password hashing | **argon2id** (bcrypt acceptable fallback) | slow, salted, built for passwords |
| Session auth | **JWT access token (HS256)** + server-side **refresh token** | stateless verify, revocable sessions |
| Access token TTL | **15 min** | small theft window |
| Refresh token TTL | **7 days**, rotated on use | revocable, detects token theft |
| High-value actions | **HMAC-SHA256 request signing** | secret never on the wire; tamper-evident |
| Replay protection | **timestamp window (±5 s) + nonce in Redis** | each signed request usable once |
| Authorization | **RBAC** (`TRADER`, `ADMIN`) **+ ownership checks** | role gate + resource gate |

## Authentication flows

### Registration / login
1. `POST /v1/auth/register` → hash password with **argon2id**, create `users` + `accounts`.
2. `POST /v1/auth/login` → verify hash → issue **access token** (JWT) + **refresh token**.

### Access token (JWT) claims
```json
{
  "sub": "1",                // account_id
  "uid": "1",                // user_id
  "roles": ["TRADER"],
  "iat": 1749465600,
  "exp": 1749466500,         // iat + 15 min
  "jti": "tok_01HX..."       // unique id, for revocation lists if needed
}
```
- Signed **HS256** with `JWT_SIGNING_SECRET` (from env/secret manager, see config doc).
- Verified on every `JWT`/`Signed` endpoint by re-checking the signature + `exp`. No DB hit.
- Claims are **readable, not secret** — never put balances or keys in here.

### Refresh & logout
- `POST /v1/auth/refresh` (refresh token in an **HttpOnly cookie**) → new access token + a
  **rotated** refresh token; the old refresh token is invalidated.
- Refresh tokens are stored server-side (table or Redis) so they can be revoked.
- `POST /v1/auth/logout` → delete the refresh token. (Access tokens simply expire ≤15 min.)
- Reuse of an already-rotated refresh token ⇒ treat as theft ⇒ revoke the whole chain.

## HMAC request signing (for `Signed` endpoints: place/cancel order)

### Canonical string (the exact bytes both sides sign)
```
canonical = METHOD + "\n" +
            PATH (incl. query) + "\n" +
            X-Timestamp + "\n" +
            X-Nonce + "\n" +
            SHA256_HEX(body)        // empty-string hash if no body
signature = HEX( HMAC_SHA256(api_secret, canonical) )
```

### Request headers
```
Authorization: Bearer <jwt>        // still required: identifies the account/role
X-API-Key:     <key_id>            // which key signed this
X-Timestamp:   1749465600123       // unix millis
X-Nonce:       3f9c1a...           // unique per request
X-Signature:   <hex hmac>
```

### Server verification (order, fail-fast)
1. Validate JWT → get `account_id`, roles.
2. Look up `key_id` → load + decrypt the `api_secret`; confirm it belongs to `account_id` and
   is `ACTIVE`. Unknown/disabled → `401 BAD_SIGNATURE`.
3. Check `|now − X-Timestamp| ≤ 5s`. Outside window → `401 BAD_SIGNATURE`.
4. `SETNX nonce:<key_id>:<X-Nonce>` in Redis with TTL = 10s. Already present → `409 NONCE_REPLAY`.
5. Recompute `signature`; **constant-time compare**. Mismatch → `401 BAD_SIGNATURE`.
6. Only now process the order.

### API key storage — **correction to the data model**
HMAC verification requires the **actual secret** to recompute the signature, so it must be
stored **encrypted (reversible)**, not one-way hashed. The data model is updated:
`api_keys.secret_hash` → **`api_keys.secret_encrypted`** (+ `key_version` for the encryption
key used). The plaintext secret is shown to the user **once** at creation and never again.

### API key lifecycle
- Mint: `POST /v1/auth/api-keys` → returns `key_id` + secret (once). Store encrypted.
- List: `GET /v1/auth/api-keys` → metadata only, never the secret.
- Revoke: `DELETE /v1/auth/api-keys/{key_id}` → status `REVOKED`; signing fails immediately.

## Authorization (RBAC + ownership)

### Roles & permissions (v1)
| Permission | TRADER | ADMIN |
|------------|:------:|:-----:|
| place/cancel **own** order | ✅ | ✅ |
| deposit/withdraw **own** funds | ✅ | ✅ |
| read **own** orders/trades/balances | ✅ | ✅ |
| read **any** account | ❌ | ✅ |
| halt/resume a market | ❌ | ✅ |
| run reconciliation / admin ops | ❌ | ✅ |

### Two gates on every protected action
1. **Role gate** — does a role in the JWT grant this action? (RBAC.)
2. **Ownership gate** — for resource-scoped actions, does `resource.account_id ==
   jwt.account_id`? A TRADER cancelling order `4827` must *own* `4827`, else `404 NOT_FOUND`
   (we return 404 not 403, so we don't leak that the order exists).

## Where state lives

| Thing | Store | Why |
|-------|-------|-----|
| password hash | Postgres `users` | durable, rarely read |
| access token (JWT) | nowhere (stateless) | verified by signature |
| refresh token | Postgres/Redis | must be revocable |
| API secret (encrypted) | Postgres `api_keys` | needed to recompute HMAC |
| nonces | **Redis** (TTL ≈ window) | high-write, auto-expiring, never needs durability |
| rate-limit buckets | **Redis** | hot, ephemeral |

## Out of scope for v1 (named)
- 2FA / TOTP, email verification, password reset flows.
- OAuth / social login, SSO.
- IP allowlists per API key, withdrawal address whitelists.
- Fine-grained per-key permission scopes (e.g. read-only keys) — *good Phase 6 stretch*.

## Open questions
- [ ] HS256 (one secret) vs RS256 (key pair) for the JWT? (Proposal: HS256 for v1; revisit at
      Phase 4 when the gateway and engine split into separate processes.)
- [ ] Refresh token store: Postgres table or Redis? (Proposal: Postgres for v1 — simpler,
      durable, low volume.)
- [ ] Do deposits/withdrawals require a `Signed` request too, or just `JWT`? (Proposal: `JWT`
      for v1 since deposit is simulated; require `Signed` for withdraw once real.)

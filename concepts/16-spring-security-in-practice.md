# 16 — Spring Security & JWT in Practice

> The *design* is in [docs/04](../docs/04-auth-and-authz.md) and the *theory* (what a JWT is,
> hashing, HMAC) in [concepts/04](04-auth-and-authz.md). This file is about *how it actually
> works inside Spring* — the filter chain, stateless auth, and the pieces we build.

## The filter chain: where auth happens

Every HTTP request passes through a **chain of filters** before it reaches your controller.
Spring Security inserts its own filters into that chain. Think of it as a series of gates:

```
request → [ ...security filters... ] → DispatcherServlet → your @RestController
```

By default (what you saw as the 401 on `/ping`), Security's filters demand HTTP Basic / form
login and a server-side **session**. We don't want that for an API. We want **stateless JWT**:
no session, each request proves who it is by carrying a token.

## Stateless vs session — the key shift

- **Session-based** (the default): you log in once, the server stores a session, the browser
  sends a session cookie on each request. The server *remembers* you.
- **Stateless / JWT** (what we build): the server remembers *nothing*. Each request carries a
  **JWT** in the `Authorization: Bearer <token>` header. A filter verifies the token's
  signature on every request and rebuilds "who you are" from its claims. No server memory.

Stateless wins for APIs: it scales horizontally (any server can verify any token — no shared
session store) and fits the fast, no-DB-lookup auth we want (concept 04).

## The SecurityContext — where "who is logged in" lives

During a single request, Spring stores the authenticated identity in the **`SecurityContext`**
(a thread-local). It holds an **`Authentication`** object with:
- the **principal** (who you are — for us, the account id / a small user object),
- **authorities** (what roles/permissions you have — `ROLE_TRADER`, etc.).

Your controllers read it via `@AuthenticationPrincipal` or `SecurityContextHolder`. The whole
job of our JWT filter is: *validate the token, then put an `Authentication` into the
SecurityContext* so the rest of the request knows who's calling.

## The end-to-end flow we're building

```
REGISTER:  POST /v1/auth/register  →  hash password (BCrypt)  →  save user + account
LOGIN:     POST /v1/auth/login     →  verify password  →  issue a signed JWT  →  return it
CALL:      POST /v1/wallet/deposit  with  Authorization: Bearer <jwt>
              → JwtAuthFilter verifies signature + expiry
              → builds an Authentication(principal = accountId) into the SecurityContext
              → controller reads the account id from the principal (NOT the request body)
```

That last line closes the temporary hole from step 0.3: `accountId` stops coming from the
request body and comes from the *verified token* instead. A user can now only deposit into
*their own* account.

## The pieces (what we'll write)

| Piece | Role |
|-------|------|
| `PasswordEncoder` bean | hashes passwords on register, checks them on login (BCrypt) |
| `JwtService` (util) | **issue** a signed JWT (login) and **parse/verify** one (filter) |
| `JwtAuthFilter` | runs once per request; reads the Bearer token, verifies it, populates the SecurityContext |
| `SecurityConfig` (real) | stateless; permit `/auth/**`; authenticate everything else; install the filter |
| `AuthController` | `register` + `login` endpoints |

## Password hashing in Spring

You never hash by hand. You declare a `PasswordEncoder` bean and use it:
```java
String hash = encoder.encode(rawPassword);          // on register → store hash
boolean ok  = encoder.matches(rawPassword, hash);   // on login → verify
```
We use **BCrypt** (built into Spring Security, salted, slow-by-design). The design doc prefers
argon2id; BCrypt is the accepted, zero-extra-dependency choice for v1 (the upgrade is logged in
tech-debt if we want it).

## Issuing & verifying a JWT (with the jjwt library)

- **Issue** (login): build a token with claims (`sub` = account id, `roles`), an expiry, and
  **sign** it with the secret (`JWT_SIGNING_SECRET` from config, HS256).
- **Verify** (every request): parse the token *with the same secret*; jjwt checks the signature
  and expiry and throws if invalid. A valid parse → trust the claims.

The secret lives in **config/env** (`JWT_SIGNING_SECRET`), never in code (doc 09). Anyone with
the secret can mint tokens, so it's a real secret.

## `OncePerRequestFilter` — the custom filter base

Our `JwtAuthFilter extends OncePerRequestFilter` (guarantees it runs exactly once per request).
Its `doFilterInternal`:
1. read the `Authorization` header; if no `Bearer` token, just continue (let it hit a
   `permitAll` route, or get rejected by `authenticated()`).
2. verify the token; on success, build a `UsernamePasswordAuthenticationToken` with the
   principal + authorities and set it on the `SecurityContext`.
3. `filterChain.doFilter(...)` to continue.
We register it **before** Spring's `UsernamePasswordAuthenticationFilter` in the chain.

## Authorization (RBAC) ties back to doc 04
Once the `Authentication` has authorities (`ROLE_TRADER`), `SecurityConfig` can gate routes
(`.requestMatchers("/v1/admin/**").hasRole("ADMIN")`) and you can use `@PreAuthorize`. For
Phase 0 we mostly need "authenticated vs not"; richer rules come with orders/admin later.

## What we are NOT doing yet (scoped)
- **HMAC request signing** (the `Signed` endpoints in doc 03) — that's for *order placement*;
  we add it when orders exist (Phase 1+). Deposits use plain JWT.
- **Refresh tokens / logout** — design is in doc 04; for v1 we can start with just access
  tokens and add refresh when needed.
- These are deliberate scope cuts; note them so they're choices, not omissions.

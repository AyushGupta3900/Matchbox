# Syntax 07 — REST Controller, DTO & a Temporary Security Opening

> Reference for the **Phase-0 "done when": deposit end-to-end + prove the ledger sums to zero.**
> We expose the `DepositService` over HTTP, open it past Spring Security *temporarily* (real
> auth is step 0.4), then verify with psql. Read [concepts/10](../concepts/10-spring-boot-and-dependency-injection.md)
> (layers) and [docs/03](../docs/03-api-contract.md) (the contract) for context.

## 1. The request DTO (a `record`)

A **DTO** (Data Transfer Object) is the shape of the request body — separate from your entities.
A Java `record` is perfect: immutable, concise. Put it in `settlement/api/`.

```java
package com.matchbox.settlement.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// amount is a STRING per the API contract (doc 03) — JSON numbers can't safely hold big ints.
public record DepositRequest(
        @NotNull Long accountId,     // TEMPORARY: real auth supplies this in 0.4
        @NotNull Integer assetId,
        @NotNull String amount       // parse to long in the controller
) {}
```
`@NotNull` etc. are Bean Validation annotations; they fire when the controller marks the body
`@Valid`. (For a quick first pass you may skip validation, but it's one annotation.)

## 2. The controller (thin — parse, delegate, respond)

```java
package com.matchbox.settlement.api;

import com.matchbox.settlement.service.DepositService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final DepositService depositService;

    @PostMapping("/deposit")
    public ResponseEntity<Void> deposit(@RequestBody @Valid DepositRequest req) {
        long amount = Long.parseLong(req.amount());           // string -> long (contract)
        depositService.deposit(req.accountId(), req.assetId(), amount);
        return ResponseEntity.status(201).build();            // 201 Created
    }
}
```
No business logic here — it parses, calls the service, returns a status (doc 10). The service's
`@Transactional` does the real work.

> The controller passes `req.accountId()` only because we have no auth yet. In **0.4** this
> becomes the authenticated account from the JWT, and `accountId` leaves the request body.

## 3. Temporarily open it past Spring Security

Right now Security locks everything (the 401 you saw). For this proof, add a **temporary**
filter chain that permits what we need. Put it in `config/` or `security/`.

```java
package com.matchbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// TEMPORARY (step 0.3 proof). Step 0.4 replaces this with JWT auth + proper rules.
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())                          // APIs don't use CSRF cookies
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/ping", "/v1/wallet/**").permitAll()
                .anyRequest().authenticated());
        return http.build();        // .build() turns the HttpSecurity into the SecurityFilterChain
    }
}
```
This permits health, ping, and the wallet endpoint; everything else stays locked. **Do not keep
`permitAll` on a money endpoint** beyond this proof — 0.4 puts auth in front of it.

## 4. Create a test account to deposit into

Only the system account (id 1) exists. Make a normal user + account via psql:
```bash
PGPASSWORD=localdev psql -h localhost -p 5433 -U matchbox -d matchbox <<'SQL'
INSERT INTO users (email, password_hash, status)
VALUES ('ada@example.com', 'x', 'ACTIVE')
ON CONFLICT (email) DO NOTHING;

INSERT INTO accounts (user_id, status)
SELECT id, 'ACTIVE' FROM users WHERE email = 'ada@example.com'
ON CONFLICT (user_id) DO NOTHING;

SELECT a.id AS account_id, u.email FROM accounts a JOIN users u ON u.id=a.user_id;
SQL
```
Note the `account_id` for `ada@example.com` (likely **2**) — that's what you'll deposit into.
Asset ids: USD = 1, BTC = 2 (from the seed).

## 5. Run and deposit

```bash
docker compose up -d postgres-primary
./mvnw spring-boot:run
```
In another terminal — deposit $100.00 (= 10000 cents) into account 2, asset USD (1):
```bash
curl -i -X POST localhost:8081/v1/wallet/deposit \
  -H 'Content-Type: application/json' \
  -d '{"accountId": 2, "assetId": 1, "amount": "10000"}'
# expect: HTTP/1.1 201
```

## 6. Prove the invariant (the "done when")

```bash
PGPASSWORD=localdev psql -h localhost -p 5433 -U matchbox -d matchbox <<'SQL'
-- 1) the ledger must sum to ZERO per asset (double-entry holds)
SELECT asset_id, sum(amount) AS net FROM ledger_entries GROUP BY asset_id;

-- 2) the two balanced entries from the deposit
SELECT account_id, asset_id, amount, entry_type FROM ledger_entries ORDER BY id;

-- 3) the user's available balance went up by the deposit
SELECT * FROM balances WHERE account_id = 2;
SQL
```
**Done when:** query (1) shows `net = 0` for asset 1, query (2) shows `+10000` (account 2) and
`-10000` (account 1, system), and query (3) shows account 2 has `available = 10000`. Deposit
again and re-check — it must *still* sum to zero.

## 7. What this proves
- A deposit moves money from the system account into the user, **balanced to zero**.
- `@Transactional` made all four writes commit together.
- The user's spendable balance reflects the deposit.

That is the Phase-0 milestone. (Real auth, register/login, and `accountId`-from-JWT come in 0.4;
this temporary `permitAll` gets replaced there.)

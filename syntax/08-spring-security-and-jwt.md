# Syntax 08 — Spring Security + JWT (Step 0.4)

> Reference for **Step 0.4 — real authentication**. Read [concepts/16](../concepts/16-spring-security-in-practice.md)
> first. `JwtService` and `JwtAuthFilter` below are worked fully (security plumbing you should
> understand, not re-derive); the **endpoints and wiring are yours** to write.

## 0. Dependencies & config (added for you)

`pom.xml` gets the **jjwt** library (api + impl + jackson). `application.yml` gets:
```yaml
matchbox:
  jwt:
    secret: ${JWT_SIGNING_SECRET:...}     # HS256 needs ≥ 32 bytes; dev fallback provided
    access-ttl: ${JWT_ACCESS_TTL:900}      # seconds (15 min)
```
The secret is a real secret (anyone with it can mint tokens) — it comes from env/config, never
hardcoded (doc 09).

## 1. `PasswordEncoder` bean (hash + verify)

Add to `SecurityConfig`:
```java
@Bean
PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();     // salted, slow-by-design
}
```
Use it: `encoder.encode(raw)` on register, `encoder.matches(raw, hash)` on login. Never hash by hand.

## 2. `JwtService` — issue & verify (worked; put in `security/`)

```java
package com.matchbox.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtService(@Value("${matchbox.jwt.secret}") String secret,
                      @Value("${matchbox.jwt.access-ttl}") long accessTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); // ≥256-bit secret
        this.accessTtlSeconds = accessTtlSeconds;
    }

    /** Called on login. */
    public String issue(long accountId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(accountId))         // sub = account id
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)                              // HS256 inferred from key size
                .compact();
    }

    /** Called by the filter on every request. Throws if invalid/expired. */
    public long verifyAndGetAccountId(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return Long.parseLong(claims.getSubject());
    }
}
```

## 3. `JwtAuthFilter` — validate per request (worked; put in `security/`)

```java
package com.matchbox.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                long accountId = jwtService.verifyAndGetAccountId(header.substring(7));
                var auth = new UsernamePasswordAuthenticationToken(
                        accountId,                                   // principal = account id
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_TRADER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                // invalid/expired token → leave unauthenticated; protected routes return 401/403
            }
        }
        chain.doFilter(req, res);
    }
}
```

## 4. Real `SecurityConfig` (replace the temporary one)

```java
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/ping", "/v1/auth/**").permitAll()
                .anyRequest().authenticated())                 // /v1/wallet/** now needs a JWT
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
```
Note: `/v1/wallet/**` is **no longer** `permitAll` — it falls under `anyRequest().authenticated()`,
so a deposit now requires a valid token. The temporary 0.3 hole is closed.

### Getting 401 instead of 403 (a Spring Security 6 gotcha)
Out of the box you'll see **403** where you expect **401**, for two reasons:
1. Spring Security 6 returns 403 for an *unauthenticated* request unless you set an
   `authenticationEntryPoint`.
2. A controller that throws `ResponseStatusException(401)` triggers an internal forward to
   `/error`, which the filter re-blocks as 403.

Fix both in the chain:
```java
.authorizeHttpRequests(auth -> auth
    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()      // (2) let /error forwards through
    .requestMatchers("/actuator/**", "/ping", "/v1/auth/**").permitAll()
    .anyRequest().authenticated())
.exceptionHandling(e -> e.authenticationEntryPoint(               // (1) unauthenticated -> 401
    (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
```
(imports: `jakarta.servlet.DispatcherType`, `jakarta.servlet.http.HttpServletResponse`.)

## 5. Repository finders you'll need (add these)

```java
// UserRepository
Optional<User> findByEmail(String email);

// AccountRepository
Optional<Account> findByUserId(Long userId);   // to get the account id for the JWT on login
```

## 6. Your turn — `AuthController` (`/v1/auth`) + DTOs

Put in `security/api/` (or `account/api/`). Two endpoints:

**`POST /v1/auth/register`** — create user + account with a hashed password:
- `record RegisterRequest(@Email String email, @NotBlank String password)`
- hash: `String hash = passwordEncoder.encode(req.password());`
- save a `User` (status ACTIVE, `passwordHash = hash`), then an `Account` for that user
- return `201` with `{ "userId":..., "accountId":... }`

**`POST /v1/auth/login`** — verify and issue a JWT:
- `record LoginRequest(@Email String email, @NotBlank String password)`
- `User u = userRepo.findByEmail(email).orElseThrow(... 401 ...)`
- `if (!passwordEncoder.matches(req.password(), u.getPasswordHash())) → 401`
- find the account id: `accountRepo.findByUserId(u.getId())`
- `String token = jwtService.issue(accountId, "TRADER");`
- return `{ "access_token": token, "token_type":"Bearer", "expires_in":900 }`

(Manual login is fine and clearest here; Spring's `AuthenticationManager` is the heavier
alternative — not needed for v1.)

## 7. Wire the deposit to the authenticated account (close the hole)

`DepositRequest` **drops** `accountId` (it now comes from the token):
```java
public record DepositRequest(@NotNull Integer assetId, @NotNull String amount) {}
```
`WalletController` reads the principal the filter set:
```java
@PostMapping("/deposit")
public ResponseEntity<Void> deposit(@AuthenticationPrincipal Long accountId,
                                    @RequestBody @Valid DepositRequest req) {
    depositService.deposit(accountId, req.assetId(), Long.parseLong(req.amount()));
    return ResponseEntity.status(201).build();
}
```
Now a user can only deposit into **their own** account — the id is proven by the token, not
claimed in the body.

## 8. Run & verify the full flow

```bash
docker compose up -d postgres-primary
./mvnw spring-boot:run
```
```bash
# register
curl -s -X POST localhost:8081/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"s3cret-pw"}'

# login -> capture the token
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"s3cret-pw"}' | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
echo "$TOKEN"

# deposit WITHOUT a token -> 401 (proves the endpoint is now protected)
curl -s -o /dev/null -w "no-token: HTTP %{http_code}\n" -X POST localhost:8081/v1/wallet/deposit \
  -H 'Content-Type: application/json' -d '{"assetId":1,"amount":"10000"}'

# deposit WITH the token -> 201
curl -s -o /dev/null -w "with-token: HTTP %{http_code}\n" -X POST localhost:8081/v1/wallet/deposit \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"assetId":1,"amount":"10000"}'
```
**Done when:** register `201`, login returns a token, deposit **without** a token is `401`,
deposit **with** the token is `201`, and the ledger still sums to zero (psql, syntax/07 §6).
The `password_hash` column now holds a real BCrypt hash, not `'x'`.

## Gotchas
- **Secret too short** → jjwt throws "key length" at startup. HS256 needs ≥ 32 bytes; the dev
  fallback is long enough.
- **`@AuthenticationPrincipal` is null** → the filter didn't set the context (bad/missing token,
  or filter not registered). Check `addFilterBefore` and the `Bearer ` prefix.
- **Register then login fails** → password stored/looked up inconsistently; ensure you store
  `encode(raw)` and check with `matches(raw, hash)`.
- **Existing `ada` row from 0.3** has `password_hash = 'x'` (not a real hash) → register a fresh
  email, or update/delete the old row.

# Environment & Configuration Plan — MatchBox Exchange

> Status: **Draft v1** · Last updated: 2026-06-09
> Read [concepts/09-environment-and-config.md](../concepts/09-environment-and-config.md) first.

## Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Config mechanism | **Spring profiles + env-var overrides** | non-secret defaults in repo, secrets from env |
| Environments (v1) | **dev**, **test** (CI), **prod** (later) | staging skipped for a learning project |
| Secrets | **env vars** (v1) → secret manager (if deployed) | never in git; rotation needs no code change |
| Secret template | committed **`.env.example`**; real `.env` git-ignored | self-documenting, leak-proof |
| Log/runtime tunables | config, not code | change per env without rebuilds |

## Environments

| Env | DB / Redis / Kafka | Logs | Secrets | Notes |
|-----|--------------------|------|---------|-------|
| **dev** | local, via docker-compose | DEBUG, pretty/JSON to stdout | dummy values in local `.env` | zero-setup defaults; profile `dev` |
| **test** (CI) | ephemeral (Testcontainers) | WARN | generated per run | deterministic; migrations run first |
| **prod** (later) | managed instances | INFO (JSON) to stdout→aggregator | injected by the platform | profile `prod`; strict |

Environments never share a database. `spring.profiles.active` selects the profile;
`SPRING_PROFILES_ACTIVE=dev` by default locally.

## Config file structure

```
src/main/resources/
├── application.yml            # non-secret DEFAULTS, common to all envs
├── application-dev.yml        # dev overrides (local hosts, verbose logs)
├── application-test.yml       # CI overrides
└── application-prod.yml       # prod overrides (no secrets inline — ${ENV} refs only)
```

Secrets are **never** written in these files — they appear only as `${ENV_VAR}` placeholders,
resolved from the environment at startup.

## Configuration catalog (the operational contract)

| Variable | Kind | Example (dev) | Used by | Doc |
|----------|------|---------------|---------|-----|
| `SPRING_PROFILES_ACTIVE` | toggle | `dev` | app | this |
| `DB_URL` | conn | `jdbc:postgresql://localhost:5432/matchbox` | settlement, all persistence | 02, 05 |
| `DB_USER` | conn | `matchbox` | persistence | 05 |
| `DB_PASSWORD` | **secret** | *(dummy in dev)* | persistence | 05 |
| `REDIS_URL` | conn | `redis://localhost:6379` | nonces, rate limit | 04, 05 |
| `KAFKA_BROKERS` | conn | `localhost:9092` | events (Phase 2) | 05 |
| `JWT_SIGNING_SECRET` | **secret** | *(dummy in dev)* | auth (sign/verify JWT) | 04 |
| `JWT_ACCESS_TTL` | tunable | `900` (s) | auth | 04 |
| `JWT_REFRESH_TTL` | tunable | `604800` (s) | auth | 04 |
| `APIKEY_ENC_KEY` | **secret** | *(dummy in dev)* | encrypt/decrypt API secrets | 04 |
| `HMAC_TIMESTAMP_WINDOW_MS` | tunable | `5000` | signing/replay | 04 |
| `RATE_LIMIT_ORDERS_PER_SEC` | tunable | `50` | gateway | 03, 06 |
| `LOG_LEVEL` | tunable | `DEBUG` | logging | 07 |

> This table *is* the `.env.example`. A new dev sets these and the app runs. Every secret is
> marked — those are the ones that must come from the environment and never from git.

## `.env.example` (committed) — shape

```dotenv
# Copy to .env and fill in. .env is git-ignored. NEVER commit real secrets.
SPRING_PROFILES_ACTIVE=dev

DB_URL=jdbc:postgresql://localhost:5432/matchbox
DB_USER=matchbox
DB_PASSWORD=change-me

REDIS_URL=redis://localhost:6379
KAFKA_BROKERS=localhost:9092

JWT_SIGNING_SECRET=change-me-long-random
JWT_ACCESS_TTL=900
JWT_REFRESH_TTL=604800
APIKEY_ENC_KEY=change-me-32-bytes
HMAC_TIMESTAMP_WINDOW_MS=5000

RATE_LIMIT_ORDERS_PER_SEC=50
LOG_LEVEL=DEBUG
```

## Precedence (later wins)
1. defaults in `application.yml`
2. active profile file (`application-<env>.yml`)
3. **environment variables** (where prod gets real + secret values)
4. command-line overrides

## Secret handling rules
- Real `.env` is git-ignored ([already configured](../.gitignore)); only `.env.example` is committed.
- No secret literal ever appears in `application*.yml` — only `${VAR}` refs.
- A leaked secret is rotated by changing the env var — **zero code changes** (the test that
  config ≠ code).
- docker-compose pulls secrets from the shell/`.env` via `${VAR}` interpolation, not inline.
- Generating strong dev secrets: `openssl rand -hex 32`.

## How config enters at runtime
- **Local:** docker-compose + a local `.env`; Spring reads env vars + `application-dev.yml`.
- **CI:** env vars set by the pipeline; Testcontainers provides DB/Redis endpoints dynamically.
- **prod (later):** the platform injects env vars / mounts secrets; profile `prod` selected.

## Out of scope for v1
- A real secret manager (Vault/AWS SM) — env vars suffice until we actually deploy.
- Dynamic/hot config reload — restart to apply config changes in v1.
- Feature-flag service — none needed yet.

## Open questions
- [ ] Generate `JWT_SIGNING_SECRET`/`APIKEY_ENC_KEY` per-dev, or share a committed *dummy* dev
      value? (Proposal: committed dummy in `.env.example` for dev only; real randoms in prod.)
- [ ] Single `.env` for app + docker-compose, or separate? (Proposal: single root `.env` for
      simplicity in v1.)

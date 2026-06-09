# 09 — Environment & Configuration

## The core idea: separate config from code

The same compiled application should run on your laptop, in CI, and in production **without
changing a single line of code** — only its *configuration* changes (which database, which
secret, which log level). If a value differs between environments, it's **config**, not code,
and it must live *outside* the build.

This is the heart of the "12-factor app" idea: **config lives in the environment** (env vars),
not hardcoded and not committed. Bake a database password into the jar and you've (a) leaked a
secret into version control forever and (b) made the jar un-deployable anywhere else.

## What counts as config

Anything that varies by environment or is sensitive:
- **Connection details** — DB URL/host, Redis URL, Kafka brokers.
- **Credentials/secrets** — DB password, JWT signing secret, the API-key encryption key.
- **Tunables** — log level, token TTLs, rate-limit numbers, thread/pool sizes.
- **Toggles** — feature flags, which profile is active.

What is *not* config: business logic, the schema, anything that's the same everywhere. Don't
make everything configurable "just in case" — every knob is a thing that can be set wrong.

## Environment separation: dev / test / staging / prod

You run the system in distinct **environments**, each isolated:

- **dev** — your machine; local Postgres/Redis in Docker; fake data; verbose logs.
- **test** — ephemeral, created and destroyed per CI run; throwaway DB; deterministic.
- **staging** — prod-like, for final checks (we may skip a real one in a learning project).
- **prod** — the real thing; real data; strict secrets; minimal logging.

The point of separation is **blast radius**: a mistake in dev can't touch prod data, and you
can experiment freely in one without risking the other. They must never share a database.

## Secrets management — the part you cannot get wrong

A **secret** (password, signing key, API secret) must **never** be committed to git. Even if
you delete it later, it's in the history forever and must be considered compromised. Rules:

- Secrets come from the **environment** (env vars) or a **secret manager** (Vault, AWS Secrets
  Manager, etc.), injected at runtime.
- The repo contains a **`.env.example`** — the *names* of the variables with dummy/blank values,
  as documentation — and `.gitignore` blocks the real `.env`.
- Rotating a secret (changing it) should require **zero code changes** — because it's config.

This is why the very first thing we did was put `.env` in `.gitignore`. One leaked production
secret can end a project.

## Config precedence (how a value is resolved)

Tools layer config sources so a more-specific source overrides a general one. Typical order
(later wins):

1. Built-in **defaults** (in the code / a base config file) — safe, non-secret fallbacks.
2. **Per-profile** file (`application-dev.yml`, `application-prod.yml`).
3. **Environment variables** — override anything; this is where prod gets its real values.
4. **Command-line / runtime overrides** — last resort.

The mental model: **defaults in the repo, real and secret values from the environment.** A
developer can run with zero setup (defaults); prod supplies the rest via env vars.

## Spring's take: profiles + externalized config

Spring Boot implements all of the above:
- **Profiles** (`spring.profiles.active=dev`) select which `application-<profile>.yml` loads.
- `application.yml` holds **non-secret defaults**; profile files hold per-env differences.
- Any property can be overridden by an **env var** (`SPRING_DATASOURCE_PASSWORD`), which is how
  secrets enter without ever being written in a file.
- `${DB_PASSWORD}` placeholders in config pull from the environment at startup.

## Config as documentation
A good `.env.example` (or a config table in this doc) doubles as a checklist: "here is
everything this service needs to run." A new developer reads it and knows exactly what to set.
That's why we *write the config plan down* even though it feels like plumbing — it's the
operational contract for running the system.

## Tie-back
This closes the loop with earlier docs: the JWT secret and API-key encryption key
([04-auth-and-authz.md](../docs/04-auth-and-authz.md)) are **secrets** managed here; the
log-level and sink ([07](../docs/07-error-handling-and-logging.md)) are **tunables** here; the
DB/Redis/Kafka endpoints ([05](../docs/05-architecture.md)) are **connection config** here.
Config is where all the moving parts get their real-world wiring.

# Syntax 01 — Project Scaffold (Spring Boot + Maven + Postgres)

> Reference shapes for **Step 0.1**. Read [concepts/10](../concepts/10-spring-boot-and-dependency-injection.md)
> first. Use these as a guide — type them yourself; don't blind-copy. The `pom.xml`,
> `docker-compose.yml`, and `application*.yml` are config you can adapt directly; the Java is
> a *shape* to write your own from.

## 1. Generate the project (Spring Initializr via curl)

Run from `/Users/ayushgupta/Desktop/MatchBox`. This downloads a ready Maven project into a
temp dir and unpacks it so you can copy the parts you want (don't let it clobber your docs).

```bash
curl https://start.spring.io/starter.tgz \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.4.5 \
  -d javaVersion=21 \
  -d groupId=com.matchbox \
  -d artifactId=matchbox \
  -d name=matchbox \
  -d packageName=com.matchbox \
  -d dependencies=web,data-jpa,postgresql,flyway,security,validation,actuator \
  -o /tmp/matchbox.tgz
mkdir -p /tmp/matchbox && tar -xzf /tmp/matchbox.tgz -C /tmp/matchbox && ls /tmp/matchbox
```

Then copy `pom.xml`, `src/`, `mvnw`, `mvnw.cmd`, `.mvn/` into the repo root. (Keep your
existing `docs/`, `concepts/`, `syntax/`, `.gitignore`.)

> Prefer a UI? https://start.spring.io with the same options, then unzip into the repo.

## 2. Target directory layout (after scaffolding)

```
MatchBox/
├── pom.xml
├── mvnw  mvnw.cmd  .mvn/
├── docker-compose.yml                 # you add this (section 4)
├── docs/ concepts/ syntax/            # already here
└── src/
    ├── main/
    │   ├── java/com/matchbox/
    │   │   ├── MatchboxApplication.java        # @SpringBootApplication, main()
    │   │   ├── common/                         # ids, time, fixed-point types
    │   │   ├── security/                        # auth (step 0.4)
    │   │   ├── settlement/                      # accounts, ledger (step 0.3)
    │   │   │   ├── api/         # @RestController
    │   │   │   ├── service/     # @Service
    │   │   │   ├── domain/      # entities
    │   │   │   └── repo/        # @Repository
    │   │   └── config/                          # @Configuration beans
    │   └── resources/
    │       ├── application.yml
    │       ├── application-dev.yml
    │       └── db/migration/                    # Flyway V1__*.sql (step 0.2)
    └── test/java/com/matchbox/
```

The package layout mirrors the modules from
[docs/05-architecture.md](../docs/05-architecture.md). Start the folders empty; fill per step.

## 3. `pom.xml` essentials (what Initializr produces — know these parts)

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.4.5</version>
</parent>

<properties>
  <java.version>21</java.version>
</properties>

<dependencies>
  <dependency><groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-security</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-validation</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-actuator</artifactId></dependency>

  <dependency><groupId>org.flywaydb</groupId>
              <artifactId>flyway-core</artifactId></dependency>
  <dependency><groupId>org.flywaydb</groupId>
              <artifactId>flyway-database-postgresql</artifactId></dependency>   <!-- add if not present -->
  <dependency><groupId>org.postgresql</groupId>
              <artifactId>postgresql</artifactId><scope>runtime</scope></dependency>

  <dependency><groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
</dependencies>
```
Versions are managed by the `spring-boot-starter-parent` — you usually omit `<version>` on
Spring deps. (JWT + Testcontainers deps get added in later steps.)

## 4. `docker-compose.yml` — Postgres for local dev

Create at the repo root. Brings up Postgres for the `dev` environment (per
[docs/09](../docs/09-environment-and-config.md)).

```yaml
services:
  postgres-primary:
    image: postgres:16
    container_name: matchbox-postgres
    environment:
      POSTGRES_DB: matchbox
      POSTGRES_USER: matchbox
      POSTGRES_PASSWORD: ${DB_PASSWORD:-localdev}   # from .env; dev fallback
    ports:
      - "5432:5432"
    volumes:
      - matchbox-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U matchbox -d matchbox"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  matchbox-pgdata:
```

Start it: `docker compose up -d postgres-primary`
Logs / status: `docker compose ps` · `docker compose logs -f postgres-primary`
Stop: `docker compose down` (add `-v` to also wipe the volume).

## 5. `application.yml` (non-secret defaults) + `application-dev.yml`

`src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: matchbox
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/matchbox}
    username: ${DB_USER:matchbox}
    password: ${DB_PASSWORD:localdev}
  jpa:
    hibernate:
      ddl-auto: validate          # Flyway owns the schema; Hibernate only validates
    open-in-view: false           # avoid the lazy-loading-in-controller anti-pattern
  flyway:
    enabled: true
    locations: classpath:db/migration

management:
  endpoints:
    web:
      exposure:
        include: health,info       # actuator: only what we want exposed
  endpoint:
    health:
      show-details: when_authorized
```

`src/main/resources/application-dev.yml`:
```yaml
logging:
  level:
    root: INFO
    com.matchbox: DEBUG
spring:
  jpa:
    show-sql: true                 # see the SQL Hibernate runs (dev only)
```

> `ddl-auto: validate` is deliberate: **Flyway** creates tables (doc 08), Hibernate must
> never auto-create/alter them — it only checks the entities match the migrated schema.

## 6. The application entry point (shape — Initializr generates this)

```java
package com.matchbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication            // = @Configuration + @EnableAutoConfiguration + @ComponentScan
public class MatchboxApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchboxApplication.class, args);
    }
}
```
`@ComponentScan` scans `com.matchbox` and below — keep all your packages under it.

## 7. A minimal controller (shape — write your own to test the wiring)

```java
package com.matchbox.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {
    @GetMapping("/ping")
    public String ping() { return "pong"; }
}
```
> Security note: with `spring-boot-starter-security` on the classpath, **every** endpoint is
> locked by default (HTTP Basic, a generated password in the logs). For Step 0.1 that's fine —
> you'll configure a proper `SecurityFilterChain` in Step 0.4. To check `/ping` now, either use
> the logged password, or temporarily permit it (we'll do this properly later).

## 8. Build, run, verify

```bash
./mvnw clean compile         # compile
docker compose up -d postgres-primary   # DB must be up (Flyway connects on boot)
./mvnw spring-boot:run       # run the app

# in another terminal:
curl -i localhost:8080/actuator/health         # -> {"status":"UP"} (once DB reachable)
```
- App fails to start with "connection refused" → Postgres isn't up; start it first.
- Health is `DOWN` → check `docker compose logs postgres-primary` and your datasource config.

## 9. Lombok — generate boilerplate at compile time

Lombok reads annotations and **generates** getters/setters/constructors/etc. during
compilation, so your source stays clean. The generated methods are real bytecode — they exist
at runtime, you just don't see them in the `.java` file.

### IDE setup (do this once, or Lombok looks "broken")
Lombok is an annotation processor. Your **IDE must understand it** or it'll red-underline
everything:
- **VS Code:** install the **"Lombok Annotations Support"** extension (or it ships with the
  Java extension pack in recent versions). Reload the window after.
- IntelliJ: enable annotation processing (newer versions detect Lombok automatically).
- Command-line `./mvnw` already works — Lombok runs via the Maven compiler plugin.

### The annotations you'll actually use
| Annotation | Generates |
|------------|-----------|
| `@Getter` / `@Setter` | getters / setters for all fields (or one field) |
| `@NoArgsConstructor` | a no-arg constructor (**JPA entities require one**) |
| `@AllArgsConstructor` | constructor with every field |
| `@RequiredArgsConstructor` | constructor for `final` fields — **great for DI** (no `@Autowired` needed) |
| `@Builder` | a fluent builder: `Order.builder().price(100).build()` |
| `@ToString` / `@EqualsAndHashCode` | those methods (use carefully on entities — see below) |
| `@Slf4j` | a ready `log` field: `log.info(...)` (our structured logging, doc 07) |
| `@Data` | bundles Getter+Setter+ToString+EqualsAndHashCode+RequiredArgsConstructor |

### Shapes
A service with constructor injection becomes one line of boilerplate-free DI:
```java
@Service
@RequiredArgsConstructor          // injects the final fields via constructor
@Slf4j
public class DepositService {
    private final LedgerRepository ledger;   // injected by Spring
    // ... methods can use `ledger` and `log`
}
```
A DTO / value object:
```java
@Getter
@Builder
@AllArgsConstructor
public class DepositRequest {
    private final String asset;
    private final long amount;
}
```

### Caveats for *this* project (Lombok can bite)
- **Don't put `@Data` / `@EqualsAndHashCode` / `@ToString` blindly on JPA entities.** They can
  trigger lazy-loading, infinite loops across relationships, or break Hibernate's identity.
  For entities, prefer explicit `@Getter @Setter @NoArgsConstructor` and hand-pick
  equals/hashCode (usually by the `id` only).
- **It hides what's generated.** While you're *learning*, occasionally let your IDE "show
  generated sources" (or de-Lombok one class) to see what it produced — so Lombok stays a
  convenience, not magic you can't reason about.

## Common Maven commands (cheat sheet)
| Command | Does |
|---------|------|
| `./mvnw compile` | compile main sources |
| `./mvnw test` | run tests |
| `./mvnw spring-boot:run` | run the app |
| `./mvnw clean package` | build a runnable jar in `target/` |
| `./mvnw dependency:tree` | see the full dependency graph |

> Use the **wrapper** `./mvnw` (committed with the project) rather than a system `mvn`, so
> everyone builds with the same Maven version.

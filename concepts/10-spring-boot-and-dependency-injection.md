# 10 — Spring Boot & Dependency Injection

## What Spring Boot actually is

**Spring** is a framework that wires your application together. **Spring Boot** is Spring with
sensible defaults + an embedded server, so you can run a real web app with almost no
configuration. You add a "starter" dependency (e.g. `spring-boot-starter-web`) and Boot
**auto-configures** the pieces (an HTTP server, JSON handling, etc.) based on what's on the
classpath. You write the logic; Boot wires the plumbing.

The mental shift: you don't write a `main()` that manually creates a web server, opens a DB
connection pool, parses JSON, and routes requests. You *declare* what you need; Spring builds
and connects it.

## The core idea: Inversion of Control (IoC) & Dependency Injection (DI)

This is the single most important Spring concept. Learn it once and the whole framework makes
sense.

**Without DI** — an object creates its own dependencies:
```
class OrderService {
    DB db = new PostgresDB("localhost", 5432, "secret");  // OrderService is now
                                                          // glued to Postgres + this config
}
```
Problems: `OrderService` is welded to a *specific* database, you can't swap it for a fake in
tests, and the connection config leaks everywhere.

**With DI** — the object *declares* what it needs; something else supplies it:
```
class OrderService {
    private final Ledger ledger;
    OrderService(Ledger ledger) { this.ledger = ledger; }   // "give me a Ledger"
}
```
`OrderService` no longer knows or cares *which* `Ledger` it gets. In production Spring injects
the real one; in tests you inject a fake. That swap-ability is the whole payoff.

**Inversion of Control** = you don't call the framework to build things; the framework builds
your objects and hands them their dependencies. Control of object creation is *inverted* from
you to the container.

## Beans & the application context

- A **bean** is just an object that Spring manages (creates, configures, injects). You mark a
  class with a stereotype annotation and Spring registers it as a bean.
- The **application context** is the container holding all the beans. On startup Spring scans
  your packages, finds the annotated classes, figures out the dependency graph, and constructs
  everything in the right order, injecting dependencies via constructors.
- You rarely say `new` for your own services — you declare them as beans and let Spring wire
  them.

**Stereotype annotations** (they all make a bean; the name documents intent):
- `@RestController` — handles HTTP requests, returns JSON.
- `@Service` — business logic.
- `@Repository` — data access (talks to the DB).
- `@Component` — generic bean (none of the above fits).
- `@Configuration` — a class that *defines* beans via `@Bean` methods.

**Constructor injection** is the right way (over field injection): dependencies are `final`,
visible in the signature, and the object can't exist half-built. A class with one constructor
needs no `@Autowired` — Spring just uses it.

## Layered architecture (how we organize a request)

A request flows through layers, each with one job. This keeps logic out of controllers and
SQL out of business code:

```
HTTP request
   │
   ▼
@RestController   ← parse/validate input, call a service, shape the HTTP response.
   │                NO business logic here.
   ▼
@Service          ← the actual business rules + transaction boundaries.
   │                Knows nothing about HTTP.
   ▼
@Repository       ← read/write the database. Knows nothing about business rules.
   │
   ▼
Database
```

The win: each layer is testable and replaceable in isolation, and a rule lives in exactly one
place. A controller that contains business logic, or a service that builds JSON, is a smell.
(This maps onto our module structure from [05-architecture.md](../docs/05-architecture.md):
`gateway` = controllers, `settlement` = services + repositories.)

## What each Phase-0 starter gives us

| Starter / dependency | Provides |
|----------------------|----------|
| `spring-boot-starter-web` | embedded Tomcat, REST controllers, JSON (Jackson) |
| `spring-boot-starter-data-jpa` | JPA/Hibernate ORM + Spring Data repositories |
| `postgresql` (driver) | the JDBC driver to talk to Postgres |
| `flyway-core` (+ pg module) | versioned DB migrations on startup |
| `spring-boot-starter-security` | authentication/authorization filter chain |
| `spring-boot-starter-validation` | `@Valid` request validation (Jakarta Bean Validation) |
| `spring-boot-starter-actuator` | health/metrics endpoints (`/actuator/health`) |
| `spring-boot-starter-test` | JUnit 5, Mockito, MockMvc, assertions |

## ORM & JPA in one paragraph (we'll go deeper in 0.3)

An **ORM** (Object-Relational Mapper) maps a Java class to a DB table and an object to a row,
so you work with objects instead of hand-writing SQL. **JPA** is the Java standard for this;
**Hibernate** is the implementation Boot uses. **Spring Data JPA** adds repositories: declare
an interface and Spring generates the query code. Powerful, but it hides SQL — so for the
*money* paths we'll stay deliberate about what queries actually run. (More in 0.3.)

## Configuration & profiles (recap from doc 09)

Boot reads `application.yml`. `application-dev.yml` overrides it when the `dev` profile is
active. Secrets come in as `${ENV_VAR}` placeholders — never written in the file. This is the
config-vs-code separation from [09-environment-and-config.md](../docs/09-environment-and-config.md)
made concrete.

## How a Boot app starts (the 10-second version)
1. `main()` calls `SpringApplication.run(App.class)`.
2. Boot creates the application context, scans packages for beans.
3. Auto-configuration wires infra (web server, datasource, etc.) from the classpath + config.
4. Flyway runs pending migrations.
5. The embedded server starts; the app serves requests.

You'll watch all of this in the startup logs the first time you run it.

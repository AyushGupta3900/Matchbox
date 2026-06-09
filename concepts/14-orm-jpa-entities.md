# 14 — ORM, JPA & Entities

## The problem an ORM solves

Your database speaks **tables and rows**. Your Java code speaks **objects**. Without help, you
spend your life writing glue: SQL strings, pulling values out of result sets by column name,
stuffing them into objects, and back again. Tedious and error-prone.

An **ORM** (Object-Relational Mapper) automates that glue. You declare "this Java class maps to
this table," and the ORM handles turning rows into objects and objects into rows — the
`INSERT`/`SELECT`/`UPDATE` SQL is generated for you.

- **JPA** (Jakarta Persistence API) — the Java *standard* (a set of interfaces + annotations)
  for ORMs.
- **Hibernate** — the actual ORM *implementation* Spring Boot uses under the hood.
- **Spring Data JPA** — a layer on top that removes even more boilerplate (repositories).

You write JPA annotations; Hibernate does the SQL; Spring Data gives you ready-made queries.

## An entity = a table, an object = a row

A class marked `@Entity` maps to a table; each instance is one row; each field is a column.

```java
@Entity
@Table(name = "users")
class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;              // -> users.id
    String email;         // -> users.email
    String passwordHash;  // -> users.password_hash
}
```

Hibernate now knows: to save a `User`, `INSERT INTO users ...`; to load one, `SELECT ... FROM
users`. You never write that SQL.

## Naming: camelCase ↔ snake_case

Java uses `passwordHash`; our DB uses `password_hash`. Spring Boot's default naming strategy
maps between them automatically (camelCase field → snake_case column), so `createdAt` finds
`created_at`. You can override per-field with `@Column(name = "...")` when they don't line up.

## Type mapping (our schema → Java)

| Postgres column | Java type | Notes |
|-----------------|-----------|-------|
| `bigint` (id, money) | `Long` / `long` | money stays integer — never `double` |
| `int` / `smallint` | `Integer` / `Short` | |
| `text` | `String` | |
| `timestamptz` | `Instant` (or `OffsetDateTime`) | an instant in UTC |
| `boolean` | `boolean` | |
| enum-like `text + CHECK` | `String`, or a Java `enum` via `@Enumerated(EnumType.STRING)` | store the name, not the ordinal |

> For `side`/`status`/`entry_type`, a Java `enum` + `@Enumerated(EnumType.STRING)` is clean —
> but **always `STRING`, never `ORDINAL`**: ordinal stores 0/1/2, so reordering the enum
> silently corrupts existing data.

## Identity: who generates the id?

Our tables use `GENERATED ALWAYS AS IDENTITY`, so the **database** assigns ids. Tell JPA that:
```java
@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
Long id;
```
`IDENTITY` = "the DB makes the id on insert; read it back." (Other strategies like `SEQUENCE`
exist; `IDENTITY` matches our DDL.)

## Composite keys (our `balances` table)

`balances` has a two-column primary key `(account_id, asset_id)`. JPA needs that expressed as a
*key class*. Two ways; we'll use `@EmbeddedId`:
```java
@Embeddable
class BalanceId {
    Long accountId;
    Integer assetId;
    // equals() + hashCode() required for key classes
}
@Entity @Table(name = "balances")
class Balance {
    @EmbeddedId BalanceId id;
    long available;
    long reserved;
}
```
This is the one fiddly mapping in Phase 0 — composite keys always need a dedicated id type.

## Relationships (use sparingly here)

JPA can map foreign keys to object references (`@ManyToOne`, `@OneToMany`). They're powerful
but bring traps — lazy loading, N+1 queries, accidental huge fetches. **For a money/perf-
sensitive system we keep it simple: store the raw FK as a plain field** (`Long accountId`)
rather than mapping a `@ManyToOne Account`. You lose some convenience, you gain control and
predictable SQL. (We'll map relationships only where they clearly pay off.)

## `ddl-auto: validate` — entities must match the schema

Recall our config (doc 09): `spring.jpa.hibernate.ddl-auto: validate`. This means:
- **Flyway owns the schema** (creates/changes tables via migrations).
- On startup Hibernate **validates** that every `@Entity` matches the real, migrated table —
  right columns, types, nullability. If an entity and table disagree, the app **fails to start
  loudly**.

This is a feature: it catches "I added a field but forgot the migration" (or vice versa) at
boot, not at 2am in production. Never let Hibernate auto-create/auto-update our schema —
migrations are the single source of truth.

## Repositories: queries without writing queries

**Spring Data JPA** gives you a repository by declaring an *interface* — it generates the
implementation:
```java
interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);   // Spring writes the query from the method name
}
```
`JpaRepository<User, Long>` already provides `save`, `findById`, `findAll`, `delete`, etc. And
**derived query methods** like `findByEmail` are parsed from the name into SQL. For anything
complex you drop to `@Query` with explicit JPQL/SQL.

You inject the repository into a service (constructor injection) and call methods — no SQL, no
boilerplate.

## The layers, end to end (recap doc 10)
```
@RestController  → @Service (business logic, @Transactional) → Repository (JpaRepository) → DB
```
Entities are the objects that flow through these layers; repositories move them in and out of
the database; the service decides *what* to do with them.

## Lombok + entities (the caveat from syntax/01)
Use `@Getter @Setter @NoArgsConstructor` on entities (JPA requires a no-arg constructor). **Do
NOT** put `@Data`/`@ToString`/`@EqualsAndHashCode` on an entity blindly — they can trigger lazy
loads or break identity. If you need equals/hashCode, base it on the `id` only.

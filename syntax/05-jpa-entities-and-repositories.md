# Syntax 05 — JPA Entities & Repositories

> Reference for **Step 0.3**. Read [concepts/14](../concepts/14-orm-jpa-entities.md) first. The
> `User` entity + repository below are worked fully — use them as templates and write the rest
> from [docs/02](../docs/02-data-model-erd.md). Entities mirror the tables you already migrated.

## Where these live (feature-based, per concept 11)

```
com.matchbox/
├── account/
│   ├── domain/    User, Account            (entities)
│   └── repo/      UserRepository, AccountRepository
├── asset/
│   ├── domain/    Asset
│   └── repo/      AssetRepository
├── settlement/
│   ├── domain/    Balance, BalanceId, LedgerTransaction, LedgerEntry
│   └── repo/      BalanceRepository, LedgerTransactionRepository, LedgerEntryRepository
```
(Place by domain; you can flatten `domain/`/`repo/` into the feature package while it's small.)

## Entity annotation reference

| Annotation | Purpose |
|------------|---------|
| `@Entity` | marks the class as a mapped table |
| `@Table(name="users")` | the table it maps to (optional if name matches) |
| `@Id` | the primary-key field |
| `@GeneratedValue(strategy = GenerationType.IDENTITY)` | DB assigns the id (matches `GENERATED ALWAYS AS IDENTITY`) |
| `@Column(name="password_hash", nullable=false)` | column mapping/constraints (often inferred) |
| `@Enumerated(EnumType.STRING)` | store a Java enum as its name |
| `@Version` | optimistic-lock version field |
| `@EmbeddedId` / `@Embeddable` | composite primary key |

## Worked example — `User` entity

```java
package com.matchbox.account.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor      // NOT @Data on entities (concept 14)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```
Note: camelCase fields map to snake_case columns automatically (`passwordHash` → `password_hash`),
so most `@Column(name=...)` are optional — shown here for clarity.

## Worked example — `UserRepository`

```java
package com.matchbox.account.repo;

import com.matchbox.account.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);      // query derived from the method name
    boolean existsByEmail(String email);
}
```
`JpaRepository<User, Long>` already gives you `save`, `findById`, `findAll`, `count`, `delete`…
You add only the custom finders.

## The tricky one — composite key for `balances`

`balances` has PK `(account_id, asset_id)`, so it needs a dedicated id class:

```java
// settlement/domain/BalanceId.java
package com.matchbox.settlement.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter; import lombok.Setter; import lombok.NoArgsConstructor;

@Embeddable
@Getter @Setter @NoArgsConstructor
public class BalanceId implements Serializable {
    private Long accountId;     // -> account_id
    private Integer assetId;    // -> asset_id

    // composite key classes MUST implement equals + hashCode
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BalanceId that)) return false;
        return Objects.equals(accountId, that.accountId) && Objects.equals(assetId, that.assetId);
    }
    @Override public int hashCode() { return Objects.hash(accountId, assetId); }
}
```
```java
// settlement/domain/Balance.java
@Entity @Table(name = "balances")
@Getter @Setter @NoArgsConstructor
public class Balance {
    @EmbeddedId
    private BalanceId id;

    @Column(nullable = false) private long available;
    @Column(nullable = false) private long reserved;

    @Version                                  // optimistic locking (concept 15)
    private long version;
}
```
Repository for it keys on the composite id type:
```java
public interface BalanceRepository extends JpaRepository<Balance, BalanceId> { }
```

## Your turn — write these entities (specs in docs/02)

| Entity | Table | Notes |
|--------|-------|-------|
| `Account` | `accounts` | `id`, `userId` (plain `Long`, the FK), `status`, `createdAt` |
| `Asset` | `assets` | `id` is `Integer` here (table uses `int`), `symbol`, `scale` (`Short`), `name` |
| `LedgerTransaction` | `transactions` | `id`, `type`, `createdAt` (class named to avoid clashing with the word "Transaction") |
| `LedgerEntry` | `ledger_entries` | `id`, `transactionId`, `accountId`, `assetId`, `amount` (`long`), `entryType`, `refId` (nullable `Long`), `createdAt` |

Plus their repositories (`extends JpaRepository<Entity, IdType>`). Add finders you'll need:
- `LedgerEntryRepository`: a method to sum amounts per asset (for the zero-check) — use `@Query`:
  ```java
  @Query("select coalesce(sum(e.amount),0) from LedgerEntry e where e.assetId = :assetId")
  long sumAmountByAsset(int assetId);
  ```

## Derived query method cheats (name → query)
| Method name | Generated where-clause |
|-------------|------------------------|
| `findByEmail(String)` | `WHERE email = ?` |
| `findByAccountIdAndAssetId(...)` | `WHERE account_id = ? AND asset_id = ?` |
| `existsByEmail(String)` | `SELECT EXISTS(... email = ?)` |
| `findByStatusOrderByCreatedAtDesc(...)` | `WHERE status = ? ORDER BY created_at DESC` |
| `countByAssetId(int)` | `SELECT count(*) ... asset_id = ?` |

For anything these can't express, use `@Query("JPQL ...")` (JPQL talks in *entities/fields*, not
tables/columns).

## Verifying your entities (the safety net)
Because `ddl-auto: validate`, just boot the app:
```bash
./mvnw spring-boot:run
```
- App **starts** → every entity matches its table. ✅
- App **fails** with `SchemaManagementException` / "missing column / wrong type" → an entity and
  its table disagree. Read the message; fix the entity (or add a migration). This is the safety
  net working — it caught a mismatch before runtime.

## Common mapping mistakes
- Forgetting `@NoArgsConstructor` → Hibernate can't instantiate the entity.
- Using `@Data` on an entity → equals/hashCode/toString traps (concept 14).
- `Long` vs `Integer` mismatch with the column type (`assets.id` is `int` → `Integer`).
- `@Enumerated(EnumType.ORDINAL)` (the default!) → use `STRING` explicitly.
- Mapping `timestamptz` to `LocalDateTime` (no zone) → use `Instant`/`OffsetDateTime`.

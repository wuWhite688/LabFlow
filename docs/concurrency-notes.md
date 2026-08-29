# Concurrency Notes

## Reservation state changes and work-order creation

LabFlow has two business paths that touch both equipment rows and reservation rows:

- reservation state changes such as approval, cancellation, and completion
- fault work-order creation, which may cancel open reservations for the same equipment

The original implementation acquired the same database row locks in opposite orders:

- reservation state changes: `Reservation -> Equipment`
- work-order creation: `Equipment -> Reservation`

When the two paths run concurrently against the same equipment and related reservation rows, this creates an ABBA lock-ordering risk at the database layer. InnoDB can detect the deadlock and roll back one transaction, but the request still fails and the ordering is avoidable.

### Fix

All relevant state-changing paths now follow one lock hierarchy:

`Equipment -> Reservation`

Reservation approval, cancellation, and completion first resolve the equipment id, acquire the equipment row with a pessimistic write lock, and then acquire the reservation row with a pessimistic write lock. Work-order creation already follows the same cross-entity order. Reservation expiry (Rabbit listener and the DB compensation scan) uses the same order inside a `REQUIRES_NEW` transaction per reservation, so it cannot form an ABBA cycle with approval.

When a work order locks multiple reservations for one equipment item, the query also sorts by reservation primary key before applying the pessimistic lock:

`Equipment -> Reservation(id ascending)`

This keeps both cross-entity and same-entity multi-row lock acquisition deterministic.

### Regression protection

A Spring Boot integration test installs a Hibernate `StatementInspector` and captures the SQL emitted by the real application path. The test asserts that:

1. reservation approval acquires the `Equipment` `FOR UPDATE` lock before the `Reservation` `FOR UPDATE` lock;
2. the work-order path's multi-reservation `FOR UPDATE` query contains an `ORDER BY` on the reservation id;
3. the expiry compensation job also acquires `Equipment` before `Reservation`.

`ReservationAbbaDeadlockConcurrencyTest` runs the live paths at a `CyclicBarrier`:

- approve vs student work-order create on the same equipment
- complete of the *higher* reservation id vs privileged work-order batch lock (`ORDER BY id`)
- cancel of the *higher* id vs teacher work-order batch lock

`Future.get(10, SECONDS)` plus `shutdownNow` turns a deadlock into a failed test instead of a hung worker. If lock order reverts to Reservation-then-Equipment, or the batch query drops `ORDER BY id` while the other thread touches the high id first, InnoDB can deadlock and this suite times out.

H2 can verify: both threads return, no 500, and (with `@Version`) no silent lost update. H2 cannot verify MySQL deadlock detection — its lock manager may complete a reversed order without deadlock. A pass on CI H2 is necessary but not sufficient for InnoDB; the SQL inspector remains the CI-stable ORDER BY check.

### Scope

This change removes the identified `Equipment <-> Reservation` lock-order inversion. It does not claim that every possible database deadlock is impossible; future code that introduces new multi-entity locking should preserve the same global ordering rule.

## Refresh-token families

Auth writers (refresh, logout, family-wide reuse revocation) are a separate lock tree from reservations. They must not acquire equipment or reservation row locks.

Order inside the auth tree:

`refresh_token_families -> presented refresh_tokens row`

1. Unlocked hash lookup only to learn `family_id`.
2. `SELECT family FOR UPDATE` (and family `@Version` so H2 serializes lost updates).
3. `SELECT presented token FOR UPDATE`.
4. Sibling revocation is `UPDATE refresh_tokens SET revoked_at=… WHERE family_id=? AND revoked_at IS NULL` while already holding the family lock. Do not `FOR UPDATE` the current successor in arbitrary id order.

Token-first locking deadlocks with reuse: refresh of B holds B then waits for the family; replay of rotated A holds A then the family then tries to update B.

H2 `FOR UPDATE` does not serialize; family `@Version` is the CI-stable backstop, same idea as `Reservation.version`. Concurrent double-refresh of still-active A is treated as reuse: at most one HTTP 200, then the family is compromised and **0** active refresh rows remain. The winner's access JWT still works until TTL.

`RefreshTokenMysqlConcurrencyTest` repeats the concurrent + replay cases against MySQL 8.4 via Testcontainers when Docker is present. GitHub Actions must actually run that class (not skip it).

## Redis lock, Rabbit expiry, and compensation

Three test layers:

1. **Default CI (H2 + local lock + local expiry).** `./mvnw verify` on GitHub Actions. Covers Redis lock *protocol* with in-memory `Commands` (timeout, retry, compare-and-delete Lua, unlock errors must not hide a successful booking), local same-equipment serialization, per-queue TTL / `x-expires` (no shared FIFO), listener duplicate consume, late expiry on APPROVED/CANCELLED/COMPLETED, compensation idempotence, per-row `REQUIRES_NEW` isolation, and expire vs approve/cancel races.
2. **Real RabbitMQ.** `RabbitReservationExpiryOrderingIntegrationTest` skips unless `127.0.0.1:5672` is open. That is the only HOL proof against a broker. No Testcontainers.
3. **Real Redis.** Not required in CI. Overlapping creates on default CI use the local lock plus equipment `PESSIMISTIC_WRITE`. Redis wait-timeout returns 409 and does **not** skip the database; writers that enter `create` still take the row lock.

`PESSIMISTIC_WRITE` remains the correctness backstop. Compensation lock order stays `Equipment → Reservation` (SQL inspector). A listener crash is recovered by the next compensation scan.

### Interview summary

> I found that reservation state changes and work-order creation acquired the same database locks in opposite orders, creating an ABBA deadlock risk. I unified the order to `Equipment -> Reservation`, sorted multi-row reservation locks by primary key, and added SQL-level integration assertions so the ordering cannot regress silently.

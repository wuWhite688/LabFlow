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

This avoids relying on a timing-sensitive concurrent deadlock reproduction in CI while still protecting the lock-order invariant at the SQL layer.

### Scope

This change removes the identified `Equipment <-> Reservation` lock-order inversion. It does not claim that every possible database deadlock is impossible; future code that introduces new multi-entity locking should preserve the same global ordering rule.

### Interview summary

> I found that reservation state changes and work-order creation acquired the same database locks in opposite orders, creating an ABBA deadlock risk. I unified the order to `Equipment -> Reservation`, sorted multi-row reservation locks by primary key, and added SQL-level integration assertions so the ordering cannot regress silently.

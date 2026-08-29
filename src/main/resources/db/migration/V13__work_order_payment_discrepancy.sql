-- Reconciliation reuses the fault work-order table, so work orders now carry a
-- category. Everything that existed before is a real equipment fault.
alter table fault_work_orders add column category varchar(30) not null default 'FAULT';

-- Stable identity of the discrepancy a reconciliation ticket was raised for
-- (bill date + order number + discrepancy type). Re-running reconciliation for
-- the same day must not pile up duplicate tickets, and the unique index is what
-- guarantees that rather than a read-then-write check.
alter table fault_work_orders add column discrepancy_key varchar(160) null;

create unique index uk_work_order_discrepancy_key
    on fault_work_orders(discrepancy_key);

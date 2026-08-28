-- Short payment window for AWAITING_PAYMENT reservations. Null for reservations
-- that never enter the paid flow (free equipment, or still pending approval).
alter table equipment_reservations add column payment_deadline timestamp(6) null;

create index idx_reservation_payment_deadline
    on equipment_reservations(status, payment_deadline);

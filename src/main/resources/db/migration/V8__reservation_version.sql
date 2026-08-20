-- Optimistic lock for Reservation. Existing rows start at 0 so the first
-- concurrent writer increments to 1 and the loser fails WHERE version = 0.
alter table equipment_reservations add column version bigint not null default 0;

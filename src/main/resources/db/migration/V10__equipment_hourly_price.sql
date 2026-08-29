-- Hourly price in cents. 0 means the equipment is free to reserve, which keeps
-- every pre-existing row (and the whole pre-payment reservation flow) unchanged:
-- approval of a free reservation still goes straight to APPROVED.
alter table equipment add column hourly_price_cents bigint not null default 0;

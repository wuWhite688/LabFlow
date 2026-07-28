alter table equipment_reservations
    add column expires_at timestamp(6) not null default current_timestamp(6);

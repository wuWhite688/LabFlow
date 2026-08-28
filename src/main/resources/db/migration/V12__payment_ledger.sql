-- One payment order per reservation that costs money.
create table payment_orders (
    id bigint auto_increment primary key,
    order_no varchar(64) not null unique,
    reservation_id bigint not null unique,
    equipment_id bigint not null,
    payer_id bigint not null,
    amount_cents bigint not null,
    paid_cents bigint not null default 0,
    refunded_cents bigint not null default 0,
    status varchar(30) not null,
    version bigint not null default 0,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_payment_order_reservation
        foreign key (reservation_id) references equipment_reservations(id)
);

-- Local ledger: exactly one row per accepted channel callback.
create table payment_transactions (
    id bigint auto_increment primary key,
    order_no varchar(64) not null,
    idempotency_key varchar(120) not null,
    type varchar(20) not null,
    amount_cents bigint not null,
    channel_txn_id varchar(80) not null,
    channel_status varchar(30) not null,
    occurred_at timestamp(6) not null,
    created_at timestamp(6) not null
);

create index idx_payment_transaction_order on payment_transactions(order_no);
create index idx_payment_transaction_occurred on payment_transactions(occurred_at);

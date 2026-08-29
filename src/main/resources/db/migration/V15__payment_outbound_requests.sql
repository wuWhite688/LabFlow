-- Durable record of every request the platform sends TO the channel.
--
-- Before this existed, an outbound call was fire-and-forget: a failure left the
-- reservation stranded (REFUNDING with no path back), and a repeated call
-- created a second real transaction because nothing tied the two attempts
-- together. Both are fixed by the same row.
create table payment_requests (
    id bigint auto_increment primary key,
    order_no varchar(64) not null,
    idempotency_key varchar(120) not null,
    type varchar(20) not null,
    amount_cents bigint not null,
    status varchar(20) not null,
    attempts int not null default 0,
    last_error varchar(500) null,
    next_attempt_at timestamp(6) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null
);

-- The stable key is what makes a retry a retry rather than a second payment.
-- It is presented to the channel on every attempt, and the unique index stops a
-- duplicate initiation from even becoming a second pending request.
create unique index uk_payment_request_idempotency on payment_requests(idempotency_key);

create index idx_payment_request_dispatch on payment_requests(status, next_attempt_at);

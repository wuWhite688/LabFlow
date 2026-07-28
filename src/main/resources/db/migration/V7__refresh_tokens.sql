create table refresh_tokens (
    id bigint auto_increment primary key,
    user_id bigint not null,
    token_hash varchar(64) not null unique,
    expires_at timestamp(6) not null,
    revoked_at timestamp(6) null,
    created_at timestamp(6) not null,
    constraint fk_refresh_token_user foreign key (user_id) references platform_users(id)
);

create index idx_refresh_token_user on refresh_tokens(user_id);
create index idx_refresh_token_expires on refresh_tokens(expires_at);

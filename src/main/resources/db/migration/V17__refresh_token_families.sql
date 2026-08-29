-- Refresh-token family reuse detection.
-- Each existing token becomes its own family (today every login is an
-- independent session). Already-revoked rows backfill as LOGOUT so a
-- post-deploy replay of a historical rotated hash is not a reuse alarm.
--
-- Intentionally omitted: replaced_by_token_id (family.current_token_id plus
-- token.revoke_reason=ROTATED is enough to detect ancestor reuse) and any
-- purge scheduler (storage cleanup is a follow-up, not this vulnerability).

create table refresh_token_families (
    id bigint auto_increment primary key,
    user_id bigint not null,
    current_token_id bigint null,
    revoked_at timestamp(6) null,
    revoke_reason varchar(32) null,
    version bigint not null default 0,
    created_at timestamp(6) not null,
    constraint fk_refresh_token_family_user
        foreign key (user_id) references platform_users(id)
);

create index idx_refresh_token_family_user on refresh_token_families(user_id);

alter table refresh_tokens add column family_id bigint not null default 0;
alter table refresh_tokens add column revoke_reason varchar(32) null;

insert into refresh_token_families (
    user_id, current_token_id, revoked_at, revoke_reason, version, created_at
)
select
    user_id,
    id,
    revoked_at,
    case when revoked_at is not null then 'LOGOUT' else null end,
    0,
    created_at
from refresh_tokens;

update refresh_tokens
   set family_id = (
        select family.id
          from refresh_token_families family
         where family.current_token_id = refresh_tokens.id
   );

update refresh_token_families
   set current_token_id = null
 where current_token_id in (
        select id from refresh_tokens where revoked_at is not null
   );

alter table refresh_tokens
    add constraint fk_refresh_token_family
        foreign key (family_id) references refresh_token_families(id);

alter table refresh_token_families
    add constraint fk_refresh_token_family_current
        foreign key (current_token_id) references refresh_tokens(id)
        on delete set null;

create index idx_refresh_token_family_id on refresh_tokens(family_id);

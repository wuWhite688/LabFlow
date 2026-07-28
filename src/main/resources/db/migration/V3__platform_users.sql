create table platform_users (
    id bigint auto_increment primary key,
    username varchar(50) not null unique,
    password_hash varchar(100) not null,
    display_name varchar(80) not null,
    role varchar(30) not null,
    enabled boolean not null default true,
    created_at timestamp(6) not null
);

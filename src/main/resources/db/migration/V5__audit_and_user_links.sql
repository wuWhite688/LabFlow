alter table equipment_reservations
    add constraint fk_reservation_requester foreign key (requester_id) references platform_users(id);

alter table fault_work_orders
    add constraint fk_work_order_reporter foreign key (reporter_id) references platform_users(id);

create table operation_logs (
    id bigint auto_increment primary key,
    actor_user_id bigint null,
    actor_username varchar(50) not null,
    actor_role varchar(30) not null,
    action varchar(80) not null,
    target_type varchar(50) not null,
    target_id bigint null,
    details varchar(1000) not null,
    created_at timestamp(6) not null,
    constraint fk_operation_log_actor foreign key (actor_user_id) references platform_users(id)
);

create index idx_operation_log_created_at on operation_logs(created_at);
create index idx_operation_log_target on operation_logs(target_type, target_id);

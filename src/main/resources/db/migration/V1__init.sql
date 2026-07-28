create table equipment (
    id bigint auto_increment primary key,
    code varchar(50) not null unique,
    name varchar(100) not null,
    category varchar(50) not null,
    location varchar(120) not null,
    status varchar(30) not null,
    version bigint not null default 0,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null
);

create table equipment_reservations (
    id bigint auto_increment primary key,
    equipment_id bigint not null,
    requester_id bigint not null,
    requester_name varchar(80) not null,
    purpose varchar(500) not null,
    start_time timestamp(6) not null,
    end_time timestamp(6) not null,
    status varchar(30) not null,
    created_at timestamp(6) not null,
    constraint fk_reservation_equipment foreign key (equipment_id) references equipment(id)
);

create index idx_reservation_conflict
    on equipment_reservations(equipment_id, status, start_time, end_time);

create table fault_work_orders (
    id bigint auto_increment primary key,
    equipment_id bigint not null,
    reporter_id bigint not null,
    reporter_name varchar(80) not null,
    title varchar(120) not null,
    description varchar(1000) not null,
    priority varchar(20) not null,
    status varchar(30) not null,
    assignee_id bigint null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    resolved_at timestamp(6) null,
    constraint fk_work_order_equipment foreign key (equipment_id) references equipment(id)
);

create index idx_work_order_equipment_status
    on fault_work_orders(equipment_id, status);

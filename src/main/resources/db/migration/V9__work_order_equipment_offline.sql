-- Whether this work order requires the equipment to stay offline.
--
-- Non-privileged (student) fault reports deliberately do not take the equipment
-- offline — see WorkOrderService.create. Before this column, sync() re-derived
-- MAINTENANCE from *any* active work order, so the scheduled compensation job
-- silently undid that limit within one scan interval.
alter table fault_work_orders add column equipment_taken_offline boolean not null default false;

-- Preserve pre-migration behaviour for rows that already exist: every open work
-- order kept the equipment offline, so mark them all. Only work orders created
-- after this migration get the role-dependent treatment.
update fault_work_orders
set equipment_taken_offline = true
where status in ('SUBMITTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED');

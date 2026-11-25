create sequence simulation_replica_id as integer;
alter table simulation_job add column replica_id integer;

-- Drain replicas that predate replica ownership before applying this migration.
with abandoned as (
    update simulation_job
       set status = 'FAILED', finished_at = clock_timestamp(),
           error = 'Simulation interrupted before replica ownership was enabled.'
     where status in ('PENDING', 'RUNNING')
    returning id, owner
)
insert into audit_log (entity_type, entity_id, actor, action)
select 'simulation', id, owner, 'FAILED' from abandoned;

alter table simulation_job add constraint simulation_job_active_replica
    check (status not in ('PENDING', 'RUNNING') or replica_id is not null);

create index simulation_job_active_replica_idx on simulation_job (tenant, replica_id)
    where status in ('PENDING', 'RUNNING');

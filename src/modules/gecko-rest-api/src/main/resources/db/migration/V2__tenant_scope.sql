alter table circuit_meta add column tenant text;
alter table simulation_job add column tenant text;
create index circuit_meta_tenant_idx on circuit_meta (tenant);
create index simulation_job_tenant_status_idx on simulation_job (tenant, status);

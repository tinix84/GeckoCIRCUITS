create table embedded_simulation_lease (
    id uuid primary key,
    tenant text not null,
    replica_id integer not null
);
create index embedded_simulation_tenant_idx on embedded_simulation_lease (tenant);
create index circuit_meta_uploaded_id_idx on circuit_meta (uploaded_at, id);
create index circuit_meta_tenant_uploaded_id_idx on circuit_meta (tenant, uploaded_at, id);

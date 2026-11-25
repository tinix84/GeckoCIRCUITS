create table circuit_meta (
    id text primary key,
    name text not null,
    owner text,
    uploaded_at timestamptz not null,
    sha256 varchar(64) not null,
    payload bytea not null
);

create table simulation_job (
    id text primary key,
    circuit_id text,
    owner text,
    status text not null,
    submitted_at timestamptz not null,
    finished_at timestamptz,
    error text
);
create index simulation_job_owner_idx on simulation_job (owner);

create table audit_log (
    id bigint generated always as identity primary key,
    event_time timestamptz not null default current_timestamp,
    entity_type text not null,
    entity_id text not null,
    actor text,
    action text not null
);

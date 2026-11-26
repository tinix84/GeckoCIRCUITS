package gecko.rest.persistence;

import gecko.rest.config.SimulationJobLimits;
import gecko.rest.model.SimulationResponse.SimulationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class PostgresPersistence {
    private static final List<SimulationStatus> ACTIVE = List.of(SimulationStatus.PENDING, SimulationStatus.RUNNING);

    private static final String LIVE_REPLICAS = """
            with live_replicas as materialized (
                select objid::integer as id
                  from pg_locks
                 where locktype = 'advisory' and objsubid = 2 and mode = 'ExclusiveLock' and granted
                   and database = (select oid from pg_database where datname = current_database())
                   and classid = cast(? as oid)
            )
            """;

    private final CircuitMetadataRepository circuits;
    private final SimulationMetadataRepository jobs;
    private final JdbcTemplate jdbc;

    private final PostgresReplica replica;

    PostgresPersistence(CircuitMetadataRepository circuits, SimulationMetadataRepository jobs, JdbcTemplate jdbc,
                        PostgresReplica replica) {
        this.circuits = circuits;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.replica = replica;
    }

    @Transactional(readOnly = true)
    public Optional<CircuitMetadata> circuit(String id, String tenant) {
        return tenant == null ? circuits.findById(id) : circuits.findByIdAndTenant(id, tenant);
    }

    @Transactional(readOnly = true)
    public List<CircuitMetadata> circuits(String tenant) {
        return tenant == null ? circuits.findAll() : circuits.findByTenant(tenant);
    }

    public record CircuitPage(List<CircuitMetadata> rows, int total) { }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public CircuitPage circuitPage(String tenant, int page, int size, String field, boolean descending) {
        long total = tenant == null ? circuits.count() : circuits.countByTenant(tenant);
        if ((long) page * size >= total) return new CircuitPage(List.of(), Math.toIntExact(total));
        var direction = descending ? Sort.Direction.DESC : Sort.Direction.ASC;
        var sort = field.equals("createdAt") ? Sort.by(direction, "uploadedAt", "id") : Sort.by(direction, "id");
        var request = PageRequest.of(page, size, sort);
        var rows = tenant == null ? circuits.findAll(request).getContent() : circuits.findByTenant(tenant, request);
        return new CircuitPage(rows, Math.toIntExact(total));
    }

    @Transactional
    public void addCircuit(CircuitMetadata circuit, int limit) {
        if (circuit.tenant() != null) {
            lockTenant("circuit", circuit.tenant());
            SimulationJobLimits.requireTenantCapacity(circuitCount(circuit.tenant()), 1, limit);
        }
        circuits.save(circuit);
        audit("circuit", circuit.id(), circuit.owner(), "CREATED");
    }

    @Transactional
    public boolean updateCircuit(String id, String actor, String tenant, UnaryOperator<CircuitMetadata> update) {
        var circuit = tenant == null ? circuits.lockById(id) : circuits.lockByIdAndTenant(id, tenant);
        if (circuit.isEmpty()) return false;
        circuits.save(update.apply(circuit.get()));
        audit("circuit", id, actor, "UPDATED");
        return true;
    }

    @Transactional
    public boolean deleteCircuit(String id, String actor, String tenant) {
        var circuit = tenant == null ? circuits.lockById(id) : circuits.lockByIdAndTenant(id, tenant);
        if (circuit.isEmpty()) return false;
        circuits.delete(circuit.get());
        audit("circuit", id, actor, "DELETED");
        return true;
    }

    @Transactional
    public Optional<SimulationMetadata> simulation(String id, String tenant) {
        recoverAbandonedJobs(tenant, id);
        return tenant == null ? jobs.findById(id) : jobs.findByIdAndTenant(id, tenant);
    }

    @Transactional
    public List<SimulationMetadata> simulations(String tenant) {
        recoverAbandonedJobs(tenant, null);
        return tenant == null ? jobs.findAll() : jobs.findByTenant(tenant);
    }

    public boolean checkOwnership() { return replica.checkOwnership(); }

    public boolean hasOwnership() { return replica.hasOwnership(); }

    public void relinquishOwnership() { replica.relinquishOwnership(); }

    @Transactional
    public boolean saveSimulation(SimulationMetadata simulation) {
        if (!replica.hasOwnership()) return false;
        var stored = jobs.lockActiveJob(simulation.id(), replica.id(), ACTIVE);
        if (stored.isEmpty() || !replica.checkOwnership()) return false;
        stored.get().recordTransition(simulation);
        audit("simulation", simulation.id(), simulation.owner(), simulation.status());
        jobs.flush();
        if (!replica.checkOwnership()) {
            throw new DataAccessResourceFailureException("Simulation ownership lost before committing its status.");
        }
        return true;
    }

    @Transactional
    public void admitSimulations(List<SimulationMetadata> simulations, String tenant, int limit) {
        long active = 0;
        if (tenant != null) {
            lockTenant("simulation", tenant);
            active = activeJobs(tenant);
        }
        int replicaId = replica.liveId();
        if (tenant != null) SimulationJobLimits.requireTenantCapacity(active, simulations.size(), limit);
        simulations.forEach(job -> job.assignReplica(replicaId));
        jobs.saveAllAndFlush(simulations);
        simulations.forEach(simulation -> audit("simulation", simulation.id(), simulation.owner(), simulation.status()));
        replica.liveId();
    }

    @Transactional
    public void rejectSimulation(SimulationMetadata simulation) {
        if (replica.checkOwnership()) {
            jobs.deleteByIdAndReplicaId(simulation.id(), replica.id());
            audit("simulation", simulation.id(), simulation.owner(), "REJECTED");
        }
    }

    public long circuitCount(String tenant) {
        return circuits.countByTenant(tenant);
    }

    @Transactional
    public long activeJobs(String tenant) {
        recoverAbandonedJobs(tenant, null);
        long embedded = jdbc.queryForObject(LIVE_REPLICAS + """
                , expired as (
                    delete from embedded_simulation_lease lease where lease.tenant = ?
                      and not exists (select 1 from live_replicas live where live.id = lease.replica_id)
                )
                select count(*) from embedded_simulation_lease lease
                 join live_replicas live on lease.replica_id = live.id where lease.tenant = ?
                """, Long.class, replica.namespace(), tenant, tenant);
        return embedded + jobs.countByTenantAndStatusIn(tenant, ACTIVE);
    }

    @Transactional
    public void admitEmbeddedSimulation(UUID id, String tenant, int limit) {
        lockTenant("simulation", tenant);
        SimulationJobLimits.requireTenantCapacity(activeJobs(tenant), 1, limit);
        jdbc.update("insert into embedded_simulation_lease (id, tenant, replica_id) values (?, ?, ?)",
                id, tenant, replica.liveId());
        replica.liveId();
    }

    @Transactional
    public void releaseEmbeddedSimulation(UUID id) {
        jdbc.update("delete from embedded_simulation_lease where id = ?", id);
    }

    private void recoverAbandonedJobs(String tenant, String id) {
        // Replica IDs are unique in this schema. A replica takes its lock before it can create a job.
        jdbc.update(LIVE_REPLICAS + """
                , abandoned as materialized (
                    select job.id from simulation_job job
                     where job.status in ('PENDING', 'RUNNING')
                       and (cast(? as text) is null or job.tenant = ?)
                       and (cast(? as text) is null or job.id = ?)
                       and not exists (select 1 from live_replicas live where live.id = job.replica_id)
                     order by job.id for update of job
                ), recovered as (
                    update simulation_job job
                       set status = 'FAILED', finished_at = clock_timestamp(),
                           error = 'Simulation interrupted because its API replica stopped.'
                      from abandoned where job.id = abandoned.id
                    returning job.id, job.owner
                )
                insert into audit_log (entity_type, entity_id, actor, action)
                select 'simulation', id, owner, 'FAILED' from recovered
                """, replica.namespace(), tenant, tenant, id, id);
    }

    private void lockTenant(String resource, String tenant) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", resource + ":" + tenant);
    }

    private void audit(String type, String id, String actor, String action) {
        jdbc.update("insert into audit_log (entity_type, entity_id, actor, action) values (?, ?, ?, ?)",
                type, id, actor, action);
    }
}

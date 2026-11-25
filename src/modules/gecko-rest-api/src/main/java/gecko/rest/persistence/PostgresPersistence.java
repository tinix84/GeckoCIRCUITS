package gecko.rest.persistence;

import gecko.rest.config.SimulationJobLimits;
import gecko.rest.model.SimulationResponse.SimulationStatus;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class PostgresPersistence {
    private static final List<SimulationStatus> ACTIVE = List.of(SimulationStatus.PENDING, SimulationStatus.RUNNING);

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

    @Transactional
    public boolean saveSimulation(SimulationMetadata simulation) {
        if (!replica.checkOwnership() || jobs.updateActiveJob(simulation.id(), replica.id(),
                simulation.simulationStatus(), simulation.finishedAt(), simulation.error(), ACTIVE) == 0) {
            return false;
        }
        audit("simulation", simulation.id(), simulation.owner(), simulation.status());
        return true;
    }

    @Transactional
    public void admitSimulations(List<SimulationMetadata> simulations, String tenant, int limit) {
        int replicaId = replica.liveId();
        if (tenant != null) {
            lockTenant("simulation", tenant);
            SimulationJobLimits.requireTenantCapacity(activeJobs(tenant), simulations.size(), limit);
        }
        simulations.forEach(job -> job.assignReplica(replicaId));
        jobs.saveAll(simulations);
        simulations.forEach(simulation -> audit("simulation", simulation.id(), simulation.owner(), simulation.status()));
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
        return jobs.countByTenantAndStatusIn(tenant, ACTIVE);
    }

    private void recoverAbandonedJobs(String tenant, String id) {
        // Replica IDs are unique in this schema. A replica takes its lock before it can create a job.
        jdbc.update("""
                with live_replicas as materialized (
                    select objid::integer as id
                      from pg_locks
                     where locktype = 'advisory' and objsubid = 2 and mode = 'ExclusiveLock' and granted
                       and database = (select oid from pg_database where datname = current_database())
                       and classid = cast(? as oid)
                ), abandoned as materialized (
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

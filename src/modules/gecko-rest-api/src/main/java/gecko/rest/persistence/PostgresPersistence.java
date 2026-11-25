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
    private final CircuitMetadataRepository circuits;
    private final SimulationMetadataRepository jobs;
    private final JdbcTemplate jdbc;

    PostgresPersistence(CircuitMetadataRepository circuits, SimulationMetadataRepository jobs, JdbcTemplate jdbc) {
        this.circuits = circuits;
        this.jobs = jobs;
        this.jdbc = jdbc;
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

    @Transactional(readOnly = true)
    public Optional<SimulationMetadata> simulation(String id, String tenant) {
        return tenant == null ? jobs.findById(id) : jobs.findByIdAndTenant(id, tenant);
    }

    @Transactional(readOnly = true)
    public List<SimulationMetadata> simulations(String tenant) {
        return tenant == null ? jobs.findAll() : jobs.findByTenant(tenant);
    }

    @Transactional
    public void saveSimulation(SimulationMetadata simulation) {
        jobs.save(simulation);
        audit("simulation", simulation.id(), simulation.owner(), simulation.status());
    }

    @Transactional
    public void admitSimulations(List<SimulationMetadata> simulations, String tenant, int limit) {
        if (tenant != null) {
            lockTenant("simulation", tenant);
            SimulationJobLimits.requireTenantCapacity(activeJobs(tenant), simulations.size(), limit);
        }
        jobs.saveAll(simulations);
        simulations.forEach(simulation -> audit("simulation", simulation.id(), simulation.owner(), simulation.status()));
    }

    @Transactional
    public void rejectSimulation(SimulationMetadata simulation) {
        jobs.deleteById(simulation.id());
        audit("simulation", simulation.id(), simulation.owner(), "REJECTED");
    }

    public long circuitCount(String tenant) {
        return circuits.countByTenant(tenant);
    }

    public long activeJobs(String tenant) {
        return jobs.countByTenantAndStatusIn(tenant, List.of(SimulationStatus.PENDING, SimulationStatus.RUNNING));
    }

    private void lockTenant(String resource, String tenant) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", resource + ":" + tenant);
    }

    private void audit(String type, String id, String actor, String action) {
        jdbc.update("insert into audit_log (entity_type, entity_id, actor, action) values (?, ?, ?, ?)",
                type, id, actor, action);
    }
}

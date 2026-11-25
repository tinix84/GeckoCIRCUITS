package gecko.rest.persistence;

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
    public Optional<CircuitMetadata> circuit(String id) {
        return circuits.findById(id);
    }

    @Transactional(readOnly = true)
    public List<CircuitMetadata> circuits() {
        return circuits.findAll();
    }

    @Transactional
    public void addCircuit(CircuitMetadata circuit) {
        circuits.save(circuit);
        audit("circuit", circuit.id(), circuit.owner(), "CREATED");
    }

    @Transactional
    public boolean updateCircuit(String id, String actor, UnaryOperator<CircuitMetadata> update) {
        var circuit = circuits.lockById(id);
        if (circuit.isEmpty()) return false;
        circuits.save(update.apply(circuit.get()));
        audit("circuit", id, actor, "UPDATED");
        return true;
    }

    @Transactional
    public boolean deleteCircuit(String id, String actor) {
        var circuit = circuits.lockById(id);
        if (circuit.isEmpty()) return false;
        circuits.delete(circuit.get());
        audit("circuit", id, actor, "DELETED");
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<SimulationMetadata> simulation(String id) {
        return jobs.findById(id);
    }

    @Transactional(readOnly = true)
    public List<SimulationMetadata> simulations() {
        return jobs.findAll();
    }

    @Transactional
    public void saveSimulation(SimulationMetadata simulation) {
        jobs.save(simulation);
        audit("simulation", simulation.id(), simulation.owner(), simulation.status());
    }

    @Transactional
    public void saveSimulations(List<SimulationMetadata> simulations) {
        jobs.saveAll(simulations);
        simulations.forEach(simulation -> audit("simulation", simulation.id(), simulation.owner(), simulation.status()));
    }

    @Transactional
    public void rejectSimulation(SimulationMetadata simulation) {
        jobs.deleteById(simulation.id());
        audit("simulation", simulation.id(), simulation.owner(), "REJECTED");
    }

    private void audit(String type, String id, String actor, String action) {
        jdbc.update("insert into audit_log (entity_type, entity_id, actor, action) values (?, ?, ?, ?)",
                type, id, actor, action);
    }
}

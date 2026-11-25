package gecko.rest.persistence;

import gecko.rest.model.SimulationResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "simulation_job")
public class SimulationMetadata {
    @Id
    @Column(columnDefinition = "text")
    private String id;
    @Column(columnDefinition = "text")
    private String circuitId;
    @Column(columnDefinition = "text")
    private String owner;
    @Column(columnDefinition = "text")
    private String tenant;
    private Integer replicaId;
    @Column(columnDefinition = "text", nullable = false)
    @Enumerated(EnumType.STRING)
    private SimulationResponse.SimulationStatus status;
    @Column(nullable = false)
    private Instant submittedAt;
    private Instant finishedAt;
    @Column(columnDefinition = "text")
    private String error;

    protected SimulationMetadata() { }

    public SimulationMetadata(String circuitId, String owner, String tenant, SimulationResponse response) {
        id = response.getSimulationId();
        this.circuitId = circuitId;
        this.owner = owner;
        this.tenant = tenant;
        submittedAt = response.getStartTime();
        status = response.getStatus();
        finishedAt = response.getEndTime();
        error = response.getErrorMessage();
    }

    public String id() { return id; }
    public String owner() { return owner; }
    public String tenant() { return tenant; }
    public String status() { return status.name(); }

    SimulationResponse.SimulationStatus simulationStatus() { return status; }
    Instant finishedAt() { return finishedAt; }
    String error() { return error; }

    void assignReplica(int replica) { replicaId = replica; }

    public SimulationResponse response() {
        var response = new SimulationResponse(id);
        response.setStartTime(submittedAt);
        response.setEndTime(finishedAt);
        response.setStatus(status);
        response.setErrorMessage(error);
        return response;
    }
}

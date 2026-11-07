package gecko.rest.model;

import jakarta.validation.constraints.Positive;
import java.util.Map;

/**
 * Request DTO for circuit simulation submissions.
 * Contains a circuit source and optional simulation settings.
 * Stored sources supply omitted settings independently.
 * Path submissions require a simulation time and a time step.
 * A null optional setting has the same meaning as an omitted setting.
 */
public class SimulationRequest {

    private String circuitFile;
    private String circuitId;

    @Positive(message = "Simulation time must be positive")
    private Double simulationTime;

    @Positive(message = "Time step must be positive")
    private Double timeStep;

    private Map<String, Double> parameters;

    private String solverType;  // Optional. Inherits the captured solver.

    // Constructors
    public SimulationRequest() {
    }

    public SimulationRequest(String circuitFile, Double simulationTime, Double timeStep) {
        this.circuitFile = circuitFile;
        this.simulationTime = simulationTime;
        this.timeStep = timeStep;
    }

    // Getters and setters
    /**
     * Returns the explicit registry source selection.
     *
     * @return the exact registry ID, or null when the legacy source field is selected.
     */
    public String getCircuitId() { return circuitId; }
    /**
     * Selects an explicit registry ID for validation during admission.
     * Select this field or circuitFile, but not both.
     *
     * @param circuitId the exact live registry ID, or null to omit this selection.
     */
    public void setCircuitId(String circuitId) { this.circuitId = circuitId; }

    public String getCircuitFile() {
        return circuitFile;
    }

    public void setCircuitFile(String circuitFile) {
        this.circuitFile = circuitFile;
    }

    public Double getSimulationTime() {
        return simulationTime;
    }

    public void setSimulationTime(Double simulationTime) {
        this.simulationTime = simulationTime;
    }

    public Double getTimeStep() {
        return timeStep;
    }

    public void setTimeStep(Double timeStep) {
        this.timeStep = timeStep;
    }

    public Map<String, Double> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Double> parameters) {
        this.parameters = parameters;
    }

    public String getSolverType() {
        return solverType;
    }

    public void setSolverType(String solverType) {
        this.solverType = solverType;
    }

    @Override
    public String toString() {
        return "SimulationRequest{" +
                "circuitFile='" + circuitFile + '\'' +
                ", simulationTime=" + simulationTime +
                ", timeStep=" + timeStep +
                ", solverType='" + solverType + '\'' +
                ", parameters=" + parameters +
                '}';
    }
}

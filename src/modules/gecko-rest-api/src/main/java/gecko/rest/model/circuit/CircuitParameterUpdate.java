package gecko.rest.model.circuit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Partial update of solver settings and numeric component parameters.
 * All fields are optional - only provided fields are updated.
 */
@Schema(description = "Partial update of solver settings and numeric component parameters")
public class CircuitParameterUpdate {

    @Schema(description = "Simulation duration in seconds", example = "0.05")
    private Double simulationDuration;

    @Schema(description = "Time step in seconds", example = "5e-7")
    private Double timeStep;

    @Schema(
        description = "Solver type: backward-euler, trapezoidal, gear-shichman",
        example = "trapezoidal"
    )
    private String solverType;

    @Schema(description = "Numeric component parameters by component name and field name",
            example = "{\"L.1.inductance\":0.0006}")
    private Map<String, Double> componentParameters;

    public CircuitParameterUpdate() {
    }

    public CircuitParameterUpdate(Double simulationDuration, Double timeStep, String solverType) {
        this.simulationDuration = simulationDuration;
        this.timeStep = timeStep;
        this.solverType = solverType;
    }

    public Double getSimulationDuration() {
        return simulationDuration;
    }

    public void setSimulationDuration(Double simulationDuration) {
        this.simulationDuration = simulationDuration;
    }

    public Double getTimeStep() {
        return timeStep;
    }

    public void setTimeStep(Double timeStep) {
        this.timeStep = timeStep;
    }

    public String getSolverType() {
        return solverType;
    }

    public void setSolverType(String solverType) {
        this.solverType = solverType;
    }

    public Map<String, Double> getComponentParameters() {
        return componentParameters;
    }

    public void setComponentParameters(Map<String, Double> componentParameters) {
        this.componentParameters = componentParameters;
    }
}

package gecko.rest.model.analysis;

import gecko.core.simulation.SteadyStateAnalyzer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Map;

/**
 * Request DTO for steady-state analysis.
 *
 * <p>The analyser runs a transient simulation for up to {@code maxPeriods} switching
 * periods and detects when the circuit has reached a periodic steady state
 * (i.e. signal values repeat within {@code tolerance}).</p>
 */
@Schema(description = "Steady-state analysis request")
public class SteadyStateRequest {

    @Schema(description = "Path to the .ipes circuit file", example = "circuits/buck_converter.ipes", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "circuitFile cannot be blank")
    private String circuitFile;

    @Schema(description = "Simulation time step in seconds", example = "1e-7", requiredMode = Schema.RequiredMode.REQUIRED)
    @Positive(message = "timeStep must be positive")
    private double timeStep;

    @Schema(description = "Switching / excitation period in seconds", example = "1e-4", requiredMode = Schema.RequiredMode.REQUIRED)
    @Positive(message = "period must be positive")
    private double period;

    @Schema(description = "Maximum number of periods to simulate before giving up",
            example = "200", defaultValue = "200")
    private int maxPeriods = SteadyStateAnalyzer.DEFAULT_MAX_PERIODS;

    @Schema(description = "Convergence tolerance (max relative change across all signals)",
            example = "1e-3", defaultValue = "1e-3")
    @Positive(message = "tolerance must be positive")
    private double tolerance = SteadyStateAnalyzer.DEFAULT_TOLERANCE;

    @Schema(description = "Integration method: SOLVER_BE, SOLVER_TRZ, or SOLVER_GS",
            example = "SOLVER_BE")
    private String solverType;

    @Schema(description = "Optional component parameter overrides (name → value)",
            example = "{\"R_load\": 10.0}")
    private Map<String, Double> parameters;

    // Getters and setters

    public String getCircuitFile() { return circuitFile; }
    public void setCircuitFile(String circuitFile) { this.circuitFile = circuitFile; }

    public double getTimeStep() { return timeStep; }
    public void setTimeStep(double timeStep) { this.timeStep = timeStep; }

    public double getPeriod() { return period; }
    public void setPeriod(double period) { this.period = period; }

    public int getMaxPeriods() { return maxPeriods; }
    public void setMaxPeriods(int maxPeriods) { this.maxPeriods = maxPeriods; }

    public double getTolerance() { return tolerance; }
    public void setTolerance(double tolerance) { this.tolerance = tolerance; }

    public String getSolverType() { return solverType; }
    public void setSolverType(String solverType) { this.solverType = solverType; }

    public Map<String, Double> getParameters() { return parameters; }
    public void setParameters(Map<String, Double> parameters) { this.parameters = parameters; }

    @Override
    public String toString() {
        return "SteadyStateRequest{"
                + "circuitFile='" + circuitFile + '\''
                + ", timeStep=" + timeStep
                + ", period=" + period
                + ", maxPeriods=" + maxPeriods
                + ", tolerance=" + tolerance
                + ", solverType='" + solverType + '\''
                + '}';
    }
}

package gecko.rest.model.analysis;

import gecko.core.simulation.SteadyStateResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response DTO for steady-state analysis.
 *
 * <p>When {@link #isConverged()} is {@code true} the waveforms in {@link #getSignals()}
 * represent one complete switching period at steady state.  When
 * {@link #isConverged()} is {@code false} (status {@code MAX_PERIODS_REACHED}) the
 * waveforms show the last simulated period, which is the best estimate available.</p>
 */
@Schema(description = "Steady-state analysis response")
public class SteadyStateResponse {

    @Schema(description = "Whether the simulation converged to a steady state")
    private boolean converged;

    @Schema(description = "Analysis status: CONVERGED, MAX_PERIODS_REACHED, or FAILED")
    private String status;

    @Schema(description = "Number of switching periods simulated")
    private int periodsSimulated;

    @Schema(description = "Final convergence error (max relative change across all signals)")
    private double convergenceError;

    @Schema(description = "Switching period used for the analysis (seconds)")
    private double period;

    @Schema(description = "Time array for one steady-state period, normalised to start at 0 (seconds)")
    private double[] timeArray;

    @Schema(description = "Signal waveforms over one steady-state period (signal name → sample array)")
    private Map<String, double[]> signals;

    @Schema(description = "Error message when status is FAILED")
    private String errorMessage;

    @Schema(description = "Wall-clock execution time in milliseconds")
    private long executionTimeMs;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link SteadyStateResponse} from a {@link SteadyStateResult}.
     *
     * @param result core result object
     * @return populated response DTO
     */
    public static SteadyStateResponse from(SteadyStateResult result) {
        SteadyStateResponse resp = new SteadyStateResponse();
        resp.converged        = result.isConverged();
        resp.status           = result.getStatus().name();
        resp.periodsSimulated = result.getPeriodsSimulated();
        resp.convergenceError = result.getConvergenceError();
        resp.period           = result.getPeriod();
        resp.timeArray        = result.getTimeArray();
        resp.errorMessage     = result.getErrorMessage();
        resp.executionTimeMs  = result.getExecutionTimeMs();

        // Defensive copy of signal map
        Map<String, double[]> copy = new LinkedHashMap<>();
        result.getSignalData().forEach((name, data) ->
                copy.put(name, data != null ? data.clone() : new double[0]));
        resp.signals = Collections.unmodifiableMap(copy);

        return resp;
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public boolean isConverged() { return converged; }
    public void setConverged(boolean converged) { this.converged = converged; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getPeriodsSimulated() { return periodsSimulated; }
    public void setPeriodsSimulated(int periodsSimulated) { this.periodsSimulated = periodsSimulated; }

    public double getConvergenceError() { return convergenceError; }
    public void setConvergenceError(double convergenceError) { this.convergenceError = convergenceError; }

    public double getPeriod() { return period; }
    public void setPeriod(double period) { this.period = period; }

    public double[] getTimeArray() { return timeArray; }
    public void setTimeArray(double[] timeArray) { this.timeArray = timeArray; }

    public Map<String, double[]> getSignals() { return signals; }
    public void setSignals(Map<String, double[]> signals) { this.signals = signals; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
}

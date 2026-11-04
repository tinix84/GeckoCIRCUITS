/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  GeckoCIRCUITS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package gecko.core.simulation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of a steady-state analysis.
 *
 * <p>A steady state is reached when the circuit's periodic signals repeat within
 * a given tolerance, i.e. state variables at the start and end of each switching
 * period are practically identical.</p>
 *
 * <p>On success the result contains:
 * <ul>
 *   <li>whether convergence was achieved</li>
 *   <li>the number of periods simulated before convergence</li>
 *   <li>the final convergence error (max relative change across all signals)</li>
 *   <li>the time array for one period (normalised to start at 0)</li>
 *   <li>signal waveforms for that last (converged) period</li>
 * </ul>
 * </p>
 */
public final class SteadyStateResult {

    /** Status of the steady-state analysis. */
    public enum Status {
        /** Analysis converged within the requested tolerance. */
        CONVERGED,
        /** Maximum number of periods was reached without convergence. */
        MAX_PERIODS_REACHED,
        /** Underlying simulation failed. */
        FAILED
    }

    private final Status status;
    private final boolean converged;
    private final int periodsSimulated;
    private final double convergenceError;
    private final double period;
    private final double[] timeArray;
    private final Map<String, double[]> signalData;
    private final String errorMessage;
    private final long executionTimeMs;

    private SteadyStateResult(Builder b) {
        this.status           = b.status;
        this.converged        = b.status == Status.CONVERGED;
        this.periodsSimulated = b.periodsSimulated;
        this.convergenceError = b.convergenceError;
        this.period           = b.period;
        this.timeArray        = b.timeArray != null ? b.timeArray.clone() : new double[0];
        this.signalData       = Collections.unmodifiableMap(new LinkedHashMap<>(b.signalData));
        this.errorMessage     = b.errorMessage;
        this.executionTimeMs  = b.executionTimeMs;
    }

    /** @return analysis status */
    public Status getStatus() { return status; }

    /** @return true if the simulation converged to a steady state */
    public boolean isConverged() { return converged; }

    /** @return number of switching periods simulated before convergence (or failure) */
    public int getPeriodsSimulated() { return periodsSimulated; }

    /**
     * Returns the maximum relative change in any signal between the start and end
     * of the last period.  A value below the requested tolerance indicates convergence.
     *
     * @return convergence error (dimensionless)
     */
    public double getConvergenceError() { return convergenceError; }

    /** @return switching period used for the analysis (seconds) */
    public double getPeriod() { return period; }

    /**
     * Returns the time array for one steady-state period, normalised to start at 0.
     *
     * @return time values in seconds
     */
    public double[] getTimeArray() { return timeArray.clone(); }

    /**
     * Returns the waveform data for each signal over one steady-state period.
     * The map is ordered by insertion (signal name → sample array).
     *
     * @return signal name → waveform samples
     */
    public Map<String, double[]> getSignalData() { return signalData; }

    /** @return error message when status is {@link Status#FAILED}, otherwise null */
    public String getErrorMessage() { return errorMessage; }

    /** @return wall-clock execution time in milliseconds */
    public long getExecutionTimeMs() { return executionTimeMs; }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /** Creates a failed result with the given error message. */
    public static SteadyStateResult failed(String errorMessage) {
        return new Builder()
                .status(Status.FAILED)
                .errorMessage(errorMessage)
                .build();
    }

    /** Creates a builder for constructing a {@link SteadyStateResult}. */
    public static Builder builder() { return new Builder(); }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /** Builder for {@link SteadyStateResult}. */
    public static final class Builder {
        private Status status = Status.FAILED;
        private int periodsSimulated;
        private double convergenceError = Double.MAX_VALUE;
        private double period;
        private double[] timeArray = new double[0];
        private Map<String, double[]> signalData = new LinkedHashMap<>();
        private String errorMessage;
        private long executionTimeMs;

        private Builder() {}

        public Builder status(Status status)                   { this.status = status; return this; }
        public Builder periodsSimulated(int p)                 { this.periodsSimulated = p; return this; }
        public Builder convergenceError(double e)              { this.convergenceError = e; return this; }
        public Builder period(double period)                   { this.period = period; return this; }
        public Builder timeArray(double[] t)                   { this.timeArray = t; return this; }
        public Builder signalData(Map<String, double[]> data)  { this.signalData = new LinkedHashMap<>(data); return this; }
        public Builder errorMessage(String msg)                { this.errorMessage = msg; return this; }
        public Builder executionTimeMs(long ms)                { this.executionTimeMs = ms; return this; }

        /** Builds the immutable {@link SteadyStateResult}. */
        public SteadyStateResult build() { return new SteadyStateResult(this); }
    }

    @Override
    public String toString() {
        return String.format(
                "SteadyStateResult[status=%s, periods=%d, error=%.3e, period=%.2es, signals=%d, wallClock=%dms]",
                status, periodsSimulated, convergenceError, period, signalData.size(), executionTimeMs);
    }
}

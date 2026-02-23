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
package gecko.core.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a parsed SPICE netlist.
 *
 * <p>Contains the circuit title, component list, and simulation parameters
 * extracted from {@code .tran} control statements.</p>
 */
public final class SpiceNetlist {

    /** Default simulation duration when no .tran statement is present (20 ms). */
    static final double DEFAULT_DURATION = 0.02;

    /** Default time step when no .tran statement is present (1 µs). */
    static final double DEFAULT_TIME_STEP = 1e-6;

    private String title = "";
    private final List<SpiceComponent> components = new ArrayList<>();
    private double simulationDuration = DEFAULT_DURATION;
    private double timeStep = DEFAULT_TIME_STEP;

    SpiceNetlist() { /* package-private – created by SpiceNetlistParser */ }

    /**
     * Returns the netlist title (first line of the .cir file).
     */
    public String getTitle() { return title; }

    void setTitle(String title) { this.title = title != null ? title : ""; }

    /**
     * Returns an unmodifiable view of all parsed circuit components.
     */
    public List<SpiceComponent> getComponents() {
        return Collections.unmodifiableList(components);
    }

    void addComponent(SpiceComponent component) {
        components.add(component);
    }

    /**
     * Returns the simulation end time in seconds (from {@code .tran} statement,
     * or {@value #DEFAULT_DURATION} s if not specified).
     */
    public double getSimulationDuration() { return simulationDuration; }

    void setSimulationDuration(double simulationDuration) {
        this.simulationDuration = simulationDuration;
    }

    /**
     * Returns the simulation time step in seconds (from {@code .tran} statement,
     * or {@value #DEFAULT_TIME_STEP} s if not specified).
     */
    public double getTimeStep() { return timeStep; }

    void setTimeStep(double timeStep) { this.timeStep = timeStep; }

    /**
     * Returns the number of parsed components.
     */
    public int getComponentCount() { return components.size(); }

    @Override
    public String toString() {
        return "SpiceNetlist{title='" + title + "', components=" + components.size()
                + ", duration=" + simulationDuration + ", dt=" + timeStep + '}';
    }
}

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

import gecko.core.circuit.ControlTypCore;
import gecko.core.control.calculators.*;
import gecko.core.io.CircuitModel;

import java.util.*;
import java.util.logging.Logger;

/**
 * Factory that builds a wired control calculator chain from parsed {@code ComponentData}.
 *
 * <p>This class converts the parsed control component data (from .ipes file
 * {@code <ElementCONTROL>} blocks) into a set of {@link AbstractControlCalculatable}
 * instances, wires their signal connections by terminal label matching, and returns
 * a ready-to-execute {@link ControlNetlist} along with a {@link ControlBlockMapping}
 * for domain coupling.</p>
 *
 * <p>Mirrors the GUI-side logic in {@code NetzlisteCONTROL} but without any Swing dependencies.</p>
 */
public class ControlCalculatorFactory {

    private static final Logger LOG = Logger.getLogger(ControlCalculatorFactory.class.getName());

    /** Signal generator sub-type IDs encoded in parameter[0] for typ=4 (SIGNAL) */
    private static final int SIGNAL_SUBTYPE_SINUS = 400;
    private static final int SIGNAL_SUBTYPE_TRIANGLE = 401;
    private static final int SIGNAL_SUBTYPE_RECTANGLE = 404;

    /**
     * Result of building the control calculator chain.
     */
    public static class BuildResult {
        private final ControlNetlist controlNetlist;
        private final ControlBlockMapping mapping;

        public BuildResult(ControlNetlist controlNetlist, ControlBlockMapping mapping) {
            this.controlNetlist = controlNetlist;
            this.mapping = mapping;
        }

        public ControlNetlist getControlNetlist() { return controlNetlist; }
        public ControlBlockMapping getMapping() { return mapping; }
    }

    /**
     * Builds and wires the control calculator chain from parsed control components.
     *
     * @param controlComponents list of control component data from the parser
     * @return a BuildResult containing the wired ControlNetlist and ControlBlockMapping
     */
    public BuildResult build(List<CircuitModel.ComponentData> controlComponents) {
        if (controlComponents == null || controlComponents.isEmpty()) {
            return new BuildResult(ControlNetlist.createEmpty(), new ControlBlockMapping());
        }

        ControlBlockMapping mapping = new ControlBlockMapping();

        // Step 1: Create calculator instances for each component
        List<CalculatorEntry> entries = new ArrayList<>();
        for (CircuitModel.ComponentData comp : controlComponents) {
            AbstractControlCalculatable calc = createCalculator(comp);
            if (calc != null) {
                entries.add(new CalculatorEntry(comp, calc));
            }
        }

        // Step 2: Build output label → (calculator, port) map
        Map<String, SignalPort> outputMap = new LinkedHashMap<>();
        for (CalculatorEntry entry : entries) {
            String[] yLabels = entry.component.getTerminalYLabels();
            for (int port = 0; port < yLabels.length; port++) {
                String label = yLabels[port];
                if (!label.isEmpty()) {
                    outputMap.put(label, new SignalPort(entry.calculator, port));
                }
            }
        }

        // Step 3: Wire input signals by label matching
        for (CalculatorEntry entry : entries) {
            String[] xLabels = entry.component.getTerminalXLabels();
            for (int inputPort = 0; inputPort < xLabels.length; inputPort++) {
                String label = xLabels[inputPort];
                if (!label.isEmpty() && outputMap.containsKey(label)) {
                    SignalPort source = outputMap.get(label);
                    try {
                        entry.calculator.setInputSignal(inputPort, source.calculator, source.portIndex);
                    } catch (Exception e) {
                        LOG.warning("Failed to wire signal '" + label + "' to "
                                + entry.component.getName() + " input " + inputPort + ": " + e.getMessage());
                    }
                }
            }
            // Fill unconnected inputs with zero
            for (int i = 0; i < entry.calculator._inputSignal.length; i++) {
                entry.calculator.checkInputWithoutConnectionAndFill(i);
            }
        }

        // Step 4: Build probe/gate/scope mappings
        for (CalculatorEntry entry : entries) {
            int type = entry.component.getType();
            if (!ControlTypCore.isKnown(type)) continue;

            ControlTypCore controlType = ControlTypCore.fromTypeNumber(type);
            String[] paramStrings = entry.component.getParameterStrings();

            switch (controlType) {
                case VOLT -> {
                    // parameterString format: [nodeLabel1, nodeLabel2, flag]
                    int nodeA = 0;
                    int nodeB = 0;
                    if (paramStrings.length >= 1 && !paramStrings[0].isEmpty()) {
                        nodeA = parseNodeLabel(paramStrings[0]);
                    }
                    if (paramStrings.length >= 2 && !paramStrings[1].isEmpty()) {
                        nodeB = parseNodeLabel(paramStrings[1]);
                    }
                    mapping.addVoltProbe(entry.calculator, nodeA, nodeB, entry.component.getName());
                }
                case AMP -> {
                    // parameterString format: [componentName, NIX, flag]
                    String compName = (paramStrings.length >= 1) ? paramStrings[0] : "";
                    mapping.addAmpProbe(entry.calculator, compName, entry.component.getName());
                }
                case GATE -> {
                    // parameterString format: [switchName, NIX, flag]
                    String switchName = (paramStrings.length >= 1) ? paramStrings[0] : "";
                    mapping.addGateMapping(entry.calculator, switchName);
                }
                case SCOPE -> {
                    // Input labels are the signal names
                    String[] xLabels = entry.component.getTerminalXLabels();
                    mapping.setScopeInfo(entry.calculator, xLabels);
                }
                default -> { /* SIGNAL, CONST, GAIN — no special mapping needed */ }
            }
        }

        // Step 5: Sort calculators in execution order
        // VOLT/AMP probes first (externally driven) → SIGNAL → processing → GATE → SCOPE last
        List<AbstractControlCalculatable> sorted = new ArrayList<>();
        for (CalculatorEntry e : entries) {
            if (isProbeType(e.component.getType())) sorted.add(e.calculator);
        }
        for (CalculatorEntry e : entries) {
            if (e.component.getType() == ControlTypCore.SIGNAL.getTypeNumber()) sorted.add(e.calculator);
        }
        for (CalculatorEntry e : entries) {
            int t = e.component.getType();
            if (!isProbeType(t) && t != ControlTypCore.SIGNAL.getTypeNumber()
                    && t != ControlTypCore.GATE.getTypeNumber()
                    && t != ControlTypCore.SCOPE.getTypeNumber()) {
                sorted.add(e.calculator);
            }
        }
        for (CalculatorEntry e : entries) {
            if (e.component.getType() == ControlTypCore.GATE.getTypeNumber()) sorted.add(e.calculator);
        }
        for (CalculatorEntry e : entries) {
            if (e.component.getType() == ControlTypCore.SCOPE.getTypeNumber()) sorted.add(e.calculator);
        }

        ControlNetlist netlist = ControlNetlist.fromOrderedCalculators(sorted);
        return new BuildResult(netlist, mapping);
    }

    /**
     * Creates the appropriate calculator for a control component.
     */
    private AbstractControlCalculatable createCalculator(CircuitModel.ComponentData comp) {
        int type = comp.getType();
        double[] params = comp.getNumericParameters();
        String[] xLabels = comp.getTerminalXLabels();

        if (!ControlTypCore.isKnown(type)) {
            throw new IllegalArgumentException("Unsupported CONTROL component '" + comp.getName()
                    + "' with type " + type);
        }

        ControlTypCore controlType = ControlTypCore.fromTypeNumber(type);
        return switch (controlType) {
            case VOLT -> new NothingToDoCalculator(0, 1);
            case AMP -> new NothingToDoCalculator(0, 1);
            case CONST -> {
                double value = (params.length > 0) ? params[0] : 0.0;
                yield new ConstantCalculator(value);
            }
            case SIGNAL -> createSignalCalculator(params);
            case SCOPE -> new NothingToDoCalculator(xLabels.length, 0);
            case GATE -> new GateCalculator();
            case GAIN -> {
                double gain = (params.length > 0) ? params[0] : 1.0;
                yield new GainCalculator(gain);
            }
        };
    }

    /**
     * Creates the right signal calculator sub-type based on parameter[0].
     */
    private AbstractControlCalculatable createSignalCalculator(double[] params) {
        if (params.length < 6) {
            LOG.warning("SIGNAL source has fewer than 6 parameters, using defaults");
            return new SignalCalculatorRectangle(0, 1.0, 1000.0, 0.0, 0.0, 0.5);
        }

        int subType = (int) params[0];
        double amplitudeAC = params[1];
        double frequency = params[2];
        double phase = params[3];
        double dcOffset = params[4];
        double duty = params[5];

        return switch (subType) {
            case SIGNAL_SUBTYPE_SINUS ->
                    new SignalCalculatorSinus(0, amplitudeAC, frequency, phase, dcOffset, duty);
            case SIGNAL_SUBTYPE_TRIANGLE ->
                    new SignalCalculatorTriangle(0, amplitudeAC, frequency, phase, dcOffset, duty);
            case SIGNAL_SUBTYPE_RECTANGLE ->
                    new SignalCalculatorRectangle(0, amplitudeAC, frequency, phase, dcOffset, duty);
            default -> {
                LOG.warning("Unknown SIGNAL sub-type " + subType + ", defaulting to rectangle");
                yield new SignalCalculatorRectangle(0, amplitudeAC, frequency, phase, dcOffset, duty);
            }
        };
    }

    /**
     * Parses a node label string to an MNA node index.
     * Labels are string-encoded integers (e.g., "1", "2", "3"). "0" = ground.
     */
    static int parseNodeLabel(String label) {
        if (label == null || label.isEmpty()) {
            return 0; // ground
        }
        try {
            return Integer.parseInt(label.trim());
        } catch (NumberFormatException e) {
            return 0; // ground for unrecognized labels
        }
    }

    private static boolean isProbeType(int type) {
        return type == ControlTypCore.VOLT.getTypeNumber() || type == ControlTypCore.AMP.getTypeNumber();
    }

    private static class CalculatorEntry {
        final CircuitModel.ComponentData component;
        final AbstractControlCalculatable calculator;

        CalculatorEntry(CircuitModel.ComponentData component, AbstractControlCalculatable calculator) {
            this.component = component;
            this.calculator = calculator;
        }
    }

    private static class SignalPort {
        final AbstractControlCalculatable calculator;
        final int portIndex;

        SignalPort(AbstractControlCalculatable calculator, int portIndex) {
            this.calculator = calculator;
            this.portIndex = portIndex;
        }
    }
}

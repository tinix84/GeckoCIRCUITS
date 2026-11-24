package gecko.core.simulation;

import gecko.core.circuit.SourceType;
import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import gecko.core.control.PeriodicStateSource;
import gecko.core.control.StatelessCalculation;
import gecko.core.control.calculators.AbstractControlCalculatable;
import gecko.core.control.calculators.SignalCalculatorExternalWrapper;
import gecko.core.control.calculators.SignalCalculatorRandom;
import gecko.core.control.calculators.SignalCalculatorSinus;
import java.util.ArrayList;
import java.util.List;

/** Checks that a circuit can reach a periodic state of the requested period, before any run state exists. */
final class PeriodicSteadyStateValidation {
    private static final double WHOLE = 1e-9;

    private PeriodicSteadyStateValidation() { }

    /**
     * Checks the circuit parts of the settings: sine sources and the state export of every CONTROL block.
     *
     * @param period the simulated period in seconds
     * @param netlist the electrical netlist, or null
     * @param calculators the assembled CONTROL calculators
     * @return one issue per violation, with its parameter path
     */
    static List<SimulationTiming.SettingIssue> circuitIssues(double period, CircuitNetlist netlist,
            List<AbstractControlCalculatable> calculators) {
        var issues = new ArrayList<SimulationTiming.SettingIssue>();
        if (netlist != null) {
            for (int element = 0; element < netlist.getElementCount(); element++) {
                CircuitTypCore type = netlist.getType(element);
                if (type != CircuitTypCore.LK_U && type != CircuitTypCore.LK_I) continue;
                double[] parameters = netlist.getParameter(element);
                int kind = (int) parameters[0];
                if ((kind == SourceType.QUELLE_SIN || kind == SourceType.QUELLE_SIN_NEW) && !wholeCycles(period, parameters[2])) {
                    issues.add(new SimulationTiming.SettingIssue("periodicSteadyState.period",
                            "Electrical sine source " + element + " does not complete whole cycles in the period."));
                }
            }
        }
        for (int index = 0; index < calculators.size(); index++) {
            var calculator = calculators.get(index);
            String name = calculator.getClass().getSimpleName() + "#" + index;
            if (calculator instanceof SignalCalculatorRandom) {
                issues.add(new SimulationTiming.SettingIssue("periodicSteadyState",
                        "CONTROL block " + name + " draws from a hidden random generator, so its state cannot repeat."));
                continue;
            }
            var periodic = calculator instanceof SignalCalculatorExternalWrapper wrapper ? wrapper.wrapped() : calculator;
            if (periodic instanceof SignalCalculatorSinus sine && !wholeCycles(period, sine.frequency())) {
                issues.add(new SimulationTiming.SettingIssue("periodicSteadyState.period",
                        "CONTROL sine source " + name + " does not complete whole cycles in the period."));
            }
            if (!(calculator instanceof PeriodicStateSource) && !(calculator instanceof StatelessCalculation)) {
                issues.add(new SimulationTiming.SettingIssue("periodicSteadyState",
                        "CONTROL block " + name + " has no periodic state export, so convergence cannot be checked."));
            }
        }
        return issues;
    }

    private static boolean wholeCycles(double period, double frequency) {
        double cycles = period * frequency;
        return Double.isFinite(cycles) && Math.abs(cycles - Math.rint(cycles)) <= WHOLE;
    }
}

package gecko.core.simulation;

import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import gecko.core.control.calculators.ConstantCalculator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Desktop SimulationsKern.setControlledSourcesFromControlValue: parameter[1] is the signal-controlled source value. */
class SignalSourceCouplingContractTest {
    private static CircuitNetlist source() {
        var netlist = new CircuitNetlist();
        double[] parameters = new double[21];
        parameters[0] = 400;
        parameters[1] = -0.02; // a stale saved value
        netlist.initNetlist(new CircuitTypCore[]{CircuitTypCore.LK_U}, new int[]{1}, new int[]{0},
                new int[]{1}, new double[][]{parameters}, 1, 1, 1);
        netlist.setComponentNameToIndex(Map.of("U.2", 0));
        return netlist;
    }

    @Test
    void theControlOutputBecomesTheSourceValue() {
        var netlist = source();
        var producer = new ConstantCalculator(0);
        var mapping = new ControlBlockMapping();
        mapping.addSourceMapping(producer, 0, "U.2");
        var coupler = new DomainCoupler();
        coupler.configureFromMapping(mapping);

        coupler.transferControlToSources(netlist);
        assertEquals(0, netlist.getParameter(0)[1]);
        producer._outputSignal[0][0] = 3.5;
        coupler.transferControlToSources(netlist);
        assertEquals(3.5, netlist.getParameter(0)[1]);
    }

    @Test
    void anUnassignedSignalHoldsTheSourceAtZero() {
        var netlist = source();
        var mapping = new ControlBlockMapping();
        mapping.addSourceMapping(null, 0, "U.2");
        var coupler = new DomainCoupler();
        coupler.configureFromMapping(mapping);

        coupler.transferControlToSources(netlist);
        assertEquals(0, netlist.getParameter(0)[1]);
    }
}

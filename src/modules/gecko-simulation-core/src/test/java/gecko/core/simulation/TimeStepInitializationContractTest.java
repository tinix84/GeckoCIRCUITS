package gecko.core.simulation;

import gecko.core.allg.SolverType;
import gecko.core.circuit.circuitcomponents.CircuitTypCore;
import gecko.core.circuit.netlist.CircuitNetlist;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class TimeStepInitializationContractTest {
    static CircuitNetlist discharge(CircuitTypCore type,double initial) {
        double[][] parameters=new double[2][13];
        parameters[0][0]=type==CircuitTypCore.LK_C?0.001:0.01;
        parameters[0][1]=initial;
        // Stale runtime values must not override a fresh dialog start.
        parameters[0][2]=77;parameters[0][4]=88;parameters[0][5]=99;
        if(type==CircuitTypCore.LK_C){parameters[0][6]=0.001;parameters[0][7]=0.001;}
        parameters[1][0]=10;
        CircuitNetlist netlist=new CircuitNetlist();
        netlist.initNetlist(new CircuitTypCore[]{type,CircuitTypCore.LK_R},
            new int[]{1,1},new int[]{0,0},new int[]{type==CircuitTypCore.LK_LKOP2?1:-1,-1},
            parameters,1,type==CircuitTypCore.LK_LKOP2?1:0,2);
        return netlist;
    }
    @ParameterizedTest @EnumSource(SolverType.class)
    void prechargedCapacitorStartsFromItsConfiguredVoltage(SolverType method) {
        var netlist=discharge(CircuitTypCore.LK_C,5);var step=new ElectricalTimeStep(netlist,method);
        double dt=1e-4;
        step.advance(dt,0,null);
        double h=dt/(10*0.001);
        double expected=method==SolverType.SOLVER_BE?5/(1+h):
            method==SolverType.SOLVER_TRZ?5*(1-h/2)/(1+h/2):5*1.5/(1.5+h);
        assertEquals(expected,netlist.getLastNodeVoltages()[1],1e-10);
        assertEquals(5,netlist.getParameter(0)[1],"Configured initial voltage remains reusable");
        assertEquals(-netlist.getLastComponentCurrents()[1],netlist.getLastComponentCurrents()[0],1e-10);
    }
    @ParameterizedTest @EnumSource(SolverType.class)
    void ordinaryInductorStartsFromItsConfiguredCurrent(SolverType method) {
        checkInductor(CircuitTypCore.LK_L,method);
    }
    @ParameterizedTest @EnumSource(SolverType.class)
    void auxiliaryCurrentWindingStartsFromItsConfiguredCurrent(SolverType method) {
        checkInductor(CircuitTypCore.LK_LKOP2,method);
    }
    static void checkInductor(CircuitTypCore type,SolverType method) {
        var netlist=discharge(type,2);var step=new ElectricalTimeStep(netlist,method);double dt=1e-4;
        step.advance(dt,0,null);
        double h=dt*10/0.01;
        double expected=method==SolverType.SOLVER_BE?2/(1+h):
            method==SolverType.SOLVER_TRZ?2*(1-h/2)/(1+h/2):2*1.5/(1.5+h);
        assertEquals(expected,netlist.getLastComponentCurrents()[0],1e-10);
        assertEquals(-10*expected,netlist.getLastNodeVoltages()[1],1e-10);
        assertEquals(2,netlist.getParameter(0)[1],"Runtime current must not overwrite the dialog initial current");
    }
    @ParameterizedTest @ValueSource(doubles={0,-1,Double.NaN,Double.POSITIVE_INFINITY})
    void invalidStepWidthCannotCommitOrNotifyAnElectricalStep(double dt) {
        var netlist=discharge(CircuitTypCore.LK_C,0);var step=new ElectricalTimeStep(netlist,SolverType.SOLVER_BE);
        List<Double> notifications=new ArrayList<>();
        assertThrows(IllegalArgumentException.class,()->step.advance(dt,0,(t,p,i,n)->notifications.add(t)));
        assertTrue(notifications.isEmpty());
    }
    @ParameterizedTest
    @EnumSource(SolverType.class)
    void decayHistoriesAdvanceExactlyOnceAndRestartFromConfiguredState(SolverType method) {
        for (CircuitTypCore type : new CircuitTypCore[]{
                CircuitTypCore.LK_C, CircuitTypCore.LK_L, CircuitTypCore.LK_LKOP2}) {
            boolean capacitor = type == CircuitTypCore.LK_C;
            double initial = capacitor ? 5 : 2;
            var netlist = discharge(type, initial);
            double dt = 1e-4;
            double h = capacitor ? dt / (10 * 0.001) : dt * 10 / 0.01;
            double previous = initial;
            double older = initial;
            double first = 0;
            for (int run = 0; run < 2; run++) {
                var step = new ElectricalTimeStep(netlist, method);
                previous = initial;
                older = initial;
                List<Double> times = new ArrayList<>();
                for (int index = 0; index < 8; index++) {
                    double expected = switch (method) {
                        case SOLVER_BE -> previous / (1 + h);
                        case SOLVER_TRZ -> previous * (1 - h / 2) / (1 + h / 2);
                        case SOLVER_GS -> (2 * previous - 0.5 * older) / (1.5 + h);
                    };
                    step.advance(dt, index * dt, (t, p, i, n) -> times.add(t));
                    double actual = capacitor ? netlist.getLastNodeVoltages()[1]
                            : netlist.getLastComponentCurrents()[0];
                    assertEquals(expected, actual, 1e-10, type + " step " + index);
                    assertEquals(0, Arrays.stream(netlist.getLastComponentCurrents()).sum(), 1e-10);
                    assertEquals(initial, netlist.getParameter(0)[1]);
                    if (index == 0) {
                        if (run == 0) first = actual;
                        else assertEquals(first, actual, 1e-12);
                    }
                    older = previous;
                    previous = expected;
                }
                assertEquals(8, times.size());
                assertEquals(7 * dt, times.getLast());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidSampleTimeCannotAdvanceHistory(double time) {
        var netlist = discharge(CircuitTypCore.LK_C, 5);
        var step = new ElectricalTimeStep(netlist, SolverType.SOLVER_BE);
        assertThrows(IllegalArgumentException.class, () -> step.advance(1e-4, time, null));
        step.advance(1e-4, 0, null);
        assertEquals(5 / 1.01, netlist.getLastNodeVoltages()[1], 1e-10);
    }

    @ParameterizedTest
    @EnumSource(SolverType.class)
    void engineLoadsConfiguredCapacitorStateAndRepeatsTheSameTrace(SolverType method) throws Exception {
        var file = java.nio.file.Files.createTempFile("solver-initial-capacitor-", ".ipes");
        java.nio.file.Files.writeString(file, """
                tDURATION 0.0003
                dt 0.0001
                e
                <ElementLK>
                typ 3
                idStringDialog C1
                parameter[] 0.001 5 77 0 88 99 0.001 0.001 0 0 0 0 0
                labelAnfangsKnoten[] /n
                labelEndKnoten[] /0
                <\\ElementLK>
                e
                <ElementLK>
                typ 1
                idStringDialog R1
                parameter[] 10
                labelAnfangsKnoten[] /n
                labelEndKnoten[] /0
                <\\ElementLK>
                """);
        var engine = new HeadlessSimulationEngine();
        var config = SimulationConfig.builder().circuitFile(file.toString()).solverType(method).build();
        List<Double> first = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            List<Double> values = new ArrayList<>();
            engine.setStepTraceListener((t, p, i, n) -> {
                int capacitor = n.getType(0) == CircuitTypCore.LK_C ? 0 : 1;
                values.add(p[n.getNodeX(capacitor)] - p[n.getNodeY(capacitor)]);
            });
            var result = engine.runSimulation(config);
            assertTrue(result.isSuccess(), result.getErrorMessage());
            double expected = switch (method) {
                case SOLVER_BE -> 5 / 1.01;
                case SOLVER_TRZ -> 5 * 0.995 / 1.005;
                case SOLVER_GS -> 7.5 / 1.51;
            };
            assertEquals(expected, values.getFirst(), 1e-10);
            if (run == 0) first = values;
            else assertEquals(first, values);
        }
    }

}

import gecko.core.preparation.ExecutionOptions;
import gecko.core.simulation.HeadlessSimulationEngine;
import java.nio.file.Path;
import java.util.Arrays;

/** Runs the standalone RC fixture through the headless simulation engine. */
public final class RcReference {
    private RcReference() { }

    public static void main(String[] args) throws Exception {
        var model = GeckoFmiWorker.readCircuit(Path.of(args[0]));
        var result = new HeadlessSimulationEngine().runSimulation(
                GeckoFmiWorker.prepare(model), ExecutionOptions.defaults(), () -> false);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getErrorMessage());
        }
        int signal = Arrays.asList(result.getSignalNames()).indexOf("vc");
        int source = Arrays.asList(result.getSignalNames()).indexOf("vin");
        if (signal < 0 || source < 0) {
            throw new IllegalStateException("The standalone circuit must record the vc signal.");
        }
        double[] times = result.getTimeArray();
        float[] voltage = result.getSignalData(signal);
        float[] input = result.getSignalData(source);
        System.out.println("time,Vc,Vs");
        for (int index = 0; index < times.length; index++) {
            System.out.println(times[index] + "," + voltage[index] + "," + input[index]);
        }
    }
}

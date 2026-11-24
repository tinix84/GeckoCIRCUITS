import gecko.core.io.CircuitFileParser;
import gecko.core.io.CircuitModel;
import gecko.core.preparation.CircuitPreparation;
import gecko.core.preparation.ExecutionOptions;
import gecko.core.preparation.PreparationRequest;
import gecko.core.preparation.PreparationResult;
import gecko.core.preparation.PreparedCircuit;
import gecko.core.simulation.SessionHost;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/** Owns the simulation session behind one FMU instance. */
public final class GeckoFmiWorker {
    private GeckoFmiWorker() { }

    public static void main(String[] args) throws Exception {
        PrintStream replies = System.out;
        System.setOut(System.err);
        CircuitModel model = readCircuit(Path.of(args[0]));
        if (model.getPreSimulationTime() != 0) {
            throw new IllegalArgumentException("Set the pre-simulation duration to zero.");
        }
        try (var host = SessionHost.open(prepare(model), new ExecutionOptions(false, 1));
             var requests = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            int inputCount = 0;
            for (int port = 0; port < host.externalInputPorts(); port++) {
                inputCount += host.externalInputWidth(port);
            }
            int outputCount = 0;
            for (int port = 0; port < host.externalOutputPorts(); port++) {
                outputCount += host.externalOutputWidth(port);
            }
            double width = host.status().stepWidth();
            replies.println(inputCount + " " + outputCount + " " + width + " " + host.status().endTime());
            for (String line; (line = requests.readLine()) != null; ) {
                String[] input = line.isEmpty() ? new String[0] : line.split(" ", -1);
                if (input.length != inputCount) {
                    throw new IllegalArgumentException("The number of external inputs does not match.");
                }
                if (host.status().nextTime() > host.status().endTime()) {
                    host.extend(host.status().nextTime(), width).get(20, TimeUnit.SECONDS);
                }
                double[][] output = host.stepExternal(inputs(host, input)).get(20, TimeUnit.SECONDS);
                host.drainSamples(sample -> { });
                replies.println(reply(host.status().time(), output));
            }
        }
    }

    static CircuitModel readCircuit(Path path) throws Exception {
        try (var data = Files.newInputStream(path)) {
            return new CircuitFileParser().parse(data, path.getFileName().toString());
        }
    }

    static PreparedCircuit prepare(CircuitModel model) {
        var preparation = new CircuitPreparation();
        var result = preparation.prepare(preparation.capture(model), PreparationRequest.defaults());
        if (result instanceof PreparationResult.Ready ready) {
            return ready.execution();
        }
        throw new IllegalArgumentException("Cannot prepare the circuit: " + result.report());
    }

    private static double[][] inputs(SessionHost host, String[] input) {
        double[][] result = new double[host.externalInputPorts()][];
        int index = 0;
        for (int port = 0; port < result.length; port++) {
            result[port] = new double[host.externalInputWidth(port)];
            for (int column = 0; column < result[port].length; column++) {
                double value = Double.parseDouble(input[index++]);
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("External inputs must be finite.");
                }
                result[port][column] = value;
            }
        }
        return result;
    }

    private static String reply(double time, double[][] output) {
        StringJoiner result = new StringJoiner(" ").add(Double.toString(time));
        for (double[] port : output) {
            for (double value : port) {
                if (!Double.isFinite(value)) {
                    throw new IllegalStateException("An external output is not finite.");
                }
                result.add(Double.toString(value));
            }
        }
        return result.toString();
    }
}

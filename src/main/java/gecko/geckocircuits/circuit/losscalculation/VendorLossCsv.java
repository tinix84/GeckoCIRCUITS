package gecko.geckocircuits.circuit.losscalculation;

import gecko.core.circuit.losscalculation.LeitverlusteMesskurve;
import gecko.core.circuit.losscalculation.SwitchingLossCurve;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Converts vendor CSV samples to a GeckoCIRCUITS detailed loss file (.scl).
 * Use the header {@code curve,tj_c,current_a,v_drop_v,blocking_v,e_on_j,e_off_j}.
 * Use {@code conduction} for voltage-drop samples. Leave the last three columns empty.
 * Use {@code switching} for turn-on and turn-off energy samples. Leave {@code v_drop_v} empty.
 * Use decimal points and SI units, except for junction temperature in degrees Celsius.
 * Supply at least two distinct currents per curve. The reader requires an increasing conduction voltage.
 * Supply one blocking voltage per switching temperature. The loss model scales switching energy
 * linearly with voltage. The CSV supports blank lines and comment lines that start with {@code #}.
 */
public final class VendorLossCsv {
    private static final String HEADER = "curve,tj_c,current_a,v_drop_v,blocking_v,e_on_j,e_off_j";

    private VendorLossCsv() {}

    /** Reads CSV samples and returns the existing detailed loss format in UTF-8. */
    public static byte[] toDetailedLossFile(Reader input) throws IOException {
        var reader = new BufferedReader(input);
        var conduction = new TreeMap<Double, SortedMap<Double, Double>>();
        var switching = new TreeMap<Double, SwitchingSamples>();
        String line;
        int lineNumber = 0;
        boolean headerRead = false;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!headerRead) {
                if (!line.replace("\uFEFF", "").equals(HEADER)) {
                    throw invalid(lineNumber, "Use the documented loss CSV header.");
                }
                headerRead = true;
                continue;
            }
            String[] columns = line.split(",", -1);
            if (columns.length != 7) {
                throw invalid(lineNumber, "Enter seven columns.");
            }
            for (int index = 0; index < columns.length; index++) {
                columns[index] = columns[index].strip();
            }
            double temperature = number(columns[1], lineNumber, "junction temperature");
            double current = number(columns[2], lineNumber, "current");
            if (temperature < -273.15) {
                throw invalid(lineNumber, "Enter a temperature above absolute zero.");
            }
            switch (columns[0]) {
                case "conduction" -> {
                    if (!columns[4].isEmpty() || !columns[5].isEmpty() || !columns[6].isEmpty()) {
                        throw invalid(lineNumber, "Leave the switching columns empty for conduction.");
                    }
                    double voltage = number(columns[3], lineNumber, "voltage drop");
                    if (current * voltage < 0) {
                        throw invalid(lineNumber, "Use matching current and voltage signs.");
                    }
                    var points = conduction.computeIfAbsent(temperature, key -> new TreeMap<>());
                    if (points.putIfAbsent(current, voltage) != null) {
                        throw invalid(lineNumber, "Enter one conduction sample per current and temperature.");
                    }
                }
                case "switching" -> {
                    if (!columns[3].isEmpty()) {
                        throw invalid(lineNumber, "Leave the voltage drop empty for switching.");
                    }
                    double blocking = number(columns[4], lineNumber, "blocking voltage");
                    double turnOn = number(columns[5], lineNumber, "turn-on energy");
                    double turnOff = number(columns[6], lineNumber, "turn-off energy");
                    if (blocking <= 0 || turnOn < 0 || turnOff < 0) {
                        throw invalid(lineNumber, "Enter a positive blocking voltage and non-negative energies.");
                    }
                    var samples = switching.computeIfAbsent(temperature, key -> new SwitchingSamples(blocking));
                    if (samples.voltage() != blocking) {
                        throw invalid(lineNumber, "Use one blocking voltage per switching temperature.");
                    }
                    if (samples.points().putIfAbsent(current, new Energy(turnOn, turnOff)) != null) {
                        throw invalid(lineNumber, "Enter one switching sample per current and temperature.");
                    }
                }
                default -> throw invalid(lineNumber, "Use conduction or switching in the curve column.");
            }
        }

        if (conduction.isEmpty() || switching.isEmpty()) {
            throw new IllegalArgumentException("Supply conduction and switching curves.");
        }
        List<LeitverlusteMesskurve> conductionCurves = new ArrayList<>();
        for (var entry : conduction.entrySet()) {
            double[][] points = new double[2][entry.getValue().size()];
            requireTwo(points[0].length, entry.getKey());
            int index = 0;
            for (var sample : entry.getValue().entrySet()) {
                double voltage = sample.getValue();
                if (index > 0 && voltage <= points[0][index - 1]) {
                    throw new IllegalArgumentException("Increase conduction voltage as current increases.");
                }
                points[0][index] = voltage;
                points[1][index] = sample.getKey();
                index++;
            }
            var curve = new LeitverlusteMesskurve(entry.getKey());
            curve.setCurveData(points);
            conductionCurves.add(curve);
        }

        List<SwitchingLossCurve> switchingCurves = new ArrayList<>();
        for (var entry : switching.entrySet()) {
            var samples = entry.getValue();
            double[][] points = new double[3][samples.points().size()];
            requireTwo(points[0].length, entry.getKey());
            int index = 0;
            for (var sample : samples.points().entrySet()) {
                points[0][index] = sample.getKey();
                points[1][index] = sample.getValue().turnOn();
                points[2][index] = sample.getValue().turnOff();
                index++;
            }
            var curve = new SwitchingLossCurve(entry.getKey(), samples.voltage());
            curve.setCurveData(points);
            switchingCurves.add(curve);
        }
        String lossFile = VerlustBerechnungDetailed.lossFileContents(switchingCurves, conductionCurves);
        return lossFile.getBytes(StandardCharsets.UTF_8);
    }

    private static double number(String raw, int line, String column) {
        try {
            double value = Double.parseDouble(raw);
            if (Double.isFinite(value)) {
                return value == 0 ? 0 : value;
            }
        } catch (NumberFormatException ignored) {
            // Use the same diagnostic for an invalid number and a non-finite number.
        }
        throw invalid(line, "Enter a finite number for " + column + ".");
    }

    private static void requireTwo(int points, double temperature) {
        if (points < 2) {
            throw new IllegalArgumentException("Supply at least two current samples per curve at " + temperature + " °C.");
        }
    }

    private static IllegalArgumentException invalid(int line, String message) {
        return new IllegalArgumentException("Line " + line + ". " + message);
    }

    private record Energy(double turnOn, double turnOff) {}

    private record SwitchingSamples(double voltage, SortedMap<Double, Energy> points) {
        SwitchingSamples(double voltage) {
            this(voltage, new TreeMap<>());
        }
    }
}

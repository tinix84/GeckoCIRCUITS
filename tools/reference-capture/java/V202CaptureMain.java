import ch.technokrat.gecko.GeckoExternal;
import ch.technokrat.gecko.GeckoSim;
import ch.technokrat.gecko.geckocircuits.allg.Fenster;
import ch.technokrat.gecko.geckocircuits.circuit.V202TraceCapture;
import ch.technokrat.gecko.geckocircuits.control.NetzlisteCONTROL;
import ch.technokrat.gecko.geckocircuits.datacontainer.DataContainerGlobal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/** Starts a normal release run and reads its completed SCOPE recording. */
public final class V202CaptureMain {
    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException("Usage: circuit output-directory stock|observer");
            Path output = Paths.get(args[1]);
            Files.createDirectories(output);
            Path home = output.toAbsolutePath().resolve("home");
            Files.createDirectory(home);
            System.setProperty("user.home", home.toString());
            GeckoSim._isTestingMode = true;
            GeckoExternal.startGui();
            GeckoExternal.openFile(Paths.get(args[0]).toAbsolutePath().toString());
            System.out.println("CAPTURE_SETTINGS dt=" + Fenster._solverSettings.dt.getValue()
                    + " end=" + Fenster._solverSettings._tDURATION.getValue()
                    + " solver=" + Fenster._solverSettings.SOLVER_TYPE.getValue());
            V202TraceCapture capture = null;
            if (args[2].equals("observer")) {
                capture = new V202TraceCapture(output);
                V202TraceCapture.active = capture;
            } else if (!args[2].equals("stock")) throw new IllegalArgumentException("Unknown capture mode.");
            GeckoExternal.runSimulation();
            if (capture != null) { capture.close(); V202TraceCapture.active = null; }
            DataContainerGlobal data = NetzlisteCONTROL.globalData;
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                    Files.newOutputStream(output.resolve("scope.csv.gz"), StandardOpenOption.CREATE_NEW)), StandardCharsets.UTF_8)))) {
                writer.print("time");
                int rows = data.getRowLength();
                int last = data.getMaximumTimeIndex(0);
                if (rows == 0 || last < 0) throw new IllegalStateException("No SCOPE witness data.");
                for (int row = 0; row < rows; row++) {
                    if (data.getMaximumTimeIndex(row) != last) throw new IllegalStateException("Unequal SCOPE lengths.");
                    String name = data.getSignalName(row);
                    if (name == null || name.indexOf(',') >= 0) throw new IllegalStateException("Invalid SCOPE identity.");
                    writer.print("," + name);
                }
                writer.println();
                for (int index = 0; index <= last; index++) {
                    writer.printf(Locale.ROOT, "%.17e", data.getTimeValue(index, 0));
                    for (int row = 0; row < rows; row++) writer.print("," + Float.toHexString(data.getValue(row, index)));
                    writer.println();
                }
                if (writer.checkError()) throw new IOException("SCOPE witness write failed.");
                System.out.println("SCOPE_WITNESS samples=" + (last + 1) + " channels=" + rows);
            }
            System.out.println("CAPTURE_SUCCESS");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}

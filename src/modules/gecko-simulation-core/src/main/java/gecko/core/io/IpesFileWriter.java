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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a {@link CircuitModel} to the GeckoCIRCUITS .ipes plain-text format.
 *
 * <p>Two forms of output are provided:
 * <ul>
 *   <li>{@link #writePlainText(CircuitModel)} – UTF-8 plain text, suitable for
 *       passing directly to {@link CircuitFileParser} from an in-memory stream.</li>
 *   <li>{@link #writeGzipCompressed(CircuitModel)} – GZIP-compressed, suitable
 *       for saving as a {@code .ipes} file and opening in the GeckoCIRCUITS GUI.</li>
 * </ul>
 *
 * <p>The format produced is compatible with GeckoCIRCUITS file version 161.
 * Only circuit (power-domain) components are written; control and thermal
 * domains are left empty.
 */
public class IpesFileWriter {

    private static final String NIX = "NIX_NIX_NIX";
    private static final int FILE_VERSION = 161;
    private static final Random RAND = new Random();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Serialises the model to a UTF-8 plain-text byte array in .ipes format.
     *
     * @param model the circuit model to serialise
     * @return raw UTF-8 bytes
     * @throws IOException if writing fails (should not happen for in-memory output)
     */
    public byte[] writePlainText(CircuitModel model) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
            writeContent(model, w);
        }
        return baos.toByteArray();
    }

    /**
     * Serialises the model to GZIP-compressed bytes in .ipes format.
     * The result can be saved directly as a {@code .ipes} file.
     *
     * @param model the circuit model to serialise
     * @return GZIP-compressed bytes
     * @throws IOException if writing fails
     */
    public byte[] writeGzipCompressed(CircuitModel model) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
             Writer w = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            writeContent(model, w);
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Format writer
    // -------------------------------------------------------------------------

    private void writeContent(CircuitModel model, Writer w) throws IOException {
        List<CircuitModel.ComponentData> circuitComps = model.getCircuitComponents();

        // 1 – Connection blocks (empty in this implementation;
        //     GeckoCIRCUITS GUI will let the user draw connections)
        w.write("verbindungLeistungskreisANZAHL 0\n");
        w.write("\n");

        // 2 – Element blocks
        w.write("elementANZAHL " + circuitComps.size() + "\n");
        w.write("\n");
        for (int i = 0; i < circuitComps.size(); i++) {
            writeElementLK(w, i, circuitComps.get(i));
        }

        // 3 – Control and thermal sections (empty)
        w.write("controlANZAHL 0\n");
        w.write("\n");
        w.write("thermANZAHL 0\n");
        w.write("\n");

        // 4 – Optimizer, scripting
        w.write("optimizerName[] \n");
        w.write("optimizerValue[]  \n");
        w.write("<scripterCode>\n    \n<\\scripterCode> \n");
        w.write("<scripterImports>\n\n<\\scripterImports> \n");
        w.write("<scripterDeclarations>\n \n<\\scripterDeclarations>\n");
        w.write("\n");
        w.write("GeckoFileManager\n");
        w.write("<GeckoFileManager>\n");
        w.write("<\\GeckoFileManager>\n");
        w.write("\n");

        // 5 – Simulation parameters
        String today = LocalDate.now().toString();
        int uid = model.getUniqueFileId() != 0 ? model.getUniqueFileId() : RAND.nextInt();
        w.write("DtStor " + today + "\n");
        w.write("tDURATION " + model.getSimulationDuration() + "\n");
        w.write("bl 0\n");
        w.write("dt " + model.getTimeStep() + "\n");
        w.write("tPAUSE " + model.getPauseTime() + "\n");
        w.write("T_pre " + model.getPreSimulationTime() + "\n");
        w.write("dt_pre " + model.getPreSimulationTimeStep() + "\n");
        w.write("solverType 0\n");
        String filePath = model.getFilePath() != null ? model.getFilePath() : "converted.ipes";
        w.write("path " + filePath + "\n");
        w.write("\n");

        // 6 – Display settings
        w.write("dpix 16\n");
        w.write("fontSize " + (model.getFontSize() > 0 ? model.getFontSize() : 10) + "\n");
        w.write("fontTyp Dialog.plain\n");
        w.write("fensterWidth " + (model.getWindowWidth() > 0 ? model.getWindowWidth() : 1176) + "\n");
        w.write("fensterHeight " + (model.getWindowHeight() > 0 ? model.getWindowHeight() : 756) + "\n");
        w.write("worksheetSizeX 60\n");
        w.write("worksheetSizeY 60\n");
        w.write("ANSICHT_SHOW_LK_NAME true\n");
        w.write("ANSICHT_SHOW_LK_PARAMETER true\n");
        w.write("ANSICHT_SHOW_LK_FLOWDIR true\n");
        w.write("ANSICHT_SHOW_LK_TEXTLINIE true\n");
        w.write("ANSICHT_SHOW_THERM_NAME true\n");
        w.write("ANSICHT_SHOW_THERM_PARAMETER true\n");
        w.write("ANSICHT_SHOW_THERM_FLOWDIR false\n");
        w.write("ANSICHT_SHOW_THERM_TEXTLINIE true\n");
        w.write("ANSICHT_SHOW_CONTROL_NAME false\n");
        w.write("ANSICHT_SHOW_CONTROL_PARAMETER true\n");
        w.write("ANSICHT_SHOW_CONTROL_TEXTLINIE true\n");

        // 7 – File metadata
        w.write("FileVersion " + FILE_VERSION + "\n");
        w.write("UniqueFileId " + uid + "\n");
        w.write("dataContainerSignals[] [] \n");
        w.write("=======================\n");
    }

    // -------------------------------------------------------------------------
    // Element block writer
    // -------------------------------------------------------------------------

    private void writeElementLK(Writer w, int index, CircuitModel.ComponentData comp) throws IOException {
        w.write("e (" + index + ")\n");
        w.write("<ElementLK>\n");

        // Terminal labels (use "/" prefix convention)
        String startNode = getFirstLabel(comp.getTerminalXLabels(), "gnd");
        String endNode   = getFirstLabel(comp.getTerminalYLabels(), "gnd");
        w.write("labelAnfangsKnoten[] /" + startNode + "\n");
        w.write("labelEndKnoten[] /" + endNode + "\n");

        w.write("enabledShorted 1\n");
        w.write("parentSheetIdentifier 0\n");
        w.write("typ " + comp.getType() + "\n");
        w.write("uniqueObjectIdentifier " + RAND.nextInt() + "\n");
        w.write("x " + comp.getPosition()[0] + "\n");
        w.write("y " + comp.getPosition()[1] + "\n");

        // Parameter array
        w.write("parameter[]");
        writeParameterArray(w, comp);
        w.write("\n");

        // Placeholder string arrays
        w.write("parameterString[] /" + NIX + "/" + NIX + "/0\n");
        w.write("nameOpt[] /" + NIX + "/" + NIX + "/" + NIX + "/" + NIX + "/" + NIX + "/" + NIX + "\n");

        w.write("orientierung " + comp.getOrientation() + "\n");
        w.write("idStringDialog " + comp.getName() + "\n");
        w.write("isNonlinear false\n");
        w.write("nonlinX[] 0.0 100.0 300.0 400.0 \n");
        w.write("nonlinY[] 1.0E-7 8.0E-8 1.2E-9 1.0E-9 \n");
        w.write("nonLinearCharHashValue 0\n");
        w.write("dxTxt 0.0\n");
        w.write("dyTxt 0.0\n");
        w.write("\n");
        w.write("<\\ElementLK>\n");
        w.write("\n");
    }

    private static void writeParameterArray(Writer w, CircuitModel.ComponentData comp) throws IOException {
        // Collect numbered params in order
        int maxIdx = -1;
        for (String key : comp.getParameters().keySet()) {
            if (key.startsWith("param")) {
                try {
                    int idx = Integer.parseInt(key.substring(5));
                    if (idx > maxIdx) maxIdx = idx;
                } catch (NumberFormatException ignored) { /* skip */ }
            }
        }

        if (maxIdx >= 0) {
            for (int i = 0; i <= maxIdx; i++) {
                Object raw = comp.getParameters().get("param" + i);
                double val = toDouble(raw);
                w.write(" " + (!Double.isNaN(val) ? val : "0.0"));
            }
        } else {
            // Fallback: use the primary semantic value if available
            double primary = Double.NaN;
            for (Object v : comp.getParameters().values()) {
                double d = toDouble(v);
                if (!Double.isNaN(d)) { primary = d; break; }
            }
            w.write(" " + (!Double.isNaN(primary) ? primary : "0.0") + " 0.0 0.0");
        }
        w.write(" ");
    }

    private static double toDouble(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { /* fall */ }
        }
        return Double.NaN;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String getFirstLabel(String[] labels, String fallback) {
        if (labels != null) {
            for (String l : labels) {
                if (l != null && !l.isBlank()) return l;
            }
        }
        return fallback;
    }
}

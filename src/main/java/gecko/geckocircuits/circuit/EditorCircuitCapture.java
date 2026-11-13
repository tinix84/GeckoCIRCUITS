package gecko.geckocircuits.circuit;

import gecko.core.allg.GeckoFile;
import gecko.core.io.CircuitFileParser;
import gecko.core.io.CircuitModel;
import gecko.core.io.CircuitTopology;
import gecko.core.preparation.CircuitPreparation;
import gecko.core.preparation.CircuitSnapshot;
import gecko.geckocircuits.allg.GeckoFileManager;
import gecko.geckocircuits.allg.OptimizerParameterData;
import gecko.geckocircuits.allg.ProjectData;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import gecko.geckocircuits.control.SubCircuitSheet;

/** Captures unsaved editor configuration. The caller owns editor-thread synchronization. */
public final class EditorCircuitCapture {
    public record ScriptSources(String code, String imports, String declarations, String extraFiles) {
        public static final ScriptSources EMPTY = new ScriptSources("", "", "", "");
    }

    /**
     * Captures the complete flattened sheet list with its parent identifiers.
     * Use CircuitSheet.getAllElements to include all nested sheets.
     * This method does not read results or desktop globals.
     */
    public CircuitSnapshot capture(Iterable<? extends AbstractCircuitSheetComponent> elements,
            SolverSettings settings, OptimizerParameterData optimizer, GeckoFileManager files,
            ScriptSources scripts, String sourceName) throws IOException, CircuitFileParser.CircuitParseException {
        var ascii = new StringBuffer();
        var blocks = new ArrayList<AbstractBlockInterface>();
        for (var element : elements) {
            ascii.append('\n').append(element.getExportImportCharacters()).append('\n');
            element.exportASCII(ascii);
            if (element instanceof AbstractBlockInterface block) blocks.add(block);
        }
        ProjectData.appendAsString(ascii.append("\noptimizerName"), optimizer.getNameOpt());
        ProjectData.appendAsString(ascii.append("\noptimizerValue"), optimizer.getValueOpt());
        section(ascii, "scripterCode", scripts.code());
        section(ascii, "scripterImports", scripts.imports());
        section(ascii, "scripterDeclarations", scripts.declarations());
        section(ascii, "extraScriptSourceFiles", scripts.extraFiles());
        ascii.append("\nGeckoFileManager\n<GeckoFileManager>");
        for (var file : files._allAvailableFiles) appendOwnedFile(ascii, file);
        ascii.append("\n<\\GeckoFileManager>");
        ascii.append("\ntDURATION ").append(settings._tDURATION.getValue());
        ascii.append("\ndt ").append(settings.dt.getValue());
        ascii.append("\ntPAUSE ").append(settings._tPAUSE.getValue());
        ascii.append("\nT_pre ").append(settings._T_pre.getValue());
        ascii.append("\ndt_pre ").append(settings._dt_pre.getValue());
        ascii.append("\nsolverType ").append(settings.SOLVER_TYPE.getValue().getOldGeckoIndex());
        var model = new CircuitFileParser().parse(new BufferedReader(new StringReader(ascii.toString())), sourceName);
        var ordinals = new HashMap<String, Integer>();
        for (var block : blocks) {
            String marker = block.getExportImportCharacters().trim();
            List<CircuitModel.ComponentData> components = switch (marker) {
                case "e" -> model.getCircuitComponents();
                case "c" -> model.getControlComponents();
                case "eTH" -> model.getThermalComponents();
                case "sp" -> model.getSpecialComponents();
                default -> List.of();
            };
            int ordinal = ordinals.getOrDefault(marker, 0);
            ordinals.put(marker, ordinal + 1);
            var component = components.get(ordinal);
            captureTerminals(component, block.XIN, CircuitTopology.Side.INPUT);
            captureTerminals(component, block.YOUT, CircuitTopology.Side.OUTPUT);
        }
        return new CircuitPreparation().capture(model);
    }

    private static void captureTerminals(CircuitModel.ComponentData component, List<AbstractTerminal> terminals,
            CircuitTopology.Side side) {
        for (int i = 0; i < terminals.size(); i++) {
            var terminal = terminals.get(i);
            var position = terminal.getPosition();
            long sheet = terminal.getCircuitSheet() instanceof SubCircuitSheet sub
                    ? sub._subBlock.getUniqueObjectIdentifier() : 0;
            component.getTerminalData().add(new CircuitModel.TerminalData(terminal.getCategory().name(), sheet,
                    side, i, position.x, position.y, !(terminal instanceof TerminalHiddenSubcircuit)));
        }
    }

    private static void appendOwnedFile(StringBuffer ascii, GeckoFile file) throws IOException {
        var serialized = new StringBuffer();
        file.exportASCII(serialized);
        if (file.getStorageType() == GeckoFile.StorageType.EXTERNAL) {
            // Capture current external bytes once. Parsing and preparation never reopen this path.
            byte[] bytes;
            try (var input = file.getInputStream()) {
                if (input == null) {
                    ascii.append(serialized);
                    return;
                }
                bytes = input.readAllBytes();
            }
            String external = serialized.toString().replace("\nisExternal 1", "\nisExternal 0");
            int end = external.lastIndexOf("<\\GeckoFile>");
            ascii.append(external, 0, end);
            ProjectData.appendAsString(ascii.append("\nfileContents"), bytes);
            ascii.append("\n<\\GeckoFile>");
        } else {
            ascii.append(serialized);
        }
    }

    private static void section(StringBuffer ascii, String tag, String text) {
        ascii.append("\n<").append(tag).append(">\n").append(text).append("\n<\\").append(tag).append('>');
    }
}

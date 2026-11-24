package gecko.core.io;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gecko.core.io.CircuitFileParser.CircuitParseException;

/** Retains saved configuration without interpreting extension algorithms. */
final class CircuitDescriptionReader {
    private CircuitDescriptionReader() { }

    static void retain(CircuitModel model, String[] original, String[] structure) throws CircuitParseException {
        String[] global = original.clone();
        Set<CircuitModel.ComponentData> assigned = new HashSet<>();
        for (int start = 0; start < structure.length; start++) {
            String line = structure[start].trim();
            if (!(line.startsWith("<Element") || line.equals("<Verbindung>")) || !line.endsWith(">")) continue;
            String tag = line.substring(1, line.length() - 1);
            int end = SavedCircuitBlock.closingLine(structure, start, tag);
            if (end == structure.length) continue;
            var block = SavedCircuitBlock.parse(tag, Arrays.copyOfRange(original, start + 1, end));
            model.getSavedBlocks().add(block);
            Arrays.fill(global, start, end + 1, "");
            List<CircuitModel.ComponentData> candidates = switch (tag) {
                case "ElementLK" -> model.getCircuitComponents();
                case "ElementCONTROL" -> model.getControlComponents();
                case "ElementTHERM" -> model.getThermalComponents();
                default -> List.of();
            };
            if (tag.equals("ElementSPECIAL")) {
                try {
                    var component = new CircuitModel.ComponentData(Integer.parseInt(block.tokens().get("typ")),
                            block.tokens().getOrDefault("idStringDialog", ""), integer(block, "x"), integer(block, "y"),
                            integer(block, "orientierung"));
                    component.setParsedInput(true);
                    component.setSavedBlock(block);
                    component.getImportTokens().putAll(block.tokens());
                    component.setSourceLocation(model.getFilePath() + ": line " + (start + 1) + " " + tag);
                    model.getSpecialComponents().add(component);
                } catch (RuntimeException error) {
                    // The parser already retains malformed-block diagnostics.
                }
            }
            for (var component : candidates) {
                if (!assigned.contains(component) && component.getName().equals(block.tokens().getOrDefault("idStringDialog", ""))) {
                    component.setSavedBlock(block);
                    component.getImportTokens().clear();
                    component.getImportTokens().putAll(block.tokens());
                    if (block.tokens().containsKey("uniqueObjectIdentifier")) {
                        try {
                            component.setParameter("uniqueObjectIdentifier", Long.parseLong(block.tokens().get("uniqueObjectIdentifier")));
                        } catch (NumberFormatException error) {
                            model.getImportIssues().add(new ImportIssue("MALFORMED_COMPONENT",
                                    tag.equals("ElementTHERM") ? "THERMAL" : tag.substring("Element".length()),
                                    component.getName(), "The saved component identifier is invalid.",
                                    component.getSourceLocation(), false));
                        }
                    }
                    assigned.add(component);
                    break;
                }
            }
            start = end;
        }
        var project = SavedCircuitBlock.parse("Project", global);
        model.getSavedSettings().putAll(project.tokens());
        // File-manager contents become owned resources, not executable text sections.
        project.orderedSections().stream().filter(section -> !section.tag().equals("GeckoFileManager"))
                .forEach(model.getSavedSections()::add);
        retainImporterIssues(model, global);
    }

    private static void retainImporterIssues(CircuitModel model, String[] global) throws CircuitParseException {
        for (int start = 0; start < global.length; start++) {
            String line = global[start].strip();
            if (!line.startsWith("<") || !line.endsWith(">") || line.startsWith("<\\")) continue;
            String tag = line.substring(1, line.length() - 1);
            int end = SavedCircuitBlock.closingLine(global, start, tag);
            if (tag.equals("ImportIssues")) {
                if (end == global.length) throw new CircuitParseException("Close the saved import diagnostics section.");
                for (int index = start + 1; index < end; index++) {
                    if (global[index].isBlank()) continue;
                    model.addConversionIssue(readImporterIssue(global[index]));
                }
            }
            start = end;
        }
    }

    private static ImportIssue readImporterIssue(String line) throws CircuitParseException {
        try {
            String[] fields = line.split(" ", -1);
            if (fields.length != 8 || !fields[0].equals("issue")) throw new IllegalArgumentException();
            for (int i = 1; i < fields.length; i++) {
                fields[i] = fields[i].equals("-") ? null
                        : new String(Base64.getDecoder().decode(fields[i]), StandardCharsets.UTF_8);
            }
            if (fields[1] == null || fields[1].isBlank() || fields[4] == null || fields[4].isBlank()
                    || !("true".equals(fields[6]) || "false".equals(fields[6]))) throw new IllegalArgumentException();
            return new ImportIssue(fields[1], fields[2], fields[3], fields[4],
                    fields[5], Boolean.parseBoolean(fields[6]), fields[7]);
        } catch (IllegalArgumentException error) {
            throw new CircuitParseException("The saved import diagnostic is invalid.", error);
        }
    }

    private static int integer(SavedCircuitBlock block, String token) {
        return Integer.parseInt(block.tokens().getOrDefault(token, "0"));
    }
}

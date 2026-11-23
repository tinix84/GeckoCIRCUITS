package gecko.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Captures resource bytes once. No execution path needs the source file. */
final class CircuitResourceReader {
    private CircuitResourceReader() { }

    static void retain(CircuitModel model, String[] lines, String[] structure,
            CircuitFileParser.ResourceAccess resourceAccess) {
        for (int start = 0; start < lines.length; start++) {
            if (!structure[start].trim().equals("<GeckoFileManager>")) continue;
            int managerEnd = SavedCircuitBlock.closingLine(lines, start, "GeckoFileManager");
            for (int i = start + 1; i < managerEnd; i++) {
                if (!lines[i].trim().equals("<GeckoFile>")) continue;
                int end = SavedCircuitBlock.closingLine(lines, i, "GeckoFile");
                var block = SavedCircuitBlock.parse("GeckoFile", Arrays.copyOfRange(lines, i + 1, end));
                try {
                    long id = Long.parseLong(block.tokens().get("hashValue"));
                    String path = block.tokens().getOrDefault("absPath", "");
                    String embedded = block.tokens().get("fileContents[]");
                    byte[] bytes;
                    if (embedded != null && !embedded.equals("null")) {
                        bytes = embeddedBytes(embedded);
                    } else if (resourceAccess == CircuitFileParser.ResourceAccess.LOCAL_FILES
                            && !block.tokens().getOrDefault("isExternal", "1").equals("0")) {
                        bytes = externalBytes(model, block);
                    } else {
                        throw new IOException("The supplied circuit does not include resource bytes");
                    }
                    String name = path.replace('\\', '/');
                    name = name.substring(name.lastIndexOf('/') + 1);
                    model.getResources().put(id, new CircuitResource(id, name, path, bytes, owners(model, block)));
                } catch (IOException | RuntimeException error) {
                    long id;
                    try { id = Long.parseLong(block.tokens().getOrDefault("hashValue", "0")); }
                    catch (NumberFormatException invalidId) { id = 0; }
                    model.getResourceFailures().put(id, new CircuitResourceFailure(id,
                            block.tokens().getOrDefault("absPath", ""), owners(model, block),
                            "The circuit resource cannot be captured: " + error.getMessage()));
                }
                i = end;
            }
            start = managerEnd;
        }
    }

    private static byte[] embeddedBytes(String text) {
        if (text == null || text.equals("null")) throw new IllegalArgumentException("Resource bytes are missing");
        String[] values = text.isBlank() ? new String[0] : text.trim().split("\\s+");
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = Byte.parseByte(values[i]);
        return bytes;
    }

    private static byte[] externalBytes(CircuitModel model, SavedCircuitBlock block) throws IOException {
        String separator = block.tokens().getOrDefault("fileSep", "/");
        String absolute = block.tokens().getOrDefault("absPath", "").replace(separator, java.io.File.separator);
        String relative = block.tokens().getOrDefault("relPath", "").replace(separator, java.io.File.separator);
        if (!absolute.isBlank() && Files.isRegularFile(Path.of(absolute))) return Files.readAllBytes(Path.of(absolute));
        if (!relative.isBlank() && model.getFilePath() != null) {
            Path source = Path.of(model.getFilePath()).toAbsolutePath();
            Path resolved = source.getParent().resolve(relative).normalize();
            if (Files.isRegularFile(resolved)) return Files.readAllBytes(resolved);
        }
        throw new IOException("Resource file is missing: " + absolute);
    }

    private static List<Long> owners(CircuitModel model, SavedCircuitBlock block) {
        String text = block.tokens().getOrDefault("usageList[]", block.sections().getOrDefault("usageList", ""));
        var owners = new ArrayList<Long>();
        for (String item : text.split("[\\s/]+")) {
            if (item.isBlank() || item.equals("null")) continue;
            try {
                owners.add(Long.parseLong(item));
            } catch (NumberFormatException error) {
                // Versions before 1.62 bind resource owners by component name.
                for (var component : components(model)) {
                    if (component.getName().equals(item) && component.getSavedBlock() != null) {
                        owners.add(component.getSavedBlock().uniqueObjectIdentifier());
                    }
                }
            }
        }
        return owners;
    }

    private static List<CircuitModel.ComponentData> components(CircuitModel model) {
        var result = new ArrayList<>(model.getCircuitComponents());
        result.addAll(model.getControlComponents());
        result.addAll(model.getThermalComponents());
        result.addAll(model.getSpecialComponents());
        return result;
    }
}

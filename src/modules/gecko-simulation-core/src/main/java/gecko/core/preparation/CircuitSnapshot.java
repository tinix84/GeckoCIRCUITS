package gecko.core.preparation;

import gecko.core.io.CircuitModel;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Objects;

/** Owns a circuit. Every inspection returns a detached model. */
public final class CircuitSnapshot {
    private final CircuitModel model;
    CircuitSnapshot(CircuitModel source) { model = copy(Objects.requireNonNull(source, "Circuit is required")); }
    /**
     * Returns a detached inspection model, including detached arrays, maps, and connections.
     * Changes to this model cannot change the snapshot or another inspection view.
     *
     * @return a writable copy of the captured circuit
     */
    public CircuitModel toModel() { return copy(model); }

    private static CircuitModel copy(CircuitModel source) {
        var target = new CircuitModel();
        target.setSimulationDuration(source.getSimulationDuration());
        target.setTimeStep(source.getTimeStep());
        target.setPreSimulationTime(source.getPreSimulationTime());
        target.setPreSimulationTimeStep(source.getPreSimulationTimeStep());
        target.setPauseTime(source.getPauseTime());
        target.setSolverType(source.getSolverType());
        target.setFilePath(source.getFilePath());
        target.setFileVersion(source.getFileVersion());
        target.setUniqueFileId(source.getUniqueFileId());
        target.setCreationDate(source.getCreationDate());
        target.setDisplayPixels(source.getDisplayPixels());
        target.setFontSize(source.getFontSize());
        target.setFontType(source.getFontType());
        target.setWindowWidth(source.getWindowWidth());
        target.setWindowHeight(source.getWindowHeight());
        target.setScripterCode(source.getScripterCode());
        target.setScripterImports(source.getScripterImports());
        target.setScripterDeclarations(source.getScripterDeclarations());
        target.getOptimizerParameters().putAll(source.getOptimizerParameters());
        target.getImportIssues().addAll(source.getImportIssues());
        if (source.getDataContainerSignals() != null) target.setDataContainerSignals(source.getDataContainerSignals().clone());
        copyComponents(source.getCircuitComponents(), target.getCircuitComponents());
        copyComponents(source.getControlComponents(), target.getControlComponents());
        copyComponents(source.getThermalComponents(), target.getThermalComponents());
        for (var connection : source.getConnections()) {
            target.addConnection(new CircuitModel.ConnectionData(connection.getType(),
                    (int[][]) copyValue(connection.getPoints()), connection.getLabel()));
        }
        return target;
    }

    private static void copyComponents(List<CircuitModel.ComponentData> source, List<CircuitModel.ComponentData> target) {
        for (var component : source) {
            int[] position = component.getPosition();
            var copy = new CircuitModel.ComponentData(component.getType(), component.getName(), position[0], position[1], component.getOrientation());
            component.getParameters().forEach((key, value) -> copy.setParameter(key, copyValue(value)));
            copy.setTerminalXLabels(component.getTerminalXLabels().clone());
            copy.setTerminalYLabels(component.getTerminalYLabels().clone());
            copy.setParameterStrings(component.getParameterStrings().clone());
            copy.setNumericParameters(component.getNumericParameters().clone());
            copy.setParsedInput(component.isParsedInput());
            copy.getImportTokens().putAll(component.getImportTokens());
            copy.setConfigurationNormalized(component.isConfigurationNormalized());
            copy.setImportedParameterCount(component.getImportedParameterCount());
            copy.setSourceLocation(component.getSourceLocation());
            target.add(copy);
        }
    }

    private static Object copyValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Enum<?> || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) return value;
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            for (int i = 0; i < length; i++) Array.set(copy, i, copyValue(Array.get(value, i)));
            return copy;
        }
        if (value instanceof Map<?, ?> values) {
            var copy = new LinkedHashMap<>();
            values.forEach((key, item) -> copy.put(copyValue(key), copyValue(item)));
            return copy;
        }
        if (value instanceof List<?> values) {
            var copy = new ArrayList<>();
            values.forEach(item -> copy.add(copyValue(item)));
            return copy;
        }
        if (value instanceof Set<?> values) {
            var copy = new LinkedHashSet<>();
            values.forEach(item -> copy.add(copyValue(item)));
            return copy;
        }
        throw new IllegalArgumentException("Unsupported mutable circuit value: " + value.getClass().getName());
    }
}

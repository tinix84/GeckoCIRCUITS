package gecko.rest.model.education;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LabAssignment(UUID id, Definition definition, Instant createdAt) {
    public record Definition(@NotBlank @Size(max = 128) String title, double duration, double timeStep,
                             @NotEmpty @Size(max = 32) List<@NotNull @Valid Check> checks) {}

    public record Check(@NotBlank @Size(max = 128) String signal, @NotNull Metric metric,
                        double t0, double t1, double target, double tolerance) {}

    public enum Metric {
        @JsonProperty("mean") MEAN,
        @JsonProperty("rms") RMS,
        @JsonProperty("ripple_pp") RIPPLE_PP,
        @JsonProperty("thd") THD,
        @JsonProperty("max") MAX,
        @JsonProperty("min") MIN
    }

    public record Submission(UUID id, UUID assignmentId, String student, Instant submittedAt,
                             double score, List<CheckResult> checks) {}

    public record CheckResult(Check check, Double value, boolean passed, String error) {}
}

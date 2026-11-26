package gecko.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gecko.core.signal.TimedSignal;
import gecko.rest.config.ApiIdentity;
import gecko.rest.config.LabIdentity.CourseUser;
import gecko.rest.model.SimulationRequest;
import gecko.rest.model.education.LabAssignment;
import gecko.rest.model.education.LabAssignment.Check;
import gecko.rest.model.education.LabAssignment.CheckResult;
import gecko.rest.model.education.LabAssignment.Definition;
import gecko.rest.model.education.LabAssignment.Submission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "gecko.education.enabled", havingValue = "true")
public class LabAssignmentService {
    private static final int MAX_CIRCUIT_BYTES = 1024 * 1024;
    private final SimulationService simulations;
    private final AnalysisService analysis;
    private final ObjectMapper json;
    private final Clock clock;
    private final Path root;

    public LabAssignmentService(SimulationService simulations, AnalysisService analysis, ObjectMapper json,
            Clock clock, @Value("${gecko.education.directory:gecko-education}") Path root) {
        this.simulations = simulations;
        this.analysis = analysis;
        this.json = json;
        this.clock = clock;
        this.root = root;
    }

    public LabAssignment create(CourseUser instructor, Definition definition, byte[] reference) throws IOException {
        validate(definition);
        LabAssignment assignment = new LabAssignment(UUID.randomUUID(), definition, clock.instant());
        if (evaluate(instructor.tenant(), definition, reference).stream().anyMatch(result -> !result.passed())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The reference circuit must pass each check.");
        }
        writeNew(directory(instructor.tenant(), assignment.id()).resolve("assignment.json"),
                new StoredAssignment(assignment, reference));
        return assignment;
    }

    public LabAssignment get(String tenant, UUID id) throws IOException {
        Path file = directory(tenant, id).resolve("assignment.json");
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found");
        }
        return json.readValue(file.toFile(), StoredAssignment.class).assignment();
    }

    public List<LabAssignment> assignments(String tenant) throws IOException {
        Path tenantDirectory = root.resolve(ApiIdentity.keyId(tenant));
        if (!Files.isDirectory(tenantDirectory)) return List.of();
        try (var files = Files.newDirectoryStream(tenantDirectory)) {
            var assignments = new ArrayList<LabAssignment>();
            for (Path file : files) {
                Path stored = file.resolve("assignment.json");
                if (Files.isRegularFile(stored)) {
                    assignments.add(json.readValue(stored.toFile(), StoredAssignment.class).assignment());
                }
            }
            assignments.sort(Comparator.comparing(LabAssignment::createdAt).thenComparing(LabAssignment::id));
            return assignments;
        }
    }

    public Submission submit(CourseUser student, UUID assignmentId, byte[] circuit) throws IOException {
        LabAssignment assignment = get(student.tenant(), assignmentId);
        List<CheckResult> results = evaluate(student.tenant(), assignment.definition(), circuit);
        double score = (double) results.stream().mapToInt(CheckResult::score).sum() / results.size();
        var submission = new Submission(UUID.randomUUID(), assignmentId, student.subject(), clock.instant(), score, results);
        writeNew(directory(student.tenant(), assignmentId).resolve("submissions").resolve(submission.id() + ".json"),
                submission);
        return submission;
    }

    public List<Submission> submissions(CourseUser instructor, UUID assignmentId, String student) throws IOException {
        get(instructor.tenant(), assignmentId);
        Path directory = directory(instructor.tenant(), assignmentId).resolve("submissions");
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            var submissions = new ArrayList<Submission>();
            for (Path file : files) {
                Submission submission = json.readValue(file.toFile(), Submission.class);
                if (student == null || student.equals(submission.student())) submissions.add(submission);
            }
            submissions.sort(Comparator.comparing(Submission::submittedAt).thenComparing(Submission::id));
            return submissions;
        }
    }

    private List<CheckResult> evaluate(String tenant, Definition definition, byte[] circuit) throws IOException {
        if (circuit.length == 0 || circuit.length > MAX_CIRCUIT_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload an .ipes circuit up to 1 MiB.");
        }
        byte[] input;
        try {
            input = ResearchBundleService.unpackCircuit(circuit);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot read the submitted circuit.", e);
        }
        Map<String, double[]> waveforms = simulations.runEmbeddedCircuit(input,
                new SimulationRequest(null, definition.duration(), definition.timeStep()), tenant);
        return definition.checks().stream()
                .map(check -> evaluate(check, waveforms, definition.timeStep())).toList();
    }

    private CheckResult evaluate(Check check, Map<String, double[]> waveforms, double timeStep) {
        double[] values = waveforms.get(check.signal());
        if (values == null) return new CheckResult(check, null, false, "Signal is missing.");
        try {
            double[] time = waveforms.get("time");
            if (time != null && time.length >= 2 && time.length == values.length) {
                int last = time.length - 1;
                // Use the nominal next step to account for accumulated clock roundoff.
                double steps = Math.rint((time[last] - time[0]) / timeStep);
                double nextStep = Math.fma(steps + 1, timeStep, time[0]);
                if (check.t1() > time[last] && check.t1() <= Math.nextUp(nextStep)) {
                    time = Arrays.copyOf(time, last + 2);
                    values = Arrays.copyOf(values, last + 2);
                    time[last + 1] = check.t1();
                    values[last + 1] = values[last];
                }
            }
            var signal = new TimedSignal(time, values).window(check.t0(), check.t1());
            var metrics = analysis.computeCharacteristics(signal, check.signal());
            double value = switch (check.metric()) {
                case MEAN -> metrics.getAverage();
                case RMS -> metrics.getRms();
                case RIPPLE_PP -> metrics.getPeakToPeak();
                case THD -> metrics.getThd();
                case MAX -> metrics.getMax();
                case MIN -> metrics.getMin();
            };
            if (!Double.isFinite(value)) return new CheckResult(check, null, false, "Metric is undefined.");
            return new CheckResult(check, value, Math.abs(value - check.target()) <= check.tolerance(), null);
        } catch (IllegalArgumentException | ResponseStatusException e) {
            return new CheckResult(check, null, false, "The metric cannot be calculated in this window.");
        }
    }

    private static void validate(Definition definition) {
        if (!positive(definition.duration()) || !positive(definition.timeStep())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a finite, positive duration and time step.");
        }
        for (Check check : definition.checks()) {
            if (!(check.t0() >= 0 && check.t0() < check.t1() && check.t1() <= definition.duration())
                    || !Double.isFinite(check.target()) || !Double.isFinite(check.tolerance()) || check.tolerance() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the time window, target, and tolerance.");
            }
        }
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private Path directory(String tenant, UUID id) {
        return root.resolve(ApiIdentity.keyId(tenant)).resolve(id.toString());
    }

    /** Publishes a complete JSON record in one filesystem operation. */
    private void writeNew(Path file, Object record) throws IOException {
        Files.createDirectories(file.getParent());
        Path pending = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".pending");
        Files.write(pending, json.writeValueAsBytes(record), StandardOpenOption.CREATE_NEW);
        Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE);
    }

    private record StoredAssignment(LabAssignment assignment, byte[] referenceCircuit) {}
}

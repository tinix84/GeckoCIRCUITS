package gecko.rest.controller;

import gecko.rest.config.LabIdentity;
import gecko.rest.model.education.LabAssignment;
import gecko.rest.model.education.LabAssignment.Definition;
import gecko.rest.model.education.LabAssignment.Submission;
import gecko.rest.service.LabAssignmentService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/assignments")
@ConditionalOnProperty(name = "gecko.education.enabled", havingValue = "true")
public class LabAssignmentController {
    private final LabAssignmentService labs;
    private final LabIdentity identity;

    public LabAssignmentController(LabAssignmentService labs, LabIdentity identity) {
        this.labs = labs;
        this.identity = identity;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public LabAssignment create(@Valid @RequestPart("assignment") Definition definition,
                                @RequestPart("file") MultipartFile reference, Authentication caller) throws IOException {
        return labs.create(identity.requireCourseUser(caller), definition, reference.getBytes());
    }

    @PostMapping(value = "/{id}/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Submission submit(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                             Authentication caller) throws IOException {
        return labs.submit(identity.requireCourseUser(caller), id, file.getBytes());
    }

    @GetMapping("/{id}/submissions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Submission> list(@PathVariable UUID id, @RequestParam(required = false) String student,
                                 Authentication caller) throws IOException {
        return labs.submissions(identity.requireCourseUser(caller), id, student);
    }
}

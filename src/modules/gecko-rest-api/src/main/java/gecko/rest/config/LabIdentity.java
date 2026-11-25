package gecko.rest.config;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Uses the authenticated REST tenant as the lab course identity. */
@Component
public class LabIdentity {
    public CourseUser requireCourseUser(Authentication authentication) {
        String tenant = ApiIdentity.tenant(authentication);
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Assign this caller to a lab tenant.");
        }
        return new CourseUser(tenant, authentication.getName());
    }

    /** The tenant and stable subject from a trusted authentication path. */
    public record CourseUser(String tenant, String subject) { }
}

package gecko.rest.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Resolves a course identity from administrator-assigned API credentials. */
@Component
public class LabIdentity {
    private final ApiKeyProperties apiKeys;
    private final Map<String, String> tenants;

    public LabIdentity(ApiKeyProperties apiKeys, Environment environment) {
        this.apiKeys = apiKeys;
        this.tenants = Binder.get(environment).bind("gecko.api.tenants", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }

    public CourseUser requireCourseUser(Authentication authentication) {
        String keyId = authentication == null ? "" : authentication.getName();
        String tenant = tenants.get(keyId);
        if (!apiKeys.isAuthEnabled() || tenant == null || tenant.isBlank()
                || apiKeys.getKeys().stream().map(ApiIdentity::keyId).noneMatch(keyId::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Assign this caller to a lab tenant.");
        }
        return new CourseUser(tenant, keyId);
    }

    /** The tenant and stable subject from a trusted authentication path. */
    public record CourseUser(String tenant, String subject) {}
}

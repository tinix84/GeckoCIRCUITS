package gecko.rest.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Maps credentials to stable identifiers without retaining raw credentials. */
public final class ApiIdentity {
    private ApiIdentity() { }

    public static String keyId(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    static UsernamePasswordAuthenticationToken forKey(String key) {
        return forKey(key, new ApiKeyProperties());
    }

    static UsernamePasswordAuthenticationToken forKey(String key, ApiKeyProperties properties) {
        String subject = key == null ? "open" : keyId(key);
        var role = !properties.isAuthEnabled() ? ApiKeyProperties.Role.ADMIN : properties.roleFor(subject);
        return authenticate(subject, properties.tenantFor(subject),
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    static UsernamePasswordAuthenticationToken authenticate(String subject, String tenant,
                                                              List<? extends GrantedAuthority> authorities) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(subject, null, authorities);
        if (tenant != null) authentication.setDetails(new Tenant(tenant));
        return authentication;
    }

    public static String tenant(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getDetails() instanceof Tenant tenant ? tenant.id() : null;
    }

    public static String currentTenant() {
        return tenant(SecurityContextHolder.getContext().getAuthentication());
    }

    public static String requireTenant(boolean enabled) {
        if (!enabled) return null;
        String tenant = currentTenant();
        if (tenant == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Tenant identity required.");
        }
        return tenant;
    }

    private record Tenant(String id) { }
}

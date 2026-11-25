package gecko.rest.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gecko.education.lti")
public record LtiProperties(URI baseUrl, Path signingJwk, List<Registration> registrations, boolean allowLoopbackHttp) {
    public record Registration(String issuer, String clientId, Set<String> deploymentIds,
                               URI authorizationUrl, URI jwksUrl, URI tokenUrl, String tokenAudience,
                               Set<URI> returnOrigins) {}
}

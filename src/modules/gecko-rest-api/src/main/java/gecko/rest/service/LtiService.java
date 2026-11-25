package gecko.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import gecko.rest.config.ApiIdentity;
import gecko.rest.config.ApiKeyProperties.Role;
import gecko.rest.config.LabIdentity.CourseUser;
import gecko.rest.config.LtiProperties;
import gecko.rest.config.LtiProperties.Registration;
import gecko.rest.model.education.LabAssignment;
import gecko.rest.model.education.LabAssignment.Submission;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import static gecko.rest.service.LtiPlatform.*;

@Service
@EnableConfigurationProperties(LtiProperties.class)
@ConditionalOnProperty(name = {"gecko.education.enabled", "gecko.education.lti.enabled"}, havingValue = "true")
public class LtiService {
    private static final String MEMBERSHIP = "http://purl.imsglobal.org/vocab/lis/v2/membership#";
    private static final Duration LOGIN_TTL = Duration.ofMinutes(5);
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final int MAX_ACTIVE = 4096;

    private final List<LtiPlatform> platforms = new ArrayList<>();
    private final Map<String, Timed<LoginState>> logins = new HashMap<>();
    private final Map<String, Timed<Session>> sessions = new HashMap<>();
    private final Clock clock;
    private final ObjectMapper json;
    private final LabAssignmentService labs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(NETWORK_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final RSAKey key;
    private final URI launch;
    private final boolean allowLoopbackHttp;

    public LtiService(LtiProperties properties, Clock clock, ObjectMapper json, LabAssignmentService labs)
            throws IOException, ParseException {
        this.clock = clock;
        this.json = json;
        this.labs = labs;
        this.allowLoopbackHttp = properties.allowLoopbackHttp();
        URI base = requireUrl(properties.baseUrl(), allowLoopbackHttp);
        if (base.getRawQuery() != null) throw new IllegalArgumentException("Remove the query from the LTI base URL.");
        launch = URI.create(base.toString().replaceFirst("/$", "") + "/api/v1/lti/launch");
        key = RSAKey.parse(Files.readString(Objects.requireNonNull(properties.signingJwk())));
        if (!key.isPrivate() || key.getKeyID() == null || key.getModulus().decodeToBigInteger().bitLength() < 2048) {
            throw new IllegalArgumentException("Configure a private RSA JWK of at least 2048 bits with a key ID.");
        }
        if (properties.registrations() == null || properties.registrations().isEmpty()) {
            throw new IllegalArgumentException("Configure an LTI platform registration.");
        }
        var clientIds = new HashSet<List<String>>();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(NETWORK_TIMEOUT);
        var keysHttp = new RestTemplate(requests);
        for (Registration entry : properties.registrations()) {
            requireUrl(URI.create(identifier(entry.issuer())), allowLoopbackHttp);
            requireUrl(entry.authorizationUrl(), allowLoopbackHttp);
            requireUrl(entry.jwksUrl(), allowLoopbackHttp);
            requireUrl(entry.tokenUrl(), allowLoopbackHttp);
            if (!clientIds.add(List.of(entry.issuer(), identifier(entry.clientId())))
                    || entry.deploymentIds() == null || entry.deploymentIds().isEmpty()
                    || entry.returnOrigins() == null || entry.returnOrigins().isEmpty()) {
                throw new IllegalArgumentException("Configure unique platform clients, deployments, and return origins.");
            }
            entry.deploymentIds().forEach(LtiService::identifier);
            var origins = entry.returnOrigins().stream().map(url -> requireUrl(url, allowLoopbackHttp))
                    .map(LtiPlatform::origin).collect(Collectors.toUnmodifiableSet());
            var decoder = NimbusJwtDecoder.withJwkSetUri(entry.jwksUrl().toString()).restOperations(keysHttp).build();
            platforms.add(new LtiPlatform(entry, origins, decoder));
        }
    }

    public Map<String, Object> publicKeys() {
        return Map.of("keys", List.of(key.toPublicJWK().toJSONObject()));
    }

    public Login beginLogin(String issuer, String clientId, String hint, String messageHint, String target) {
        var matches = platforms.stream().filter(p -> p.registration().issuer().equals(issuer))
                .filter(p -> clientId == null || p.registration().clientId().equals(clientId)).toList();
        if (matches.size() != 1 || !launch.toString().equals(target) || hint == null || hint.isBlank()) {
            throw unauthorized();
        }
        LtiPlatform platform = matches.getFirst();
        String state = random();
        String nonce = random();
        String browser = random();
        var redirect = UriComponentsBuilder.fromUriString(platform.registration().authorizationUrl().toString())
                .queryParam("scope", "openid").queryParam("response_type", "id_token")
                .queryParam("response_mode", "form_post").queryParam("prompt", "none")
                .queryParam("client_id", "{client}").queryParam("redirect_uri", "{redirect}")
                .queryParam("login_hint", "{hint}").queryParam("state", state).queryParam("nonce", nonce);
        var values = new HashMap<>(Map.of("client", platform.registration().clientId(), "redirect", target, "hint", hint));
        if (messageHint != null) {
            redirect.queryParam("lti_message_hint", "{message}");
            values.put("message", messageHint);
        }
        synchronized (logins) {
            store(logins, state, new LoginState(platform, nonce, browser), LOGIN_TTL);
        }
        return new Login(redirect.encode().buildAndExpand(values).toUri(), state, browser);
    }

    public OpenedSession launch(String state, String browser, String idToken) {
        if (state == null || browser == null || idToken == null || idToken.length() > 32768) throw unauthorized();
        LoginState login;
        synchronized (logins) {
            Timed<LoginState> pending = logins.get(state);
            if (pending == null || !clock.instant().isBefore(pending.expires())
                    || !pending.value().browser().equals(browser)) throw unauthorized();
            login = logins.remove(state).value();
        }
        Session session;
        try {
            session = verifiedSession(login, login.platform().decoder().decode(idToken));
        } catch (JwtException | IllegalArgumentException ex) {
            throw unauthorized();
        }
        String id = random();
        synchronized (sessions) {
            store(sessions, id, session, SESSION_TTL);
        }
        return new OpenedSession(id, session);
    }

    private Session verifiedSession(LoginState login, Jwt token) {
        Registration registration = login.platform().registration();
        List<String> audiences = token.getAudience();
        String party = token.getClaimAsString("azp");
        Instant now = clock.instant();
        if (!registration.issuer().equals(token.getClaimAsString("iss")) || audiences == null
                || !audiences.contains(registration.clientId()) || (audiences.size() > 1 && party == null)
                || (party != null && !registration.clientId().equals(party))
                || !login.nonce().equals(token.getClaimAsString("nonce")) || token.getExpiresAt() == null
                || !now.isBefore(token.getExpiresAt()) || token.getIssuedAt() == null
                || token.getIssuedAt().isBefore(now.minus(LOGIN_TTL)) || token.getIssuedAt().isAfter(now.plusSeconds(60))
                || !launch.toString().equals(token.getClaimAsString(CLAIM + "target_link_uri"))
                || !"1.3.0".equals(token.getClaimAsString(CLAIM + "version"))) throw unauthorized();

        String deployment = identifier(token.getClaimAsString(CLAIM + "deployment_id"));
        if (!registration.deploymentIds().contains(deployment)) throw unauthorized();
        String subject = identifier(token.getSubject());
        String course = identifier(claim(token, CLAIM + "context").get("id"));
        String tenant = tenantFor(registration.issuer(), registration.clientId(), deployment, course);
        CourseUser user = new CourseUser(tenant, ApiIdentity.keyId(tenant + "\n" + subject));
        Role role = role(token.getClaimAsStringList(CLAIM + "roles"));
        String type = token.getClaimAsString(CLAIM + "message_type");
        if ("LtiDeepLinkingRequest".equals(type) && role == Role.ADMIN) {
            Map<String, Object> settings = claim(token, DEEP + "deep_linking_settings");
            if (!(settings.get("accept_types") instanceof List<?> types) || !types.contains("ltiResourceLink")) {
                throw unauthorized();
            }
            URI returnUrl = login.platform().returnUrl(identifier(settings.get("deep_link_return_url")), allowLoopbackHttp);
            return new Session(user, role, login.platform(), deployment, subject, random(),
                    returnUrl, settings.get("data") instanceof String data ? data : null, null, null);
        }
        if (!"LtiResourceLinkRequest".equals(type)) throw unauthorized();
        identifier(claim(token, CLAIM + "resource_link").get("id"));
        UUID assignment = UUID.fromString(identifier(claim(token, CLAIM + "custom").get("assignment_id")));
        Map<String, Object> endpoint = claim(token, AGS + "claim/endpoint");
        URI lineItem = endpoint.get("lineitem") instanceof String line && endpoint.get("scope") instanceof List<?> scopes
                && scopes.contains(AGS + "scope/score") ? login.platform().returnUrl(line, allowLoopbackHttp) : null;
        return new Session(user, role, login.platform(), deployment, subject, random(), null, null, assignment, lineItem);
    }

    public static String tenantFor(String issuer, String clientId, String deployment, String course) {
        return "lti-" + ApiIdentity.keyId(issuer + "\n" + clientId + "\n" + deployment + "\n" + course);
    }

    public Session requireSession(String id, String csrf) {
        if (id == null || csrf == null) throw unauthorized();
        synchronized (sessions) {
            Timed<Session> active = sessions.get(id);
            if (active == null || !clock.instant().isBefore(active.expires())
                    || !active.value().csrf().equals(csrf)) throw unauthorized();
            return active.value();
        }
    }

    public List<LabAssignment> assignments(Session session) throws IOException {
        if (session.role() != Role.ADMIN || session.returnUrl() == null) throw unauthorized();
        return labs.assignments(session.user().tenant());
    }

    public LabAssignment assignment(Session session) throws IOException {
        if (session.assignmentId() == null) throw unauthorized();
        return labs.get(session.user().tenant(), session.assignmentId());
    }

    public String deepLink(Session session, UUID assignmentId) throws IOException {
        if (session.role() != Role.ADMIN || session.returnUrl() == null) throw unauthorized();
        LabAssignment assignment = labs.get(session.user().tenant(), assignmentId);
        return session.platform().deepLink(assignment, launch, session.deployment(), session.returnData(), clock.instant(), key);
    }

    public Grade submit(Session session, byte[] circuit) throws IOException {
        if (session.assignmentId() == null || session.role() != Role.USER) throw unauthorized();
        Submission submission = labs.submit(session.user(), session.assignmentId(), circuit);
        if (session.lineItem() == null) return new Grade(submission, false, false);
        boolean delivered;
        try {
            session.platform().postScore(http, json, key, clock.instant(), session.platformSubject(),
                    session.lineItem(), submission);
            delivered = true;
        } catch (IOException e) {
            delivered = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            delivered = false;
        }
        return new Grade(submission, true, delivered);
    }

    private static Role role(List<String> roles) {
        if (roles == null) throw unauthorized();
        if (roles.contains(MEMBERSHIP + "Instructor")) return Role.ADMIN;
        if (roles.contains(MEMBERSHIP + "Learner")) return Role.USER;
        throw unauthorized();
    }

    private static Map<String, Object> claim(Jwt jwt, String name) {
        Map<String, Object> value = jwt.getClaimAsMap(name);
        return value == null ? Map.of() : value;
    }

    private static String identifier(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 4096
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The LTI identifier is invalid.");
        }
        return text;
    }

    private <T> void store(Map<String, Timed<T>> store, String id, T value, Duration ttl) {
        Instant now = clock.instant();
        store.values().removeIf(entry -> !now.isBefore(entry.expires()));
        if (store.size() >= MAX_ACTIVE) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "LTI session capacity is full.");
        }
        store.put(id, new Timed<>(value, now.plus(ttl)));
    }

    private static String random() { return UUID.randomUUID().toString(); }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Validate the LTI launch.");
    }

    public record Login(URI redirect, String state, String browser) {}
    private record LoginState(LtiPlatform platform, String nonce, String browser) {}
    private record Timed<T>(T value, Instant expires) {}
    public record OpenedSession(String id, Session session) {}
    public record Session(CourseUser user, Role role, LtiPlatform platform, String deployment, String platformSubject,
                          String csrf, URI returnUrl, String returnData, UUID assignmentId, URI lineItem) {
        public java.util.Set<String> frameOrigins() { return platform.returnOrigins(); }
    }
    public record Grade(Submission submission, boolean hasGradeService, boolean delivered) {}
}

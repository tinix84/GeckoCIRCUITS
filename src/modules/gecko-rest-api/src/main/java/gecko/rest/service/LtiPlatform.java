package gecko.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import gecko.rest.config.LtiProperties.Registration;
import gecko.rest.model.education.LabAssignment;
import gecko.rest.model.education.LabAssignment.Submission;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.util.UriComponentsBuilder;

record LtiPlatform(Registration registration, Set<String> returnOrigins, NimbusJwtDecoder decoder) {
    static final String CLAIM = "https://purl.imsglobal.org/spec/lti/claim/";
    static final String DEEP = "https://purl.imsglobal.org/spec/lti-dl/claim/";
    static final String AGS = "https://purl.imsglobal.org/spec/lti-ags/";
    static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(10);

    URI returnUrl(String value, boolean allowLoopbackHttp) {
        URI uri = requireUrl(URI.create(value), allowLoopbackHttp);
        if (!returnOrigins.contains(origin(uri))) throw new IllegalArgumentException("Invalid LTI origin.");
        return uri;
    }

    String deepLink(LabAssignment assignment, URI launch, String deployment, String data, Instant now, RSAKey key) {
        var claims = toolClaims(registration.issuer(), now)
                .claim(CLAIM + "deployment_id", deployment)
                .claim(CLAIM + "message_type", "LtiDeepLinkingResponse").claim(CLAIM + "version", "1.3.0")
                .claim(DEEP + "content_items", List.of(Map.of("type", "ltiResourceLink",
                        "title", assignment.definition().title(), "url", launch.toString(),
                        "custom", Map.of("assignment_id", assignment.id().toString()),
                        "lineItem", Map.of("scoreMaximum", 1, "resourceId", assignment.id().toString(),
                                "label", assignment.definition().title()))));
        if (data != null) claims.claim(DEEP + "data", data);
        return sign(claims.build(), key);
    }

    void postScore(HttpClient http, ObjectMapper json, RSAKey key, Instant now, String userId,
                   URI lineItem, Submission submission) throws IOException, InterruptedException {
        String audience = registration.tokenAudience() == null ? registration.tokenUrl().toString() : registration.tokenAudience();
        String assertion = sign(toolClaims(audience, now).build(), key);
        String requestBody = encode(Map.of("grant_type", "client_credentials", "client_assertion", assertion,
                "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", "scope", AGS + "scope/score"));
        var tokenRequest = HttpRequest.newBuilder(registration.tokenUrl()).timeout(NETWORK_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        var response = http.send(tokenRequest, HttpResponse.BodyHandlers.ofInputStream());
        String token;
        try (var body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("The platform rejected the token request.");
            byte[] data = body.readNBytes(65537);
            if (data.length > 65536) throw new IOException("The platform token response is too large.");
            var payload = json.readTree(data);
            token = payload == null ? "" : payload.path("access_token").asText("");
            if (!token.matches("[A-Za-z0-9._~+/-]+=*")) throw new IOException("The platform access token is invalid.");
        }
        URI endpoint = UriComponentsBuilder.fromUri(lineItem).pathSegment("scores").build(true).toUri();
        String score = json.writeValueAsString(Map.of("userId", userId, "scoreGiven", submission.score(),
                "scoreMaximum", 1, "timestamp", submission.submittedAt().toString(), "activityProgress", "Completed",
                "gradingProgress", "FullyGraded"));
        var scoreRequest = HttpRequest.newBuilder(endpoint).timeout(NETWORK_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/vnd.ims.lis.v1.score+json")
                .POST(HttpRequest.BodyPublishers.ofString(score)).build();
        int status = http.send(scoreRequest, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status < 200 || status >= 300) throw new IOException("The platform rejected the score.");
    }

    private JWTClaimsSet.Builder toolClaims(String audience, Instant now) {
        return new JWTClaimsSet.Builder().issuer(registration.clientId()).subject(registration.clientId())
                .audience(audience).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                .jwtID(UUID.randomUUID().toString());
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the LTI message.", e);
        }
    }

    static URI requireUrl(URI uri, boolean allowLoopbackHttp) {
        if (uri == null || uri.getHost() == null || uri.getRawFragment() != null || uri.getUserInfo() != null
                || (!"https".equals(uri.getScheme()) && !(allowLoopbackHttp && "http".equals(uri.getScheme())
                && "127.0.0.1".equals(uri.getHost())))) {
            throw new IllegalArgumentException("Configure a valid HTTPS LTI URL.");
        }
        return uri;
    }

    static String origin(URI uri) {
        int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static String encode(Map<String, String> fields) {
        return fields.entrySet().stream().map(field -> field.getKey() + "="
                + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
}

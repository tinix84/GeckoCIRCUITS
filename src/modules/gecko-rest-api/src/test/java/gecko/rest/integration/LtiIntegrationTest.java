package gecko.rest.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import gecko.rest.config.ApiIdentity;
import gecko.rest.service.LtiService;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "gecko.education.enabled=true", "gecko.education.lti.enabled=true", "gecko.education.lti.allow-loopback-http=true",
    "gecko.api.auth-enabled=true", "gecko.api.keys=instructor"
})
@ActiveProfiles("tenant")
@AutoConfigureMockMvc
class LtiIntegrationTest {
    private static final String PATH = "/api/v1/lti";
    private static final String BASE = "https://tool.example.test/gecko";
    private static final String LAUNCH = BASE + PATH + "/launch";
    private static final String CLAIM = "https://purl.imsglobal.org/spec/lti/claim/";
    private static final String DEEP = "https://purl.imsglobal.org/spec/lti-dl/claim/";
    private static final String AGS = "https://purl.imsglobal.org/spec/lti-ags/";
    private static final String ROLE = "http://purl.imsglobal.org/vocab/lis/v2/membership#";
    private static final String CLIENT = "gecko-client";
    private static final String DEPLOYMENT = "deployment";
    private static final String COURSE = "buck-course";
    private static final RSAKey PLATFORM_KEY = key("platform");
    private static final RSAKey TOOL_KEY = key("tool");
    private static final MockWebServer PLATFORM = platform();
    private static final LinkedBlockingQueue<RecordedRequest> SERVICE_REQUESTS = new LinkedBlockingQueue<>();

    @TempDir static Path storage;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @DynamicPropertySource
    static void registrations(DynamicPropertyRegistry settings) throws IOException {
        String origin = endpoint("");
        String tenant = LtiService.tenantFor(endpoint("issuer"), CLIENT, DEPLOYMENT, COURSE);
        Path jwk = storage.resolve("tool.jwk");
        Files.writeString(jwk, TOOL_KEY.toJSONString());
        settings.add("gecko.education.directory", () -> storage.resolve("gradebook").toString());
        settings.add("gecko.api.tenants." + ApiIdentity.keyId("instructor"), () -> tenant);
        settings.add("gecko.education.lti.base-url", () -> BASE);
        settings.add("gecko.education.lti.signing-jwk", jwk::toString);
        String platform = "gecko.education.lti.registrations[0].";
        settings.add(platform + "issuer", () -> endpoint("issuer"));
        settings.add(platform + "client-id", () -> CLIENT);
        settings.add(platform + "deployment-ids[0]", () -> DEPLOYMENT);
        settings.add(platform + "authorization-url", () -> endpoint("authorize"));
        settings.add(platform + "jwks-url", () -> endpoint("jwks"));
        settings.add(platform + "token-url", () -> endpoint("token"));
        settings.add(platform + "return-origins[0]", () -> origin);
    }

    @AfterAll
    static void stopPlatform() throws IOException {
        PLATFORM.shutdown();
    }

    @Test
    void instructorLinksAnAssignmentAndLearnerGradeReachesThePlatform() throws Exception {
        String assignment = createAssignment();
        var linkLogin = login();
        var instructor = launch(linkLogin, token(linkLogin, "Instructor", "LtiDeepLinkingRequest",
                null, PLATFORM_KEY, Instant.now().plusSeconds(120)));
        assertTrue(instructor.getContentAsString().contains(assignment));
        assertTrue(instructor.getHeader("Content-Security-Policy").contains("frame-ancestors"));
        assertNull(instructor.getHeader("X-Frame-Options"));
        var link = mvc.perform(post(PATH + "/link").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .cookie(sessionCookie(instructor))
                        .param("csrf", value(instructor, "csrf")).param("assignmentId", assignment))
                .andExpect(status().isOk()).andReturn().getResponse();
        var linkToken = SignedJWT.parse(value(link, "JWT"));
        assertTrue(linkToken.verify(new RSASSAVerifier(TOOL_KEY.toRSAPublicKey())));
        var linkClaims = linkToken.getJWTClaimsSet();
        assertEquals(CLIENT, linkClaims.getIssuer());
        assertEquals(endpoint("issuer"), linkClaims.getAudience().getFirst());
        assertEquals("LtiDeepLinkingResponse", linkClaims.getStringClaim(CLAIM + "message_type"));
        assertEquals("return this data", linkClaims.getStringClaim(DEEP + "data"));
        assertTrue(linkClaims.getClaim(DEEP + "content_items").toString().contains(assignment));

        var learnerLogin = login();
        var learner = launch(learnerLogin, token(learnerLogin, "Learner", "LtiResourceLinkRequest",
                assignment, PLATFORM_KEY, Instant.now().plusSeconds(120)));
        assertTrue(learner.getContentAsString().contains("Submit circuit"));
        mvc.perform(multipart(PATH + "/submit").file(circuit()).cookie(sessionCookie(learner))
                        .param("csrf", "incorrect"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(PATH + "/submit").file(circuit()).cookie(sessionCookie(learner))
                        .param("csrf", value(learner, "csrf")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.submission.score").value(1.0))
                .andExpect(jsonPath("$.delivered").value(true));

        RecordedRequest tokenRequest = SERVICE_REQUESTS.poll(5, TimeUnit.SECONDS);
        assertNotNull(tokenRequest);
        assertEquals("/token", tokenRequest.getPath());
        String form = java.net.URLDecoder.decode(tokenRequest.getBody().readUtf8(), StandardCharsets.UTF_8);
        assertTrue(form.contains("scope=" + AGS + "scope/score"));
        var assertion = Pattern.compile("client_assertion=([^&]+)").matcher(form);
        assertTrue(assertion.find());
        var clientAssertion = SignedJWT.parse(assertion.group(1));
        assertTrue(clientAssertion.verify(new RSASSAVerifier(TOOL_KEY.toRSAPublicKey())));
        assertEquals(CLIENT, clientAssertion.getJWTClaimsSet().getSubject());

        RecordedRequest score = SERVICE_REQUESTS.poll(5, TimeUnit.SECONDS);
        assertNotNull(score);
        assertEquals("/lineitem/scores", score.getPath());
        assertEquals("Bearer grade-access", score.getHeader("Authorization"));
        var payload = json.readTree(score.getBody().readUtf8());
        assertEquals("lms-user", payload.path("userId").asText());
        assertEquals(1, payload.path("scoreGiven").asDouble());
        assertEquals(1, payload.path("scoreMaximum").asDouble());
        assertEquals("FullyGraded", payload.path("gradingProgress").asText());
        mvc.perform(get("/api/v1/assignments/{id}/submissions", assignment).header("X-API-Key", "instructor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void rejectsBadSignaturesExpiredTokensWrongNoncesAndReplays() throws Exception {
        var signedWrong = login();
        reject(signedWrong, token(signedWrong, "Instructor", "LtiDeepLinkingRequest", null,
                key("platform"), Instant.now().plusSeconds(120)));
        var expired = login();
        reject(expired, token(expired, "Instructor", "LtiDeepLinkingRequest", null,
                PLATFORM_KEY, Instant.now().minusSeconds(5)));
        var wrongNonce = login();
        reject(wrongNonce, token(new Login(wrongNonce.state(), "different", wrongNonce.browser()),
                "Instructor", "LtiDeepLinkingRequest", null, PLATFORM_KEY, Instant.now().plusSeconds(120)));
        var valid = login();
        String token = token(valid, "Instructor", "LtiDeepLinkingRequest", null, PLATFORM_KEY, Instant.now().plusSeconds(120));
        launch(valid, token);
        reject(valid, token);
    }

    private String createAssignment() throws Exception {
        String definition = """
                {"title":"Buck lab","duration":0.004,"timeStep":0.000001,"checks":[
                  {"signal":"u2","metric":"mean","t0":0.0029,"t1":0.0039,"target":18.82,"tolerance":0.3764}
                ]}
                """;
        var assignment = new MockMultipartFile("assignment", "definition.json", "application/json",
                definition.getBytes(StandardCharsets.UTF_8));
        byte[] response = mvc.perform(multipart("/api/v1/assignments").file(assignment).file(circuit())
                        .header("X-API-Key", "instructor"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        return json.readTree(response).path("id").asText();
    }

    private Login login() throws Exception {
        var response = mvc.perform(get(PATH + "/login").param("iss", endpoint("issuer"))
                        .param("target_link_uri", LAUNCH).param("login_hint", "hint").param("client_id", CLIENT))
                .andExpect(status().isSeeOther()).andReturn().getResponse();
        var query = UriComponentsBuilder.fromUriString(response.getHeader("Location")).build().getQueryParams();
        assertEquals("form_post", query.getFirst("response_mode"));
        return new Login(query.getFirst("state"), query.getFirst("nonce"), readCookie(response, "gecko_lti_state_"));
    }

    private MockHttpServletResponse launch(Login login, String token) throws Exception {
        return mvc.perform(post(PATH + "/launch").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("state", login.state()).param("id_token", token).cookie(login.browser()))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private void reject(Login login, String token) throws Exception {
        mvc.perform(post(PATH + "/launch").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("state", login.state()).param("id_token", token).cookie(login.browser()))
                .andExpect(status().isUnauthorized());
    }

    private static String token(Login login, String role, String type, String assignment, RSAKey key, Instant expires)
            throws Exception {
        var messages = new JWTClaimsSet.Builder().issuer(endpoint("issuer")).subject("lms-user").audience(CLIENT)
                .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(expires))
                .claim("nonce", login.nonce()).claim(CLAIM + "deployment_id", DEPLOYMENT)
                .claim(CLAIM + "message_type", type).claim(CLAIM + "version", "1.3.0")
                .claim(CLAIM + "target_link_uri", LAUNCH).claim(CLAIM + "context", Map.of("id", COURSE))
                .claim(CLAIM + "roles", List.of(ROLE + role));
        if (assignment == null) {
            messages.claim(DEEP + "deep_linking_settings", Map.of("accept_types", List.of("ltiResourceLink"),
                    "deep_link_return_url", endpoint("return"), "data", "return this data"));
        } else {
            messages.claim(CLAIM + "resource_link", Map.of("id", "link"))
                    .claim(CLAIM + "custom", Map.of("assignment_id", assignment))
                    .claim(AGS + "claim/endpoint", Map.of("lineitem", endpoint("lineitem"),
                            "scope", List.of(AGS + "scope/score")));
        }
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), messages.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static MockWebServer platform() {
        var server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return switch (request.getPath()) {
                    case "/jwks" -> jsonResponse(new JWKSet(PLATFORM_KEY.toPublicJWK()).toString());
                    case "/token" -> {
                        SERVICE_REQUESTS.add(request);
                        yield jsonResponse("{\"access_token\":\"grade-access\",\"token_type\":\"Bearer\"}");
                    }
                    case "/lineitem/scores" -> {
                        SERVICE_REQUESTS.add(request);
                        yield new MockResponse().setResponseCode(204);
                    }
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        try {
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
            return server;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static RSAKey key(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String endpoint(String path) {
        return PLATFORM.url("/" + path).newBuilder().host("127.0.0.1").build().toString();
    }

    private static MockMultipartFile circuit() throws IOException {
        return new MockMultipartFile("file", "buck.ipes", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                Files.readAllBytes(Path.of("../gecko-simulation-core/src/test/resources/ipes/buck_simple.ipes")));
    }

    private static Cookie readCookie(MockHttpServletResponse response, String prefix) {
        String pair = response.getHeaders("Set-Cookie").stream().filter(header -> header.startsWith(prefix))
                .findFirst().orElseThrow().split(";", 2)[0];
        String[] value = pair.split("=", 2);
        return new Cookie(value[0], value[1]);
    }

    private static Cookie sessionCookie(MockHttpServletResponse response) {
        return readCookie(response, "gecko_lti_session=");
    }

    private static String value(MockHttpServletResponse response, String field) throws Exception {
        var matcher = Pattern.compile("name=\"" + field + "\" value=\"([^\"]+)\"").matcher(response.getContentAsString());
        assertTrue(matcher.find(), field);
        return matcher.group(1);
    }

    private record Login(String state, String nonce, Cookie browser) {}
}

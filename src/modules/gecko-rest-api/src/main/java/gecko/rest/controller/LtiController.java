package gecko.rest.controller;

import gecko.rest.config.ApiKeyProperties.Role;
import gecko.rest.service.LtiService;
import gecko.rest.service.LtiService.Session;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

@RestController
@RequestMapping("/api/v1/lti")
@ConditionalOnProperty(name = {"gecko.education.enabled", "gecko.education.lti.enabled"}, havingValue = "true")
public class LtiController {
    private static final String SESSION = "gecko_lti_session";
    private static final String STATE = "gecko_lti_state_";
    private final LtiService lti;

    public LtiController(LtiService lti) {
        this.lti = lti;
    }

    @GetMapping("/jwks")
    public Map<String, Object> publicKeys() {
        return lti.publicKeys();
    }

    @RequestMapping(value = "/login", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> login(@RequestParam("iss") String issuer,
            @RequestParam(value = "client_id", required = false) String client,
            @RequestParam("login_hint") String hint,
            @RequestParam(value = "lti_message_hint", required = false) String messageHint,
            @RequestParam("target_link_uri") String target, HttpServletRequest request) {
        var login = lti.beginLogin(issuer, client, hint, messageHint, target);
        return ResponseEntity.status(303).location(login.redirect())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookie(STATE + login.state(), login.browser(), request, Duration.ofMinutes(5)))
                .build();
    }

    @PostMapping(value = "/launch", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> launch(@RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "id_token", required = false) String token, HttpServletRequest request) throws IOException {
        var opened = lti.launch(state, readCookie(request, STATE + state), token);
        Session session = opened.session();
        String body;
        if (session.returnUrl() != null) {
            var options = new StringBuilder();
            for (var assignment : lti.assignments(session)) {
                options.append("<option value=\"").append(assignment.id()).append("\">")
                        .append(htmlEscape(assignment.definition().title())).append("</option>");
            }
            body = "<h1>Choose an assignment</h1>";
            if (options.length() == 0) {
                body += "<p>Create an assignment in this course before adding a link.</p>"
                        + "<p>Course tenant <code>" + htmlEscape(session.user().tenant()) + "</code></p>";
            } else {
                body += "<form action=\"link\" method=\"post\">" + hidden("csrf", session.csrf())
                        + "<label>Assignment <select name=\"assignmentId\" required>" + options
                        + "</select></label><button type=\"submit\">Add to course</button></form>";
            }
        } else {
            var assignment = lti.assignment(session);
            body = "<h1>" + htmlEscape(assignment.definition().title()) + "</h1>";
            if (session.role() == Role.USER) {
                body += "<form action=\"submit\" method=\"post\" enctype=\"multipart/form-data\">"
                        + hidden("csrf", session.csrf())
                        + "<label>Circuit <input type=\"file\" name=\"file\" accept=\".ipes\" required></label>"
                        + "<button type=\"submit\">Submit circuit</button></form>";
            }
        }
        return html(session, Set.of())
                .header(HttpHeaders.SET_COOKIE, cookie(STATE + state, "", request, Duration.ZERO),
                        cookie(SESSION, opened.id(), request, Duration.ofMinutes(30)))
                .body(document(body));
    }

    @PostMapping(value = "/link", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> link(@RequestParam UUID assignmentId, @RequestParam String csrf,
                                        HttpServletRequest request) throws IOException {
        Session session = lti.requireSession(readCookie(request, SESSION), csrf);
        String signed = lti.deepLink(session, assignmentId);
        String url = session.returnUrl().toString();
        String body = "<h1>Return to the course</h1><form method=\"post\" action=\"" + htmlEscape(url)
                + "\">" + hidden("JWT", signed) + "<button type=\"submit\">Return to course</button></form>";
        return html(session, session.frameOrigins()).body(document(body));
    }

    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LtiService.Grade> submit(@RequestPart MultipartFile file, @RequestParam String csrf,
                                                     HttpServletRequest request) throws IOException {
        Session session = lti.requireSession(readCookie(request, SESSION), csrf);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(lti.submit(session, file.getBytes()));
    }

    private static ResponseEntity.BodyBuilder html(Session session, Set<String> formOrigins) {
        String forms = formOrigins.isEmpty() ? "'self'" : String.join(" ", formOrigins);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors "
                        + String.join(" ", session.frameOrigins()) + "; form-action " + forms);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName())).findFirst().map(Cookie::getValue).orElse(null);
    }

    private static String cookie(String name, String value, HttpServletRequest request, Duration ttl) {
        return ResponseCookie.from(name, value).secure(true).httpOnly(true).sameSite("None")
                .path(request.getContextPath() + "/api/v1/lti").maxAge(ttl).build().toString();
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + htmlEscape(value) + "\">";
    }

    private static String document(String body) {
        return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
                + "<title>GeckoCIRCUITS lab</title><main>" + body + "</main></html>";
    }
}

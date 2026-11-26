package gecko.rest.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies general and submission budgets in one atomic fixed-window admission. */
public final class RateLimitFilter extends OncePerRequestFilter {
    private static final Set<String> EXEMPT = Set.of("/api/health", "/api/v1/health", "/actuator/health", ApiKeyAuthFilter.PROMETHEUS_PATH,
            "/api-docs", "/v3/api-docs", "/swagger-ui", "/swagger-ui.html", "/ws", "/ws-raw");
    private final RateLimitProperties properties;
    private final Clock clock;
    private final RateLimitStore store;

    public RateLimitFilter(RateLimitProperties properties, Clock clock) {
        this(properties, clock, new RateLimitStore.InMemory());
    }

    RateLimitFilter(RateLimitProperties properties, Clock clock, RateLimitStore store) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = ApiKeyAuthFilter.requestPath(request);
        return EXEMPT.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER);
        String client = key == null || key.isBlank() ? "ip:" + request.getRemoteAddr() : "key:" + ApiIdentity.keyId(key);
        String path = ApiKeyAuthFilter.requestPath(request);
        boolean submission = request.getMethod().equals("POST")
                && (path.equals("/api/v1/simulations") || path.equals("/api/v1/simulations/batch"));
        Decision decision;
        try {
            decision = admit(client, submission);
        } catch (DataAccessException e) {
            logger.warn("Rate limit store unavailable", e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Service Unavailable\",\"message\":\"Rate limit store unavailable.\"}");
            return;
        }
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(decision.reset()));
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(decision.retryAfter()));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"Request limit exceeded.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private Decision admit(String client, boolean submission) {
        long now = clock.instant().getEpochSecond();
        int generalLimit = properties.getRateLimit();
        int simulationLimit = properties.getRateLimitSimulation();
        RateLimitStore.Admission admission = store.admit(client, now, submission,
                generalLimit, simulationLimit);
        int limit = submission ? simulationLimit : generalLimit;
        int generalRemaining = generalLimit - admission.general();
        int remaining = submission ? Math.min(generalRemaining, simulationLimit - admission.simulations())
                : generalRemaining;
        return new Decision(admission.allowed(), limit, Math.max(remaining, 0),
                admission.reset(), admission.retryAfter());
    }

    private record Decision(boolean allowed, int limit, int remaining, long reset, long retryAfter) { }
}

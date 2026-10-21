package com.ledgerx.api;

import com.ledgerx.client.Client;
import com.ledgerx.client.ClientRepository;
import com.ledgerx.ratelimit.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every request except health checks and the admin endpoint via the X-API-Key header, then applies
 * the client's rate limit.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String CLIENT_ATTRIBUTE = "ledgerx.client";

    private final ClientRepository clients;
    private final RateLimiter rateLimiter;
    private final Counter rejected;

    public ApiKeyAuthFilter(ClientRepository clients, RateLimiter rateLimiter, MeterRegistry registry) {
        this.clients = clients;
        this.rateLimiter = rateLimiter;
        this.rejected = Counter.builder("ledgerx.rate_limit.rejected")
                .description("Requests rejected with 429").register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/admin")
                || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");
        Optional<Client> client = apiKey == null || apiKey.isBlank()
                ? Optional.empty()
                : clients.findByApiKey(apiKey);
        if (client.isEmpty()) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Missing or invalid X-API-Key");
            return;
        }

        RateLimiter.Decision decision = rateLimiter.tryAcquire(client.get().id());
        if (!decision.allowed()) {
            rejected.increment();
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            writeProblem(response, 429, "Too Many Requests", "Rate limit exceeded; retry later");
            return;
        }

        request.setAttribute(CLIENT_ATTRIBUTE, client.get());
        chain.doFilter(request, response);
    }

    private static void writeProblem(HttpServletResponse response, int status, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getOutputStream().write(
                ("{\"title\":\"" + title + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }
}

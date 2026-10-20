package com.ledgerx.api;

import com.ledgerx.client.Client;
import com.ledgerx.client.ClientRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates every request except health checks and the admin endpoint via the X-API-Key header. */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String CLIENT_ATTRIBUTE = "ledgerx.client";

    private final ClientRepository clients;

    public ApiKeyAuthFilter(ClientRepository clients) {
        this.clients = clients;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");
        Optional<Client> client = apiKey == null || apiKey.isBlank()
                ? Optional.empty()
                : clients.findByApiKey(apiKey);
        if (client.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getOutputStream().write(
                    "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Missing or invalid X-API-Key\"}"
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        request.setAttribute(CLIENT_ATTRIBUTE, client.get());
        chain.doFilter(request, response);
    }
}

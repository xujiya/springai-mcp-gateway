package es.omarall.mcp.gateway;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import es.omarall.mcp.gateway.entity.ApiKey;
import es.omarall.mcp.gateway.service.ApiKeyService;

/**
 * API Key authentication filter.
 * <p>
 * Checks for API key in two locations (in priority order):
 * <ol>
 *   <li>{@code X-API-Key} header (configurable via ecso.mcp.api-key.header-name)</li>
 *   <li>{@code Authorization: Bearer ak-*} header (Bearer tokens starting with "ak-" are treated as API keys)</li>
 * </ol>
 * <p>
 * If a valid API key is found, sets a {@link ApiKeyAuthentication} in the SecurityContext,
 * allowing the request to proceed without JWT authentication.
 * <p>
 * If no API key is found, the filter passes through — the next filter (JWT) handles authentication.
 * This allows OAuth2 and AK modes to coexist (对标阿里云 dual auth model).
 */
@Slf4j
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final String headerName;
    private final boolean enabled;

    public ApiKeyAuthenticationFilter(
            ApiKeyService apiKeyService,
            @Value("${ecso.mcp.api-key.header-name:X-API-Key}") String headerName,
            @Value("${ecso.mcp.api-key.enabled:true}") boolean enabled) {
        this.apiKeyService = apiKeyService;
        this.headerName = headerName;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // Already authenticated? Skip
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawKey = resolveApiKey(request);
        if (rawKey == null) {
            // No API key present — pass through to JWT filter
            filterChain.doFilter(request, response);
            return;
        }

        // Validate API key
        ApiKey apiKey = apiKeyService.validate(rawKey);
        if (apiKey == null) {
            log.debug("Invalid API key from {}", request.getRemoteAddr());
            // Don't reject here — let the downstream JWT filter try
            // If JWT also fails, the entry point returns 401
            filterChain.doFilter(request, response);
            return;
        }

        log.debug("API key authenticated: name={}, scope={}", apiKey.getName(), apiKey.getServiceScope());

        // Set authentication in SecurityContext
        ApiKeyAuthentication auth = new ApiKeyAuthentication(apiKey);
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    /**
     * Resolve API key from request headers.
     * Priority: 1) X-API-Key header, 2) Authorization: Bearer ak-*
     */
    private String resolveApiKey(HttpServletRequest request) {
        // 1. Check dedicated header (X-API-Key)
        String key = request.getHeader(headerName);
        if (key != null && !key.isBlank()) {
            return key.trim();
        }

        // 2. Check Authorization: Bearer ak-*
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (token.startsWith("ak-")) {
                return token;
            }
        }

        return null;
    }

    /**
     * Authentication token representing a validated API key.
     * Carries the ApiKey entity so controllers can check service scope.
     */
    public static class ApiKeyAuthentication extends AbstractAuthenticationToken {

        private final ApiKey apiKey;

        public ApiKeyAuthentication(ApiKey apiKey) {
            super(List.of(
                    new SimpleGrantedAuthority("ROLE_API_KEY"),
                    new SimpleGrantedAuthority("SCOPE_mcp:read"),
                    new SimpleGrantedAuthority("SCOPE_mcp:write")));
            this.apiKey = apiKey;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null; // Never expose the key
        }

        @Override
        public Object getPrincipal() {
            return apiKey;
        }

        public ApiKey getApiKey() {
            return apiKey;
        }

        /**
         * Get the service scope from this API key.
         */
        public String getServiceScope() {
            return apiKey.getServiceScope();
        }
    }
}

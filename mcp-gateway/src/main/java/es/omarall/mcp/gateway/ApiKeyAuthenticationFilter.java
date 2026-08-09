package es.omarall.mcp.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * API Key authentication filter — dual auth modes (对标阿里云 AccessKey).
 * <p>
 * <b>Mode 1: HMAC Signed (recommended, 对标阿里云)</b>
 * <pre>
 * X-AccessKey-Id: ak-a1327ef38936cdc9e4c1
 * X-AccessKey-Signature: &lt;HMAC-SHA256(secret, stringToSign)&gt;
 * X-AccessKey-Timestamp: 2026-08-09T06:30:00Z
 * </pre>
 * The AccessKey Secret is <b>NEVER</b> transmitted — only the HMAC signature.
 * This is identical to Alibaba Cloud's AccessKey authentication.
 * <p>
 * <b>Mode 2: Bearer/Header (legacy, like Stripe)</b>
 * <pre>
 * X-API-Key: ak-a1327ef38936cdc9e4c1:sk-c0c396...
 * // OR
 * Authorization: Bearer ak-a1327ef38936cdc9e4c1:sk-c0c396...
 * </pre>
 * Format: {@code <AccessKeyId>:<AccessKeySecret>}. Less secure — secret transmitted
 * on every request. Simpler for scripts/CI.
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

        // Admin paths: skip entirely, let the controller handle auth
        String path = request.getServletPath();
        if (path != null && path.startsWith("/admin/")) {
            filterChain.doFilter(request, response);
            return;
        }

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

        // Try Mode 1: HMAC Signed request (对标阿里云)
        String accessKeyId = request.getHeader("X-AccessKey-Id");
        String signature = request.getHeader("X-AccessKey-Signature");

        if (accessKeyId != null && signature != null) {
            String timestamp = request.getHeader("X-AccessKey-Timestamp");
            String method = request.getMethod();
            String requestUri = request.getRequestURI();
            // Note: Body SHA-256 would need caching the request body
            // For MCP (JSON-RPC), the body is typically small
            String bodySha256 = null; // TODO: compute from cached request body

            ApiKey apiKey = apiKeyService.validateBySignature(
                    accessKeyId, signature, method, requestUri, timestamp, bodySha256);
            if (apiKey != null) {
                setAuthentication(apiKey, "HMAC");
                filterChain.doFilter(request, response);
                return;
            }
            // HMAC validation failed — fall through to JWT
            log.debug("HMAC signature validation failed for AccessKey ID: {}", accessKeyId);
        }

        // Try Mode 2: Bearer/Header (ak-id:sk-secret format)
        String rawKey = resolveApiKey(request);
        if (rawKey != null && rawKey.contains(":sk-")) {
            // Split: ak-xxx:sk-yyy
            int sepIdx = rawKey.indexOf(":sk-");
            String kid = rawKey.substring(0, sepIdx);
            String secret = rawKey.substring(sepIdx + 1);

            ApiKey apiKey = apiKeyService.validateBySecret(kid, secret);
            if (apiKey != null) {
                setAuthentication(apiKey, "BEARER");
                filterChain.doFilter(request, response);
                return;
            }
            log.debug("Bearer API key validation failed for AccessKey ID: {}", kid);
        }

        // No API key or invalid — pass through to JWT filter
        filterChain.doFilter(request, response);
    }

    /**
     * Resolve API key from request headers.
     * Looks for: 1) X-API-Key header, 2) Authorization: Bearer ak-*:sk-*
     */
    private String resolveApiKey(HttpServletRequest request) {
        // 1. Check dedicated header
        String key = request.getHeader(headerName);
        if (key != null && !key.isBlank() && key.contains(":sk-")) {
            return key.trim();
        }

        // 2. Check Authorization: Bearer ak-*:sk-*
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (token.contains(":sk-")) {
                return token;
            }
        }

        return null;
    }

    private void setAuthentication(ApiKey apiKey, String mode) {
        ApiKeyAuthentication auth = new ApiKeyAuthentication(apiKey, mode);
        SecurityContextHolder.getContext().setAuthentication(auth);
        log.debug("API key authenticated: name={}, scope={}, mode={}",
                apiKey.getName(), apiKey.getServiceScope(), mode);
    }

    /**
     * Authentication token representing a validated API key.
     */
    public static class ApiKeyAuthentication extends AbstractAuthenticationToken {

        private final ApiKey apiKey;
        private final String authMode; // "HMAC" or "BEARER"

        public ApiKeyAuthentication(ApiKey apiKey, String authMode) {
            super(List.of(
                    new SimpleGrantedAuthority("ROLE_API_KEY"),
                    new SimpleGrantedAuthority("SCOPE_mcp:read"),
                    new SimpleGrantedAuthority("SCOPE_mcp:write")));
            this.apiKey = apiKey;
            this.authMode = authMode;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return apiKey;
        }

        public ApiKey getApiKey() {
            return apiKey;
        }

        public String getServiceScope() {
            return apiKey.getServiceScope();
        }

        public String getAuthMode() {
            return authMode;
        }
    }
}

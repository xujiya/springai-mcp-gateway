package es.omarall.mcp.gateway;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Per-service WWW-Authenticate header for RFC 9728.
 * <p>
 * When a request to {@code /{serviceName}/mcp} gets 401, the WWW-Authenticate
 * header must point to the per-service Protected Resource Metadata endpoint:
 * <pre>
 * WWW-Authenticate: Bearer resource_metadata=http://host/mcp-gateway/{serviceName}/.well-known/oauth-protected-resource
 * </pre>
 * For the unified endpoint {@code /mcp}, falls back to the default PRM URL.
 */
public class ServiceAwareBearerEntryPoint implements AuthenticationEntryPoint {

    private final String mcpServerPublicUrl;

    public ServiceAwareBearerEntryPoint(String mcpServerPublicUrl) {
        this.mcpServerPublicUrl = mcpServerPublicUrl;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {

        // Admin paths: let the controller handle auth (don't inject WWW-Authenticate)
        String path = request.getServletPath();
        if (path != null && path.startsWith("/admin/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            response.flushBuffer();
            return;
        }

        String prmUrl = resolvePrmUrl(request);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=" + prmUrl);

        // Add CORS headers so browser can read the 401 response
        // Without these, browser blocks the response and axios throws -> "offline"
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty()) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }

        response.flushBuffer();
    }

    private String resolvePrmUrl(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Build base URL from request's actual host — RFC 9728 requires exact match
        // e.g. client connecting as 127.0.0.1 must get PRM with 127.0.0.1, not localhost
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String baseUrl = scheme + "://" + serverName;
        if (!((scheme.equals("http") && serverPort == 80) || (scheme.equals("https") && serverPort == 443))) {
            baseUrl += ":" + serverPort;
        }
        String contextPath = request.getContextPath();

        // Match /{serviceName}/mcp → /{serviceName}/.well-known/oauth-protected-resource
        // e.g. /weather/mcp → /weather/.well-known/oauth-protected-resource
        if (path != null) {
            // Strip context path
            String servletPath = request.getServletPath();
            // Pattern: /{serviceName}/mcp or /{serviceName}/mcp/...
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(/([^/]+))/mcp").matcher(servletPath);
            if (matcher.find()) {
                String servicePrefix = matcher.group(1); // e.g. /weather
                return baseUrl + contextPath + servicePrefix + "/.well-known/oauth-protected-resource";
            }
        }

        // Default: unified gateway PRM
        return baseUrl + contextPath + "/.well-known/oauth-protected-resource/mcp";
    }
}

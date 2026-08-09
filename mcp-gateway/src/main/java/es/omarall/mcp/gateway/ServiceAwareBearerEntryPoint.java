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

        String prmUrl = resolvePrmUrl(request);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=" + prmUrl);
        response.flushBuffer();
    }

    private String resolvePrmUrl(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Match /{serviceName}/mcp → /{serviceName}/.well-known/oauth-protected-resource
        // e.g. /weather/mcp → /weather/.well-known/oauth-protected-resource
        if (path != null) {
            // Strip context path
            String servletPath = request.getServletPath();
            // Pattern: /{serviceName}/mcp or /{serviceName}/mcp/...
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(/([^/]+))/mcp").matcher(servletPath);
            if (matcher.find()) {
                String servicePrefix = matcher.group(1); // e.g. /weather
                return mcpServerPublicUrl + servicePrefix + "/.well-known/oauth-protected-resource";
            }
        }

        // Default: unified gateway PRM
        return mcpServerPublicUrl + "/.well-known/oauth-protected-resource/mcp";
    }
}

package es.omarall.mcp.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-tenant MCP service router.
 * <p>
 * For each configured MCP client connection (e.g. weather, climate), exposes an
 * independent endpoint that proxies streamable-http requests directly to the
 * corresponding backend MCP server.
 *
 * <ul>
 *   <li>{@code POST /{serviceName}/mcp} — proxy JSON-RPC requests to backend</li>
 *   <li>{@code GET  /{serviceName}/mcp} — proxy SSE reconnect requests to backend</li>
 *   <li>{@code DELETE /{serviceName}/mcp} — proxy session close requests to backend</li>
 *   <li>{@code GET /{serviceName}/.well-known/oauth-protected-resource} — protected resource metadata</li>
 * </ul>
 */
@Slf4j
@RestController
public class McpServiceRouterController {

    /** Service name → backend URL (e.g. "weather" → "http://localhost:9092/mcp") */
    private final Map<String, String> serviceUrls;

    /** Public base URL of this gateway */
    private final String mcpServerPublicUrl;

    /** Public URL of the authorization server */
    private final String authServerPublicUrl;

    private final HttpClient httpClient;

    public McpServiceRouterController(
            Environment environment,
            @Value("${ecso.mcp-server.public-url:http://localhost:8080/mcp-gateway}") String mcpServerPublicUrl,
            @Value("${ecso.auth-server.public-url:${spring.security.oauth2.resourceserver.jwt.issuer-uri}}") String authServerPublicUrl) {

        this.mcpServerPublicUrl = mcpServerPublicUrl;
        this.authServerPublicUrl = authServerPublicUrl;
        this.serviceUrls = resolveServiceUrls(environment);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("MCP Service Router initialized with {} services: {}", serviceUrls.size(), serviceUrls.keySet());
        serviceUrls.forEach((name, url) -> log.info("  /{}/mcp → {}", name, url));
    }

    // ─────────────────────────────────────────────────────────────
    // MCP Proxy Endpoints
    // ─────────────────────────────────────────────────────────────

    @PostMapping("/{serviceName}/mcp")
    public void proxyMcpPost(
            @PathVariable String serviceName,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        proxyMcp(serviceName, "POST", request, response);
    }

    @GetMapping("/{serviceName}/mcp")
    public void proxyMcpGet(
            @PathVariable String serviceName,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        proxyMcp(serviceName, "GET", request, response);
    }

    @DeleteMapping("/{serviceName}/mcp")
    public void proxyMcpDelete(
            @PathVariable String serviceName,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        proxyMcp(serviceName, "DELETE", request, response);
    }

    private void proxyMcp(
            String serviceName,
            String method,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        String backendUrl = serviceUrls.get(serviceName);
        if (backendUrl == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "Unknown MCP service: " + serviceName + ". Available: " + serviceUrls.keySet());
            return;
        }

        log.debug("Proxying {} /{}/mcp → {}", method, serviceName, backendUrl);

        try {
            // Build backend request
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(backendUrl))
                    .timeout(Duration.ofSeconds(30));

            // Copy headers
            copyHeader(request, "Content-Type", reqBuilder);
            copyHeader(request, "Accept", reqBuilder);
            copyHeader(request, "Authorization", reqBuilder);
            copyHeader(request, "Mcp-Session-Id", reqBuilder);

            // Set method and body
            if ("POST".equals(method)) {
                byte[] body = request.getInputStream().readAllBytes();
                reqBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
                // Default Accept if not set
                if (request.getHeader("Accept") == null) {
                    reqBuilder.header("Accept", "application/json, text/event-stream");
                }
            } else if ("DELETE".equals(method)) {
                reqBuilder.DELETE();
            } else {
                reqBuilder.GET();
            }

            // Send and get streaming response
            HttpResponse<InputStream> backendResponse = httpClient.send(
                    reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            // Copy response status
            response.setStatus(backendResponse.statusCode());

            // Copy response headers
            copyResponseHeader(backendResponse, "Content-Type", response);
            copyResponseHeader(backendResponse, "Mcp-Session-Id", response);

            // Stream response body
            try (InputStream in = backendResponse.body();
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }

        } catch (Exception e) {
            log.error("Error proxying {} MCP request to '{}': {}", method, serviceName, e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY,
                        "Backend MCP service '" + serviceName + "' unavailable: " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Protected Resource Metadata
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/{serviceName}/.well-known/oauth-protected-resource")
    public Map<String, Object> protectedResourceMetadata(@PathVariable String serviceName) {
        if (!serviceUrls.containsKey(serviceName)) {
            throw new IllegalArgumentException("Unknown MCP service: " + serviceName);
        }

        String resourceUrl = mcpServerPublicUrl + "/" + serviceName + "/mcp";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", resourceUrl);
        metadata.put("authorization_servers", List.of(authServerPublicUrl));
        metadata.put("scopes_supported", List.of("mcp:read", "mcp:write"));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_name", serviceName + " MCP Service");
        return metadata;
    }

    // ─────────────────────────────────────────────────────────────
    // Configuration Resolution
    // ─────────────────────────────────────────────────────────────

    private static Map<String, String> resolveServiceUrls(Environment environment) {
        Map<String, String> result = new HashMap<>();

        org.springframework.boot.context.properties.bind.Binder binder =
                org.springframework.boot.context.properties.bind.Binder.get(environment);

        var bound = binder.bind(
                "ecso.mcp.services",
                Map.class
        );

        if (bound.isBound()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> connections = (Map<String, Object>) bound.get();
            for (String name : connections.keySet()) {
                String url = environment.getProperty(
                        "ecso.mcp.services." + name + ".url");
                if (url != null && !url.isBlank()) {
                    result.put(name, url);
                    log.info("Resolved MCP service '{}' → {}", name, url);
                }
            }
        }

        return Map.copyOf(result);
    }

    // ─────────────────────────────────────────────────────────────
    // Header Helpers
    // ─────────────────────────────────────────────────────────────

    private static void copyHeader(HttpServletRequest request, String name, HttpRequest.Builder target) {
        String value = request.getHeader(name);
        if (value != null && !value.isBlank()) {
            target.header(name, value);
        }
    }

    private static void copyResponseHeader(HttpResponse<?> source, String name, HttpServletResponse target) {
        List<String> values = source.headers().allValues(name);
        if (!values.isEmpty()) {
            target.setHeader(name, values.get(0));
        }
    }
}

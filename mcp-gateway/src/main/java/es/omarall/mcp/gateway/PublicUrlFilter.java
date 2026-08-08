package es.omarall.mcp.gateway;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;

/**
 * Rewrites the 401 {@code WWW-Authenticate} {@code resource_metadata} URL from the
 * internal server address (as seen by the container behind nginx, e.g.
 * {@code http://127.0.0.1:8082}) to the public nginx address (e.g.
 * {@code http://localhost:8080/mcp-gateway}).
 *
 * <p>MCP clients reject a {@code resource_metadata} URL whose host differs from the MCP
 * server URL they were configured with (SSRF guard) and fall back to a same-origin
 * well-known path that hits the nginx root page, producing an HTML body that breaks the
 * JSON-RPC handshake. Behind nginx the internal container address is observed under both
 * {@code localhost} and {@code 127.0.0.1}, so both are matched.
 *
 * <p>This filter only touches the 401 header. The metadata {@code resource} claim in the
 * 200 body is corrected through {@code protectedResourceMetadataCustomizer} on
 * {@link org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer}.
 */
public class PublicUrlFilter implements Filter {

	private final List<String> internalBaseUrls;

	private final String publicBaseUrl;

	public PublicUrlFilter(int port, String publicBaseUrl) {
		this.internalBaseUrls = List.of("http://localhost:" + port, "http://127.0.0.1:" + port);
		this.publicBaseUrl = publicBaseUrl;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		chain.doFilter(request, response);

		if (!(response instanceof HttpServletResponse httpRes) || httpRes.getStatus() != 401) {
			return;
		}

		String www = httpRes.getHeader(HttpHeaders.WWW_AUTHENTICATE);
		if (www == null) {
			return;
		}

		String rewritten = www;
		for (String internal : this.internalBaseUrls) {
			rewritten = rewritten.replace(internal, this.publicBaseUrl);
		}
		if (!rewritten.equals(www)) {
			httpRes.setHeader(HttpHeaders.WWW_AUTHENTICATE, rewritten);
		}
	}

}

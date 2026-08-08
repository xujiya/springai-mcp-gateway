package org.springaicommunity.mcp.security.sample.authorizationserver;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Returns OAuth2 authorization context for the Vue login page.
 * Reads the saved authorization request from the session to show
 * which client is requesting access and which scopes are being requested.
 */
@RestController
class AuthInfoController {

	private final RegisteredClientRepository clientRepository;

	AuthInfoController(@Lazy RegisteredClientRepository clientRepository) {
		this.clientRepository = clientRepository;
	}

	@GetMapping("/oauth2/auth-info")
	public Map<String, Object> authInfo(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return Map.of("pending", false);
		}

		// Spring Security saves the original request that triggered authentication
		// as SPRING_SECURITY_SAVED_REQUEST in the session
		Object savedRequest = session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
		if (savedRequest == null) {
			return Map.of("pending", false);
		}

		// DefaultSavedRequest or HttpSessionRequestCache's saved request
		// Extract query parameters from the redirect URL
		String savedUrl = extractSavedUrl(savedRequest);
		if (savedUrl == null) {
			return Map.of("pending", false);
		}

		String clientId = extractQueryParam(savedUrl, "client_id");
		String scope = extractQueryParam(savedUrl, "scope");
		String redirectUri = extractQueryParam(savedUrl, "redirect_uri");

		// Derive a human-readable client name from client_id
		String clientName = deriveClientName(clientId);

		return Map.of(
			"pending", true,
			"clientId", clientId != null ? clientId : "",
			"clientName", clientName,
			"scope", scope != null ? scope : "",
			"redirectUri", redirectUri != null ? redirectUri : ""
		);
	}

	private String extractSavedUrl(Object savedRequest) {
		// Spring Security's DefaultSavedRequest has getRedirectUrl()
		try {
			var method = savedRequest.getClass().getMethod("getRedirectUrl");
			return (String) method.invoke(savedRequest);
		} catch (Exception e) {
			// Fallback: try toString() which often contains the URL
			String str = savedRequest.toString();
			if (str.contains("http")) {
				int idx = str.indexOf("http");
				int end = str.indexOf(",", idx);
				if (end == -1) end = str.indexOf("}", idx);
				if (end == -1) end = str.length();
				return str.substring(idx, end).trim();
			}
			return null;
		}
	}

	private String extractQueryParam(String url, String param) {
		int queryStart = url.indexOf('?');
		if (queryStart == -1) return null;
		String query = url.substring(queryStart + 1);
		for (String pair : query.split("&")) {
			String[] kv = pair.split("=", 2);
			if (kv.length == 2 && kv[0].equals(param)) {
				return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private String deriveClientName(String clientId) {
		if (clientId == null || clientId.isEmpty()) return "Unknown";
		// Look up the registered client to get its clientName
		try {
			RegisteredClient client = clientRepository.findByClientId(clientId);
			if (client != null && client.getClientName() != null && !client.getClientName().isEmpty()) {
				return client.getClientName();
			}
		} catch (Exception e) {
			// fallback to ID-based name
		}
		// Fallback for unknown clients
		if (clientId.length() > 20) return "MCP Client (" + clientId.substring(0, 8) + "...)";
		return clientId;
	}
}

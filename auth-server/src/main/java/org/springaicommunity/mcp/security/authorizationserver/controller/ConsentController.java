package org.springaicommunity.mcp.security.authorizationserver.controller;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springaicommunity.mcp.security.authorizationserver.config.ConsentFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the consent step of the authorization flow (consent-first, login-after).
 * <ul>
 *   <li>GET  /oauth2/consent-info?client_id=X — returns client display info for the Vue consent page.</li>
 *   <li>POST /oauth2/consent — records consent in the session and redirects back to /oauth2/authorize.</li>
 * </ul>
 * The scope to display is parsed client-side from the {@code return_to} authorize URL.
 */
@RestController
class ConsentController {

    private final RegisteredClientRepository clientRepository;

    ConsentController(@Lazy RegisteredClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @GetMapping("/oauth2/consent-info")
    public Map<String, Object> consentInfo(@RequestParam("client_id") String clientId) {
        String clientName = clientId;
        try {
            RegisteredClient client = clientRepository.findByClientId(clientId);
            if (client != null && client.getClientName() != null && !client.getClientName().isBlank()) {
                clientName = client.getClientName();
            }
        } catch (Exception ignored) {
            // fall back to raw client id
        }
        // Only expose safe display fields (clientName + clientId), never secrets/redirects
        return Map.of("clientId", clientId, "clientName", clientName, "pending", true);
    }

    @PostMapping("/oauth2/consent")
    public void consent(@RequestParam("client_id") String clientId,
                        @RequestParam("return_to") String returnTo,
                        HttpSession session, HttpServletResponse response) throws IOException {
        session.setAttribute(ConsentFilter.CONSENT_ATTR_PREFIX + clientId, "true");
        // returnTo is the original authorize URL (decoded by Spring); redirect back to it.
        // Spring's authorize filter then takes over: unauthenticated → /vue-login, then code.
        response.sendRedirect(returnTo);
    }
}

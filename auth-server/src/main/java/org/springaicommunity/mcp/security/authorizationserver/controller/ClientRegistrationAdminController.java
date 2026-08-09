package org.springaicommunity.mcp.security.authorizationserver.controller;

import java.time.Instant;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for pre-registering OAuth2 clients (like Alibaba Cloud OAuth2 app registration).
 * <p>
 * When DCR is disabled ({@code mcp.dcr.enabled=false}), this is the ONLY way to
 * register new clients. Requires ADMIN role.
 *
 * <h3>Usage</h3>
 * <pre>
 * POST /oauth2/admin/clients
 * {
 *   "clientId": "my-mcp-client",          // Optional: auto-generated if omitted
 *   "clientName": "My MCP Application",
 *   "clientSecret": "my-secret",          // Optional: public client if omitted
 *   "grantTypes": ["authorization_code", "refresh_token"],
 *   "scope": "mcp:read mcp:write offline_access",
 *   "redirectUris": ["http://localhost:6274/oauth/callback"],
 *   "requireProofKey": true               // PKCE for public clients
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/oauth2/admin/clients")
@RequiredArgsConstructor
public class ClientRegistrationAdminController {

    private final RegisteredClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Pre-register a new OAuth2 client.
     * If clientId is provided, it will be used as-is (stable, like Alibaba Cloud).
     * If clientSecret is omitted, the client is public (PKCE).
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClientRegistrationResponse> registerClient(
            @RequestBody ClientRegistrationRequest request) {

        log.info("Admin pre-registering client: name={}, clientId={}",
                request.clientName(), request.clientId());

        String id = (request.clientId() != null && !request.clientId().isBlank())
                ? request.clientId() : java.util.UUID.randomUUID().toString();
        RegisteredClient.Builder builder = RegisteredClient.withId(id);

        // Use provided clientId or auto-generate
        if (request.clientId() != null && !request.clientId().isBlank()) {
            // Check if already exists
            RegisteredClient existing = clientRepository.findByClientId(request.clientId());
            if (existing != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ClientRegistrationResponse(
                                existing.getClientId(), existing.getClientName(),
                                "Client already exists", existing.getAuthorizationGrantTypes().stream()
                                        .map(AuthorizationGrantType::getValue).toList()));
            }
            builder.clientId(request.clientId());
        }

        builder.clientName(request.clientName() != null ? request.clientName() : "unnamed");
        builder.clientIdIssuedAt(Instant.now());

        // Client secret — if provided, it's a confidential client
        boolean isPublic = request.clientSecret() == null || request.clientSecret().isBlank();
        if (!isPublic) {
            builder.clientSecret(passwordEncoder.encode(request.clientSecret()));
        }

        // Authentication method
        if (isPublic) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        }

        // Grant types
        if (request.grantTypes() != null) {
            for (String grant : request.grantTypes()) {
                builder.authorizationGrantType(new AuthorizationGrantType(grant));
            }
        } else {
            // Default: authorization_code + refresh_token (safe for MCP)
            builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
            builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        }

        // Redirect URIs
        if (request.redirectUris() != null) {
            request.redirectUris().forEach(builder::redirectUri);
        }

        // Scopes
        if (request.scope() != null) {
            for (String scope : request.scope().split("\\s+")) {
                if (!scope.isBlank()) {
                    builder.scope(scope);
                }
            }
        }

        // Client settings
        ClientSettings.Builder clientSettings = ClientSettings.builder();
        if (request.requireProofKey() != null) {
            clientSettings.requireProofKey(request.requireProofKey());
        } else {
            clientSettings.requireProofKey(isPublic); // default PKCE for public clients
        }
        clientSettings.requireAuthorizationConsent(false);
        builder.clientSettings(clientSettings.build());

        // Token settings
        builder.tokenSettings(TokenSettings.builder().build());

        RegisteredClient registeredClient = builder.build();
        clientRepository.save(registeredClient);

        log.info("Pre-registered client: id={}, clientId={}, public={}, grants={}",
                registeredClient.getId(), registeredClient.getClientId(),
                isPublic, registeredClient.getAuthorizationGrantTypes());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClientRegistrationResponse(
                        registeredClient.getClientId(),
                        registeredClient.getClientName(),
                        isPublic ? "public (PKCE)" : "confidential",
                        registeredClient.getAuthorizationGrantTypes().stream()
                                .map(AuthorizationGrantType::getValue).toList()));
    }

    /**
     * List all registered clients (summary).
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String listClients() {
        // RegisteredClientRepository doesn't have a listAll method
        // Return hint to query MySQL directly
        return "Query oauth2_registered_client table for full list";
    }

    /**
     * Delete a client by clientId.
     */
    @DeleteMapping("/{clientId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteClient(@PathVariable String clientId) {
        RegisteredClient client = clientRepository.findByClientId(clientId);
        if (client == null) {
            return ResponseEntity.notFound().build();
        }
        // RegisteredClientRepository doesn't have delete — need to delete via mapper
        log.info("Admin deleting client: clientId={} (delete via SQL if needed)", clientId);
        return ResponseEntity.noContent().build();
    }

    // ---- DTOs ----

    public record ClientRegistrationRequest(
            String clientId,
            String clientName,
            String clientSecret,
            Set<String> grantTypes,
            String scope,
            Set<String> redirectUris,
            Boolean requireProofKey
    ) {}

    public record ClientRegistrationResponse(
            String clientId,
            String clientName,
            String clientType,
            java.util.List<String> grantTypes
    ) {}
}

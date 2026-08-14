package org.springaicommunity.mcp.security.authorizationserver.controller;

import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 提供 AS metadata 给未认证客户端（web.ignoring() 绕过了 Security filter chain）。
 * McpAuthorizationServerConfigurer 的 exceptionHandling 404 阻止了重定向到 login page。
 */
@org.springframework.web.bind.annotation.RestController
class AuthorizationServerMetadataController {

    @GetMapping("/.well-known/openid-configuration")
    public java.util.Map<String, Object> openidConfiguration() {
        var ctx = AuthorizationServerContextHolder.getContext();
        String issuer = ctx.getIssuer();
        return java.util.Map.ofEntries(
            java.util.Map.entry("issuer", issuer),
            java.util.Map.entry("authorization_endpoint", issuer + "/oauth2/authorize"),
            java.util.Map.entry("token_endpoint", issuer + "/oauth2/token"),
            java.util.Map.entry("token_endpoint_auth_methods_supported", java.util.List.of("client_secret_basic", "client_secret_post", "none")),
            java.util.Map.entry("jwks_uri", issuer + "/oauth2/jwks"),
            java.util.Map.entry("response_types_supported", java.util.List.of("code")),
            java.util.Map.entry("grant_types_supported", java.util.List.of("authorization_code", "client_credentials", "refresh_token")),
            java.util.Map.entry("revocation_endpoint", issuer + "/oauth2/revoke"),
            java.util.Map.entry("revocation_endpoint_auth_methods_supported", java.util.List.of("client_secret_basic", "client_secret_post")),
            java.util.Map.entry("introspection_endpoint", issuer + "/oauth2/introspect"),
            java.util.Map.entry("introspection_endpoint_auth_methods_supported", java.util.List.of("client_secret_basic", "client_secret_post")),
            java.util.Map.entry("code_challenge_methods_supported", java.util.List.of("S256")),
            java.util.Map.entry("scopes_supported", java.util.List.of("openid", "offline_access", "mcp:read", "mcp:write")),
            java.util.Map.entry("registration_endpoint", issuer + "/oauth2/register")
        );
    }
}

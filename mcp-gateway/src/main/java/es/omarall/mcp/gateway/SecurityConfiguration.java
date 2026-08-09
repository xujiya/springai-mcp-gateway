package es.omarall.mcp.gateway;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Security configuration for the MCP gateway.
 * <p>
 * Supports two authentication modes (对标阿里云 dual auth):
 * <ol>
 *   <li><b>AK 静态凭证 (API Key)</b> — long-term, no browser, no expiry by default.
 *       Checked first via {@link ApiKeyAuthenticationFilter}.</li>
 *   <li><b>OAuth2 JWT</b> — short-term tokens from auth-server.
 *       Used when no API key is present.</li>
 * </ol>
 * <p>
 * The gateway is a pure transparent proxy — it does not expose its own MCP server
 * endpoint. It validates credentials on proxied requests and returns per-service
 * WWW-Authenticate headers for RFC 9728 OAuth2 discovery.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Value("${ecso.auth-server.public-url:${spring.security.oauth2.resourceserver.jwt.issuer-uri}}")
    private String authServerPublicUrl;

    @Value("${ecso.mcp-server.public-url:http://localhost:8080/mcp-gateway}")
    private String mcpServerPublicUrl;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                             ApiKeyAuthenticationFilter apiKeyFilter) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        // RFC 9728: PRM endpoints are public (clients need them before having a token)
                        .requestMatchers("/*/.well-known/oauth-protected-resource").permitAll()
                        // Admin API keys endpoint — authenticated via admin Bearer token (checked in controller)
                        .requestMatchers("/admin/api-keys/**").permitAll()
                        // All MCP proxy endpoints require a valid token (JWT or API Key)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {})
                        .bearerTokenResolver(apiKeyAwareBearerTokenResolver()))
                // Per-service WWW-Authenticate: /weather/mcp → /weather/.well-known/oauth-protected-resource
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                new ServiceAwareBearerEntryPoint(this.mcpServerPublicUrl)))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // API Key filter BEFORE JWT Bearer filter — if valid API key, skip OAuth entirely
                .addFilterBefore(apiKeyFilter,
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new PublicUrlFilter(8082, this.mcpServerPublicUrl),
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * PasswordEncoder bean for API key hashing.
     * Uses DelegatingPasswordEncoder with bcrypt (same as auth-server).
     * Hashes stored as {bcrypt}$2a$10$...
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, org.springframework.security.crypto.password.PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder("bcrypt", encoders);
    }

    /**
     * BearerTokenResolver that skips non-JWT Bearer tokens.
     * <p>
     * API Keys ("ak-*:sk-*") and admin tokens ("adm-*") are handled by
     * ApiKeyAuthenticationFilter or the controller itself. If we let the JWT
     * filter try to decode these, it will fail with "Malformed token" and return 401.
     * <p>
     * This resolver returns null for non-JWT Bearer tokens, causing the JWT
     * filter to skip authentication.
     */
    private BearerTokenResolver apiKeyAwareBearerTokenResolver() {
        return (HttpServletRequest request) -> {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7).trim();
                // Skip API keys (ak-*:sk-* format) — handled by ApiKeyAuthenticationFilter
                if (token.startsWith("ak-") && token.contains(":sk-")) {
                    return null;
                }
                // Skip admin tokens (adm-* format) — handled by controller
                if (token.startsWith("adm-")) {
                    return null;
                }
                // JWT token — let the JWT filter process it
                return token;
            }
            return null;
        };
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:*",      // dev
                "http://127.0.0.1:*",      // dev
                "null"                     // W3C opaque origin: form submit after redirect
                // Production: add "https://your-domain.com"
        ));
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

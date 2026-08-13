package es.omarall.mcp.gateway;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Security configuration for the MCP gateway.
 * <p>
 * Two filter chains:
 * <ol>
 *   <li><b>Admin chain (order=0)</b>: /admin/** — no JWT/API-Key filter,
 *       controller handles auth itself (sys_user login + adm- token)</li>
 *   <li><b>MCP chain (order=1)</b>: everything else — JWT + API Key dual auth</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Value("${ecso.auth-server.public-url:${spring.security.oauth2.resourceserver.jwt.issuer-uri}}")
    private String authServerPublicUrl;

    @Value("${ecso.mcp-server.public-url:http://localhost:8080/mcp-gateway}")
    private String mcpServerPublicUrl;

    // ═══════════════════════════════════════════════════════════
    // Chain 1: Admin console — no security filters, controller handles auth
    // ═══════════════════════════════════════════════════════════

    @Bean
    @Order(0)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(new AntPathRequestMatcher("/admin/**"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // Chain 2: MCP proxy — JWT + API Key dual auth (original logic)
    // ═══════════════════════════════════════════════════════════

    @Bean
    @Order(1)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                             ApiKeyAuthenticationFilter apiKeyFilter) throws Exception {

        return http
                .authorizeHttpRequests(auth -> auth
                        // RFC 9728: PRM endpoints are public
                        .requestMatchers("/*/.well-known/oauth-protected-resource").permitAll()
                        // All MCP proxy endpoints require a valid token (JWT or API Key)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {})
                        .bearerTokenResolver(apiKeyAwareBearerTokenResolver()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                new ServiceAwareBearerEntryPoint(this.mcpServerPublicUrl)))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .addFilterBefore(apiKeyFilter,
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new PublicUrlFilter(8082, this.mcpServerPublicUrl),
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * PasswordEncoder bean for API key hashing.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, org.springframework.security.crypto.password.PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder("bcrypt", encoders);
    }

    /**
     * BearerTokenResolver that skips non-JWT Bearer tokens.
     */
    private BearerTokenResolver apiKeyAwareBearerTokenResolver() {
        return (HttpServletRequest request) -> {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7).trim();
                if (token.startsWith("ak-") && token.contains(":sk-")) {
                    return null;
                }
                if (token.startsWith("adm-")) {
                    return null;
                }
                if (token.startsWith("mcp_sk_")) {
                    return null;
                }
                return token;
            }
            return null;
        };
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*", "null"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

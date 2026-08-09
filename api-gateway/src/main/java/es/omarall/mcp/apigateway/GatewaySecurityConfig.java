package es.omarall.mcp.apigateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * API Gateway security: whitelist paths are public, everything else requires Bearer token.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    private final WhitelistProperties whitelist;
    private final String issuerUri;

    GatewaySecurityConfig(WhitelistProperties whitelist,
                          org.springframework.core.env.Environment env) {
        this.whitelist = whitelist;
        this.issuerUri = env.getProperty(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:9090");
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // CORS: handled by CorsWebFilter bean + downstream auth-server
                // Do NOT disable - that blocks OPTIONS preflight (403)
                .cors(cors -> cors.disable())
                .authorizeExchange(exchanges -> {
                    // OPTIONS preflight must be permitted for CORS
                    exchanges.pathMatchers("OPTIONS", "/**").permitAll();
                    // Whitelist: public paths (OAuth2 endpoints, Vue login, etc.)
                    for (String path : whitelist.getPaths()) {
                        exchanges.pathMatchers(path).permitAll();
                    }
                    // Everything else requires authentication
                    exchanges.anyExchange().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwkSetUri(issuerUri + "/oauth2/jwks")))
                .build();
    }

    /**
     * CORS filter for the API Gateway.
     * Handles OPTIONS preflight requests so downstream auth-server CORS is not blocked.
     * In production, restrict allowedOriginPatterns to the actual frontend domain.
     */
    @Bean
    CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(java.util.List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "null"    // W3C opaque origin: form submit after redirect, sandbox iframe
        ));
        config.setAllowedMethods(java.util.List.of("*"));
        config.setAllowedHeaders(java.util.List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}

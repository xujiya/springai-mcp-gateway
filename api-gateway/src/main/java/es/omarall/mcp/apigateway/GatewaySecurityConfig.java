package es.omarall.mcp.apigateway;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
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
    private final List<String> corsOrigins;

    GatewaySecurityConfig(WhitelistProperties whitelist,
                          org.springframework.core.env.Environment env) {
        this.whitelist = whitelist;
        this.issuerUri = env.getProperty(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:9090");
        this.corsOrigins = env.getProperty("ecso.cors.allowed-origins", "").isBlank()
                ? List.of() : List.of(env.getProperty("ecso.cors.allowed-origins").split(","));
    }

    @Bean
    @org.springframework.core.annotation.Order(-1)
    SecurityWebFilterChain adminSecurityWebFilterChain(ServerHttpSecurity http) {
        // Admin console paths: no OAuth2 resource server, no JWT validation.
        // The downstream mcp-gateway/auth-server handle auth themselves.
        // Without this, Bearer adm-xxx tokens would be rejected by the
        // default chain's OAuth2 JWT decoder.
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.disable())
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(
                        "/ecso/admin/**"))
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // CORS: CorsWebFilter bean handles CORS (Order=HIGHEST_PRECEDENCE)
                // Security must not interfere — keep .disable() so Security
                // doesn't add its own CorsWebFilter
                .cors(cors -> cors.disable())
                .authorizeExchange(exchanges -> {
                    // OPTIONS preflight: CorsWebFilter already handled CORS headers,
                    // permit so Security doesn't reject
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
     * CORS filter: base dev origins + ecso.cors.allowed-origins from yml.
     * Runs before Security, so preflight OPTIONS requests get CORS headers.
     */
    @Bean
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        var origins = new java.util.ArrayList<>(java.util.List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "null"
        ));
        origins.addAll(corsOrigins.stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(java.util.List.of("*"));
        config.setAllowedHeaders(java.util.List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }

}

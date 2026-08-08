package es.omarall.mcp.apigateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

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
                .cors(cors -> cors.disable())
                .authorizeExchange(exchanges -> {
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
}

package es.omarall.mcp.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for the MCP gateway.
 * <p>
 * The gateway is a pure transparent proxy — it does not expose its own MCP server
 * endpoint. It validates JWT Bearer tokens on proxied requests and returns per-service
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
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        // RFC 9728: PRM endpoints are public (clients need them before having a token)
                        .requestMatchers("/*/.well-known/oauth-protected-resource").permitAll()
                        // All MCP proxy endpoints require a valid token
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                // Per-service WWW-Authenticate: /weather/mcp → /weather/.well-known/oauth-protected-resource
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                new ServiceAwareBearerEntryPoint(this.mcpServerPublicUrl)))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .addFilterAfter(new PublicUrlFilter(8082, this.mcpServerPublicUrl),
                        org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                .build();
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

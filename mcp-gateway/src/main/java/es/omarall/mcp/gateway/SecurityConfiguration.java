package es.omarall.mcp.gateway;

import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
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

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    // Daniel Garnier-Moiroux  https://spring.io/blog/2025/09/30/spring-ai-mcp-server-security

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String authServerUrl;

    // Public URL for the authorization server (as seen by external clients through Nginx)
    // Internal issuer-uri is used for JWT validation; public URL is used in 401 responses
    @Value("${ecso.auth-server.public-url:${spring.security.oauth2.resourceserver.jwt.issuer-uri}}")
    private String authServerPublicUrl;

    // Public URL of this MCP server as seen by external clients through Nginx
    // (without the trailing /mcp). Used to rewrite internal addresses in 401 responses
    // and to override the `resource` claim of the protected-resource metadata body.
    @Value("${ecso.mcp-server.public-url:http://localhost:8080/mcp-gateway}")
    private String mcpServerPublicUrl;

    // AuthorizationServer Configuration
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Allow .well-known endpoints without authentication (RFC 9728: clients
                // must discover authorization servers before they have a token)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/*/.well-known/oauth-protected-resource").permitAll()
                        .anyRequest().authenticated())
                // Configure OAuth2 on the MCP server
                .with(
                        McpServerOAuth2Configurer.mcpServerOAuth2(),
                        (mcpAuthorization) -> {
                            // REQUIRED: the authserver's issuer URI
                            mcpAuthorization.authorizationServer(this.authServerPublicUrl);
                            // NOTE: providing a customizer REPLACES the library default
                            // (which only sets authServer/resourceName/bearerMethod and,
                            // as of this library revision, does NOT apply configured scopes),
                            // so all standard claims must be set here.
                            mcpAuthorization.protectedResourceMetadataCustomizer(metadata -> metadata
                                    // Override the `resource` claim: the library derives it
                                    // from the request URL, which behind nginx is the internal
                                    // http://127.0.0.1:8082/mcp. Expose the public prefixed URL.
                                    .resource(this.mcpServerPublicUrl + "/mcp")
                                    .authorizationServer(this.authServerPublicUrl)
                                    .resourceName("Spring MCP Gateway")
                                    .bearerMethod("header")
                                    .scope("mcp:read")
                                    .scope("mcp:write"));
                            // OPTIONAL: enforce the `aud` claim in the JWT token.
//                            mcpAuthorization.validateAudienceClaim(true);
                        }
                )
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .addFilterAfter(new PublicUrlFilter(8082, this.mcpServerPublicUrl),
                        org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                .build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

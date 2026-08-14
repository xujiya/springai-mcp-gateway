package org.springaicommunity.mcp.security.authorizationserver.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer.mcpAuthorizationServer;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class AuthorizationServerConfiguration {

	private final boolean dcrEnabled;

	AuthorizationServerConfiguration(
			@Value("${mcp.dcr.enabled:true}") boolean dcrEnabled) {
		this.dcrEnabled = dcrEnabled;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> {
				auth
					.requestMatchers("/.well-known/openid-configuration", "/vue-login", "/assets/**").permitAll();
				// When DCR is disabled, block /oauth2/register with 403
				// (denyAll() causes 302 to login, leaking internal :9090 in Location)
				if (!dcrEnabled) {
					auth.requestMatchers("/oauth2/register").denyAll();
				}
				auth.anyRequest().authenticated();
			})
			.formLogin(form -> form
					.loginPage("/vue-login")
					.loginProcessingUrl("/login")
					.defaultSuccessUrl("/")
					.failureUrl("/vue-login?error")
					.permitAll())
			.with(mcpAuthorizationServer(), withDefaults());

		if (dcrEnabled) {
			http.csrf(csrf -> csrf.ignoringRequestMatchers("/login", "/oauth2/register", "/oauth2/consent", "/oauth2/admin/**"));
		} else {
			http.csrf(csrf -> csrf.ignoringRequestMatchers("/login", "/oauth2/consent", "/oauth2/admin/**"));
		}

		http.cors(cors -> cors.configurationSource(corsConfigurationSource()));

		// When DCR is disabled, denyAll() for /oauth2/register would normally
		// trigger 302 to login page (leaking internal :9090 in Location header).
		// Override AccessDeniedHandler to return 403 JSON instead.
		http.exceptionHandling(ex -> ex
				.accessDeniedHandler((request, response, ex2) -> {
					response.setStatus(403);
					response.setContentType("application/json");
					response.getWriter().write("{\"error\":\"access_denied\",\"error_description\":\"DCR is disabled\"}");
				}));

		return http.build();
	}

	/**
	 * Register ConsentFilter at the servlet layer with HIGHEST_PRECEDENCE so it runs
	 * BEFORE Spring Security's filter chain (which contains
	 * OAuth2AuthorizationEndpointFilter). Using FilterRegistrationBean avoids the
	 * "OAuth2AuthorizationEndpointFilter does not have a registered order" error that
	 * http.addFilterBefore(..., OAuth2AuthorizationEndpointFilter.class) triggers.
	 */
	@Bean
	FilterRegistrationBean<ConsentFilter> consentFilterRegistration() {
		FilterRegistrationBean<ConsentFilter> reg = new FilterRegistrationBean<>();
		reg.setFilter(new ConsentFilter());
		reg.addUrlPatterns("/oauth2/authorize");
		reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return reg;
	}

	/** Password encoder for client secrets and user passwords */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	/** Vue login 页面、静态资源、AS metadata 等不需要 Security filter chain 处理 */
	@Bean
	WebSecurityCustomizer publicEndpoints() {
		return web -> web.ignoring().requestMatchers(
				"/oauth2/auth-info",
				"/oauth2/consent-info",
				"/oauth2/consent"
		);
	}

	@Value("${ecso.cors.allowed-origins:}")
	private String corsOriginsConfig;

	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		// Base dev origins + configured production domains
		var origins = new java.util.ArrayList<>(java.util.List.of(
				"http://localhost:*",      // dev: Vue + nginx
				"http://127.0.0.1:*",      // dev: loopback
				"null"                     // W3C opaque origin: form submit after redirect
		));
		if (corsOriginsConfig != null && !corsOriginsConfig.isBlank()) {
			origins.addAll(java.util.Arrays.stream(corsOriginsConfig.split(","))
					.map(String::trim).filter(s -> !s.isEmpty()).toList());
		}
		configuration.setAllowedOriginPatterns(origins);
		configuration.setAllowedMethods(List.of("*"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
}

package org.springaicommunity.mcp.security.sample.authorizationserver;

import java.util.List;

import org.springaicommunity.mcp.security.authorizationserver.repository.MybatisUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer.mcpAuthorizationServer;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
class AuthorizationServerConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/.well-known/openid-configuration", "/vue-login", "/assets/**").permitAll()
						.anyRequest().authenticated())
				.formLogin(form -> form
						.loginPage("/vue-login")
						.loginProcessingUrl("/login")
						.defaultSuccessUrl("/")
						.failureUrl("/vue-login?error")
						.permitAll())
				.with(mcpAuthorizationServer(), withDefaults())
				.csrf(csrf -> csrf.ignoringRequestMatchers("/login", "/oauth2/register"))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.build();
	}

	/** Vue login 页面、静态资源、AS metadata 等不需要 Security filter chain 处理 */
	@Bean
	WebSecurityCustomizer publicEndpoints() {
		return web -> web.ignoring().requestMatchers(
				"/oauth2/auth-info"
		);
	}

	@Bean
	UserDetailsService userDetailsService(MybatisUserDetailsService mybatisUserDetailsService) {
		return mybatisUserDetailsService;
	}

	public CorsConfigurationSource corsConfigurationSource() {
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

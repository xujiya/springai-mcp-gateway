/*
 * Copyright 2025-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.mcp.security.authorizationserver.config;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2ClientRegistrationEndpointConfigurer;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springaicommunity.mcp.security.sample.authorizationserver.McpTokenGeneratorWrapper;
import org.springframework.security.oauth2.server.authorization.token.ResourceIdentifierAudienceTokenCustomizer;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer.authorizationServer;

/**
 * @author Daniel Garnier-Moiroux
 */
public class McpAuthorizationServerConfigurer
		extends AbstractHttpConfigurer<McpAuthorizationServerConfigurer, HttpSecurity> {

	public static McpAuthorizationServerConfigurer mcpAuthorizationServer() {
		return new McpAuthorizationServerConfigurer();
	}

	@Override
	public void init(HttpSecurity http) throws Exception {
		http.with(authorizationServer(), authServer -> {
			authServer.authorizationServerMetadataEndpoint(
					authorizationServerMetadataEndpoint -> authorizationServerMetadataEndpoint
						.authorizationServerMetadataCustomizer(authorizationServerMetadataCustomizer()));
			OAuth2TokenGenerator<?> tokenGenerator = getTokenGenerator(http);
			authServer.tokenGenerator(tokenGenerator);
			System.out.println("[MCP] tokenGenerator set: " + tokenGenerator.getClass().getName());
		});
		// Configure DCR security settings from application.yml
		Environment env = http.getSharedObject(ApplicationContext.class).getEnvironment();
		OAuth2ClientRegistrationEndpointConfigurer dcrConfigurer = new OAuth2ClientRegistrationEndpointConfigurer();
		dcrConfigurer.setDcrClientSecretExpiresIn(
				parseDuration(env.getProperty("mcp.dcr.client-secret-expires-in", "90d")));
		dcrConfigurer.setDcrAccessTokenTimeToLive(
				parseDuration(env.getProperty("mcp.dcr.access-token-time-to-live", "5m")));
		dcrConfigurer.setDcrRefreshTokenTimeToLive(
				parseDuration(env.getProperty("mcp.dcr.refresh-token-time-to-live", "1h")));
		http.with(dcrConfigurer, withDefaults());
		http.csrf(csrf -> csrf.ignoringRequestMatchers(
				OAuth2ClientRegistrationEndpointConfigurer.OAUTH2_CLIENT_REGISTRATION_ENDPOINT_URI));

		// MCP: 注释掉 exceptionHandling 404 — AS metadata 需要被未认证客户端访问
		// OAuth2AuthorizationServerMetadataEndpointFilter 会直接返回 metadata
		// http.exceptionHandling(
		// 		exc -> exc.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.NOT_FOUND),
		// 				PathPatternRequestMatcher.withDefaults().matcher("/.well-known/openid-configuration")));
	}

	private static Duration parseDuration(String value) {
		// Parse Spring-style duration: "90d", "5m", "1h", "30s", "P90D" (ISO)
		if (value == null || value.isBlank()) {
			return Duration.ofDays(90);
		}
		try {
			// Try ISO-8601 first (e.g. "P90D", "PT5M")
			if (value.startsWith("P")) {
				return Duration.parse(value);
			}
			// Spring boot style: number + unit suffix
			char unit = value.charAt(value.length() - 1);
			long amount = Long.parseLong(value.substring(0, value.length() - 1));
			return switch (unit) {
				case 'd' -> Duration.ofDays(amount);
				case 'h' -> Duration.ofHours(amount);
				case 'm' -> Duration.ofMinutes(amount);
				case 's' -> Duration.ofSeconds(amount);
				default -> Duration.parse(value); // fall back to ISO
			};
		} catch (Exception e) {
			throw new IllegalArgumentException(
					"Invalid duration '" + value + "'. Use Spring style (90d, 5m, 1h) or ISO-8601 (P90D, PT5M)", e);
		}
	}

	private static Consumer<OAuth2AuthorizationServerMetadata.Builder> authorizationServerMetadataCustomizer() {
		return (builder) -> {
			AuthorizationServerContext authorizationServerContext = AuthorizationServerContextHolder.getContext();
			String issuer = authorizationServerContext.getIssuer();

			String clientRegistrationEndpoint = UriComponentsBuilder.fromUriString(issuer)
				.path(OAuth2ClientRegistrationEndpointConfigurer.OAUTH2_CLIENT_REGISTRATION_ENDPOINT_URI)
				.build()
				.toUriString();

			builder.clientRegistrationEndpoint(clientRegistrationEndpoint);
		};
	}

	private OAuth2TokenGenerator<?> getTokenGenerator(HttpSecurity http) {
		OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator = http.getSharedObject(OAuth2TokenGenerator.class);
		if (tokenGenerator == null) {
			tokenGenerator = getOptionalBean(http, OAuth2TokenGenerator.class);
			if (tokenGenerator == null) {
				JWKSource<SecurityContext> jwkSource = getJwkSource(http);
				JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
				jwtGenerator.setJwtCustomizer(new ResourceIdentifierAudienceTokenCustomizer());
				OAuth2RefreshTokenGenerator refreshTokenGenerator = new OAuth2RefreshTokenGenerator();
				tokenGenerator = new DelegatingOAuth2TokenGenerator(jwtGenerator, refreshTokenGenerator);
				// MCP: OAuth2RefreshTokenGenerator 对 public client 返回 null，
				// 包装一层强制生成 refresh token（PKCE 已提供客户端认证）
				tokenGenerator = new McpTokenGeneratorWrapper(tokenGenerator);
				System.out.println("[MCP] Created McpTokenGeneratorWrapper(DelegatingOAuth2TokenGenerator)");
			} else {
				System.out.println("[MCP] Found OAuth2TokenGenerator bean: " + tokenGenerator.getClass().getSimpleName());
			}
		} else {
			System.out.println("[MCP] Found sharedObject OAuth2TokenGenerator: " + tokenGenerator.getClass().getSimpleName());
		}
		http.setSharedObject(OAuth2TokenGenerator.class, tokenGenerator);
		return tokenGenerator;
	}

	/**
	 * Lifted from
	 * {@code org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2ConfigurerUtils}.
	 */
	static JWKSource<SecurityContext> getJwkSource(HttpSecurity http) {
		JWKSource<SecurityContext> jwkSource = http.getSharedObject(JWKSource.class);
		if (jwkSource == null) {
			ResolvableType type = ResolvableType.forClassWithGenerics(JWKSource.class, SecurityContext.class);
			jwkSource = getOptionalBean(http, type);
			if (jwkSource != null) {
				http.setSharedObject(JWKSource.class, jwkSource);
			}
		}
		return jwkSource;
	}

	/**
	 * Lifted from
	 * {@code org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2ConfigurerUtils}.
	 */
	static <T> T getOptionalBean(HttpSecurity http, Class<T> type) {
		Map<String, T> beansMap = BeanFactoryUtils
			.beansOfTypeIncludingAncestors(http.getSharedObject(ApplicationContext.class), type);
		if (beansMap.size() > 1) {
			throw new NoUniqueBeanDefinitionException(type, beansMap.size(),
					"Expected single matching bean of type '" + type.getName() + "' but found " + beansMap.size() + ": "
							+ StringUtils.collectionToCommaDelimitedString(beansMap.keySet()));
		}
		return (!beansMap.isEmpty() ? beansMap.values().iterator().next() : null);
	}

	/**
	 * Lifted from
	 * {@code org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2ConfigurerUtils}.
	 */
	static <T> T getOptionalBean(HttpSecurity http, ResolvableType type) {
		ApplicationContext context = http.getSharedObject(ApplicationContext.class);
		String[] names = context.getBeanNamesForType(type);
		if (names.length > 1) {
			throw new NoUniqueBeanDefinitionException(type, names);
		}
		return (names.length == 1) ? (T) context.getBean(names[0]) : null;
	}

}

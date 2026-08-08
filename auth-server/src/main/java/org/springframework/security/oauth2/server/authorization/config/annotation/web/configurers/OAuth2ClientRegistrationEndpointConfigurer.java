/*
 * Copyright 2020-2025 the original author or authors.
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
package org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.converter.OAuth2ClientRegistrationRegisteredClientConverter;
import org.springframework.security.oauth2.server.authorization.converter.RegisteredClientOAuth2ClientRegistrationConverter;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.web.OAuth2ClientRegistrationEndpointFilter;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;

/**
 * @author Joe Grandja
 */
public final class OAuth2ClientRegistrationEndpointConfigurer
		extends AbstractHttpConfigurer<OAuth2ClientRegistrationEndpointConfigurer, HttpSecurity> {

	public static final String OAUTH2_CLIENT_REGISTRATION_ENDPOINT_URI = "/oauth2/register";

	private static final String CLIENT_SETTINGS_NAMESPACE = "settings.client.";

	private static final String RESOURCE_IDS_KEY = "resource_ids";

	private Duration dcrClientSecretExpiresIn = Duration.ofDays(90);

	private Duration dcrAccessTokenTimeToLive = Duration.ofMinutes(5);

	private Duration dcrRefreshTokenTimeToLive = Duration.ofHours(1);

	/**
	 * Set the lifetime of DCR-registered client secrets. Defaults to 90 days.
	 * Configure via {@code mcp.dcr.client-secret-expires-in} in application.yml.
	 */
	public void setDcrClientSecretExpiresIn(Duration dcrClientSecretExpiresIn) {
		this.dcrClientSecretExpiresIn = dcrClientSecretExpiresIn;
	}

	/**
	 * Set the access token TTL for DCR-registered clients. Defaults to 5 minutes.
	 * Configure via {@code mcp.dcr.access-token-time-to-live} in application.yml.
	 */
	public void setDcrAccessTokenTimeToLive(Duration dcrAccessTokenTimeToLive) {
		this.dcrAccessTokenTimeToLive = dcrAccessTokenTimeToLive;
	}

	/**
	 * Set the refresh token TTL for DCR-registered clients. Defaults to 1 hour.
	 * Configure via {@code mcp.dcr.refresh-token-time-to-live} in application.yml.
	 */
	public void setDcrRefreshTokenTimeToLive(Duration dcrRefreshTokenTimeToLive) {
		this.dcrRefreshTokenTimeToLive = dcrRefreshTokenTimeToLive;
	}

	@Override
	public void configure(HttpSecurity http) {
		RegisteredClientRepository registeredClientRepository = OAuth2ConfigurerUtils
			.getRegisteredClientRepository(http);
		OAuth2ClientRegistrationAuthenticationProvider clientRegistrationAuthenticationProvider = new OAuth2ClientRegistrationAuthenticationProvider(
				registeredClientRepository);
		clientRegistrationAuthenticationProvider.setRegisteredClientConverter(
				new CustomRegisteredClientConverter(this.dcrClientSecretExpiresIn, this.dcrAccessTokenTimeToLive, this.dcrRefreshTokenTimeToLive));
		clientRegistrationAuthenticationProvider
			.setClientRegistrationConverter(new CustomClientRegistrationConverter());
		http.authenticationProvider(clientRegistrationAuthenticationProvider);

		AuthenticationManager authenticationManager = http.getSharedObject(AuthenticationManager.class);
		OAuth2ClientRegistrationEndpointFilter clientRegistrationEndpointFilter = new OAuth2ClientRegistrationEndpointFilter(
				authenticationManager, OAUTH2_CLIENT_REGISTRATION_ENDPOINT_URI);
		http.addFilterBefore(clientRegistrationEndpointFilter, AbstractPreAuthenticatedProcessingFilter.class);
	}

	static List<String> getResourceIds(ClientSettings clientSettings) {
		return clientSettings.getSetting(CLIENT_SETTINGS_NAMESPACE.concat(RESOURCE_IDS_KEY));
	}

	private static final class CustomRegisteredClientConverter
			implements Converter<OAuth2ClientRegistration, RegisteredClient> {

		private final Duration clientSecretExpiresIn;
		private final Duration accessTokenTimeToLive;
		private final Duration refreshTokenTimeToLive;

		private final OAuth2ClientRegistrationRegisteredClientConverter delegate = new OAuth2ClientRegistrationRegisteredClientConverter();

		CustomRegisteredClientConverter(Duration clientSecretExpiresIn, Duration accessTokenTimeToLive, Duration refreshTokenTimeToLive) {
			this.clientSecretExpiresIn = clientSecretExpiresIn;
			this.accessTokenTimeToLive = accessTokenTimeToLive;
			this.refreshTokenTimeToLive = refreshTokenTimeToLive;
			this.delegate.setClientSecretExpiresIn(clientSecretExpiresIn);
		}

		@Override
		public RegisteredClient convert(OAuth2ClientRegistration clientRegistration) {
			RegisteredClient registeredClient = this.delegate.convert(clientRegistration);
			ClientSettings.Builder clientSettingsBuilder = ClientSettings
				.withSettings(registeredClient.getClientSettings().getSettings());
			if (clientRegistration.getClaims().get(RESOURCE_IDS_KEY) != null) {
				clientSettingsBuilder.setting(CLIENT_SETTINGS_NAMESPACE.concat(RESOURCE_IDS_KEY),
						clientRegistration.getClaims().get(RESOURCE_IDS_KEY));
			}

			// Security: DCR-registered clients get short-lived tokens and rotating refresh tokens (configurable)
			TokenSettings.Builder tokenSettingsBuilder =
				TokenSettings.withSettings(registeredClient.getTokenSettings().getSettings())
					.accessTokenTimeToLive(this.accessTokenTimeToLive)
					.refreshTokenTimeToLive(this.refreshTokenTimeToLive)
					.reuseRefreshTokens(false);

			// ═══ SECURITY: Two-tier client model ═══
			// DCR-registered clients: authorization_code + refresh_token ONLY (never client_credentials)
			//   → attacker can register but CANNOT get token without user login
			// Pre-registered clients (application.yml): admin-controlled, may have client_credentials
			// ═══════════════════════════════════════════

			boolean isPublicClient = registeredClient.getClientAuthenticationMethods()
				.contains(ClientAuthenticationMethod.NONE);

			RegisteredClient.Builder reb = RegisteredClient.from(registeredClient);

			if (isPublicClient) {
				// Public client (none/PKCE): no secret, no client_credentials
				reb.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
					.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
			} else {
				// Confidential client: force secret auth + authorization_code only
				reb.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
					.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
					.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
					.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
			}

			return reb
				.clientSettings(clientSettingsBuilder.build())
				.tokenSettings(tokenSettingsBuilder.build())
				.build();
		}

	}

	private static final class CustomClientRegistrationConverter
			implements Converter<RegisteredClient, OAuth2ClientRegistration> {

		private final RegisteredClientOAuth2ClientRegistrationConverter delegate = new RegisteredClientOAuth2ClientRegistrationConverter();

		@Override
		public OAuth2ClientRegistration convert(RegisteredClient registeredClient) {
			OAuth2ClientRegistration clientRegistration = this.delegate.convert(registeredClient);
			Map<String, Object> claims = new HashMap<>(clientRegistration.getClaims());
			ClientSettings clientSettings = registeredClient.getClientSettings();
			if (getResourceIds(clientSettings) != null) {
				claims.put(RESOURCE_IDS_KEY, getResourceIds(clientSettings));
			}
			// Security: Strip client_credentials from DCR response grant_types
			// DCR clients never have client_credentials; only pre-registered (admin) clients do
			Object grantTypes = claims.get("grant_types");
			if (grantTypes instanceof java.util.List<?> list) {
				claims.put("grant_types", list.stream()
					.filter(gt -> !AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(gt))
					.toList());
			}
			return OAuth2ClientRegistration.withClaims(claims).build();
		}

	}

}

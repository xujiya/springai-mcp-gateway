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
package org.springframework.security.oauth2.server.authorization.converter;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.util.CollectionUtils;

/**
 * A {@link Converter} that converts the provided {@link OAuth2ClientRegistration} to a
 * {@link RegisteredClient}.
 *
 * @author Joe Grandja
 * @since 2.0
 */
public final class OAuth2ClientRegistrationRegisteredClientConverter
		implements Converter<OAuth2ClientRegistration, RegisteredClient> {

	private static final StringKeyGenerator CLIENT_ID_GENERATOR = new Base64StringKeyGenerator(
			Base64.getUrlEncoder().withoutPadding(), 32);

	private static final StringKeyGenerator CLIENT_SECRET_GENERATOR = new Base64StringKeyGenerator(
			Base64.getUrlEncoder().withoutPadding(), 48);

	private Duration clientSecretExpiresIn = Duration.ofDays(90);

	/**
	 * Set the lifetime of DCR-registered client secrets. Defaults to 90 days.
	 * Configure via {@code mcp.dcr.client-secret-expires-in} in application.yml.
	 */
	public void setClientSecretExpiresIn(Duration clientSecretExpiresIn) {
		this.clientSecretExpiresIn = clientSecretExpiresIn;
	}

	@Override
	public RegisteredClient convert(OAuth2ClientRegistration clientRegistration) {
		// @formatter:off
		RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
				.clientId(CLIENT_ID_GENERATOR.generateKey())
				.clientIdIssuedAt(Instant.now())
				.clientName(clientRegistration.getClientName());

		if (ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue().equals(clientRegistration.getTokenEndpointAuthenticationMethod())) {
			builder
					.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
					.clientSecret(CLIENT_SECRET_GENERATOR.generateKey());
		}
		else if (ClientAuthenticationMethod.CLIENT_SECRET_JWT.getValue().equals(clientRegistration.getTokenEndpointAuthenticationMethod())) {
			builder
					.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_JWT)
					.clientSecret(CLIENT_SECRET_GENERATOR.generateKey());
		}
		else if (ClientAuthenticationMethod.PRIVATE_KEY_JWT.getValue().equals(clientRegistration.getTokenEndpointAuthenticationMethod())) {
			builder.clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
		}
		else if (ClientAuthenticationMethod.NONE.getValue().equals(clientRegistration.getTokenEndpointAuthenticationMethod())) {
			builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
		}
		else {
			builder
					.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
					.clientSecret(CLIENT_SECRET_GENERATOR.generateKey());
		}

		builder.redirectUris((redirectUris) ->
				redirectUris.addAll(clientRegistration.getRedirectUris()));

		if (!CollectionUtils.isEmpty(clientRegistration.getGrantTypes())) {
			// Security: DCR must NOT allow client_credentials — only authorization_code + refresh_token
			// client_credentials is reserved for pre-registered (admin-controlled) clients
			List<String> allowedGrantTypes = clientRegistration.getGrantTypes().stream()
				.filter(grantType -> !AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(grantType))
				.toList();
			if (!allowedGrantTypes.isEmpty()) {
				builder.authorizationGrantTypes((authorizationGrantTypes) ->
						allowedGrantTypes.forEach((grantType) ->
							authorizationGrantTypes.add(new AuthorizationGrantType(grantType))));
			} else {
				// All requested grant types were filtered (e.g. only client_credentials) → default to authorization_code
				builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
			}
		}
		else {
			builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
		}
		if (!CollectionUtils.isEmpty(clientRegistration.getResponseTypes()) &&
				clientRegistration.getResponseTypes().contains(OAuth2AuthorizationResponseType.CODE.getValue())) {
			builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
		}

		if (!CollectionUtils.isEmpty(clientRegistration.getScopes())) {
			builder.scopes((scopes) ->
					scopes.addAll(clientRegistration.getScopes()));
		}

		ClientSettings.Builder clientSettingsBuilder = ClientSettings.builder()
				.requireProofKey(true)
                // dgarnier
				.requireAuthorizationConsent(false);
		if (clientRegistration.getJwkSetUrl() != null) {
			clientSettingsBuilder.jwkSetUrl(clientRegistration.getJwkSetUrl().toString());
		}

		builder
				.clientSettings(clientSettingsBuilder.build());

		// Security: DCR-registered client secrets expire after configurable duration (default 90 days)
		// Only set for confidential clients (public clients with 'none' have no secret)
		boolean isPublicClient = ClientAuthenticationMethod.NONE.getValue()
			.equals(clientRegistration.getTokenEndpointAuthenticationMethod());
		if (!isPublicClient) {
			builder.clientSecretExpiresAt(Instant.now().plus(this.clientSecretExpiresIn));
		}

		return builder.build();
		// @formatter:on
	}

}

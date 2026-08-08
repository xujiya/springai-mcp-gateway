package org.springaicommunity.mcp.security.sample.authorizationserver;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Instant;
import java.util.Base64;

/**
 * 包装 DelegatingOAuth2TokenGenerator，解决 public client 无 refresh token 问题。
 * <p>
 * Spring Auth Server 1.5.3 的 OAuth2RefreshTokenGenerator 对 public client
 * （client_authentication_method=none）返回 null，不发 refresh token。
 * <p>
 * MCP 规范要求 PKCE + public client + refresh token（RFC 7636 + rotation），
 * 所以当 delegate 返回 null 且 tokenType 是 REFRESH_TOKEN 时，我们强制生成。
 */
public class McpTokenGeneratorWrapper implements OAuth2TokenGenerator<OAuth2Token> {

    private final OAuth2TokenGenerator<?> delegate;
    private final StringKeyGenerator refreshTokenGenerator = new Base64StringKeyGenerator(
            Base64.getUrlEncoder().withoutPadding(), 96);

    public McpTokenGeneratorWrapper(OAuth2TokenGenerator<?> delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2Token generate(OAuth2TokenContext context) {
        OAuth2Token token = delegate.generate(context);
        if (token == null && context.getTokenType() != null
                && OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            // Delegate (OAuth2RefreshTokenGenerator) returned null for public client — force generate
            Instant issuedAt = Instant.now();
            Instant expiresAt = issuedAt.plus(
                    context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
            token = new OAuth2RefreshToken(this.refreshTokenGenerator.generateKey(), issuedAt, expiresAt);
        }
        return token;
    }
}

package org.springaicommunity.mcp.security.authorizationserver.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springaicommunity.mcp.security.authorizationserver.entity.RegisteredClientEntity;
import org.springaicommunity.mcp.security.authorizationserver.mapper.RegisteredClientMapper;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class MybatisRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public void save(RegisteredClient registeredClient) {
        RegisteredClientEntity entity = toEntity(registeredClient);
        // Upsert: try insert, on duplicate key update
        RegisteredClientEntity existing = mapper.selectById(entity.getId());
        if (existing != null) {
            // 更新时保留审计列（注册来源不应被覆盖）
            entity.setRegistrationSource(existing.getRegistrationSource());
            mapper.updateById(entity);
        } else {
            // 新插入：来源由调用方通过 RegistrationSourceHolder 注入（DCR / PRE-REGISTERED / ADMIN）
            // 未设置时为 null —— 调用方应显式声明，避免启发式误判
            entity.setRegistrationSource(RegistrationSourceHolder.get());
            mapper.insert(entity);
        }
    }

    @Nullable
    @Override
    public RegisteredClient findById(String id) {
        RegisteredClientEntity entity = mapper.selectById(id);
        return entity != null ? toObject(entity) : null;
    }

    @Nullable
    @Override
    public RegisteredClient findByClientId(String clientId) {
        RegisteredClientEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<RegisteredClientEntity>().eq(RegisteredClientEntity::getClientId, clientId));
        return entity != null ? toObject(entity) : null;
    }

    // ---- Conversion ----

    @SneakyThrows
    private RegisteredClientEntity toEntity(RegisteredClient client) {
        RegisteredClientEntity entity = new RegisteredClientEntity();
        entity.setId(client.getId());
        entity.setClientId(client.getClientId());
        entity.setClientIdIssuedAt(client.getClientIdIssuedAt());
        entity.setClientSecret(client.getClientSecret());
        entity.setClientSecretExpiresAt(client.getClientSecretExpiresAt());
        entity.setClientName(client.getClientName());

        entity.setClientAuthenticationMethods(
                objectMapper.writeValueAsString(client.getClientAuthenticationMethods().stream()
                        .map(ClientAuthenticationMethod::getValue).toList()));
        entity.setAuthorizationGrantTypes(
                objectMapper.writeValueAsString(client.getAuthorizationGrantTypes().stream()
                        .map(AuthorizationGrantType::getValue).toList()));
        entity.setRedirectUris(
                client.getRedirectUris().isEmpty() ? null
                        : objectMapper.writeValueAsString(client.getRedirectUris()));
        entity.setScopes(
                client.getScopes().isEmpty() ? null
                        : objectMapper.writeValueAsString(client.getScopes()));
        entity.setClientSettings(objectMapper.writeValueAsString(client.getClientSettings()));
        entity.setTokenSettings(objectMapper.writeValueAsString(client.getTokenSettings()));
        return entity;
    }

    @SneakyThrows
    private RegisteredClient toObject(RegisteredClientEntity entity) {
        Set<String> authMethods = objectMapper.readValue(entity.getClientAuthenticationMethods(),
                new TypeReference<Set<String>>() {});
        Set<String> grantTypes = objectMapper.readValue(entity.getAuthorizationGrantTypes(),
                new TypeReference<Set<String>>() {});
        Set<String> redirectUris = StringUtils.hasText(entity.getRedirectUris())
                ? objectMapper.readValue(entity.getRedirectUris(), new TypeReference<Set<String>>() {})
                : Set.of();
        Set<String> scopes = StringUtils.hasText(entity.getScopes())
                ? objectMapper.readValue(entity.getScopes(), new TypeReference<Set<String>>() {})
                : Set.of();

        RegisteredClient.Builder builder = RegisteredClient.withId(entity.getId())
                .clientId(entity.getClientId())
                .clientIdIssuedAt(entity.getClientIdIssuedAt())
                .clientSecret(entity.getClientSecret())
                .clientSecretExpiresAt(entity.getClientSecretExpiresAt())
                .clientName(entity.getClientName());

        authMethods.forEach(m -> builder.clientAuthenticationMethod(new ClientAuthenticationMethod(m)));
        grantTypes.forEach(g -> builder.authorizationGrantType(new AuthorizationGrantType(g)));
        redirectUris.forEach(builder::redirectUri);
        scopes.forEach(builder::scope);

        builder.clientSettings(parseClientSettings(entity.getClientSettings()));
        builder.tokenSettings(parseTokenSettings(entity.getTokenSettings()));

        return builder.build();
    }

    @SneakyThrows
    private ClientSettings parseClientSettings(String json) {
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
        map.remove("@class"); // Remove type hint if present
        ClientSettings.Builder builder = ClientSettings.builder();
        if (map.containsKey("requireProofKey")) {
            builder.requireProofKey(Boolean.TRUE.equals(map.get("requireProofKey")));
        }
        if (map.containsKey("requireAuthorizationConsent")) {
            builder.requireAuthorizationConsent(Boolean.TRUE.equals(map.get("requireAuthorizationConsent")));
        }
        // Skip JWS algorithm setting - not needed for basic config
        return builder.build();
    }

    @SneakyThrows
    private TokenSettings parseTokenSettings(String json) {
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
        map.remove("@class");
        TokenSettings.Builder builder = TokenSettings.builder();
        if (map.containsKey("accessTokenTimeToLive")) {
            builder.accessTokenTimeToLive(java.time.Duration.parse((String) map.get("accessTokenTimeToLive")));
        }
        if (map.containsKey("refreshTokenTimeToLive")) {
            builder.refreshTokenTimeToLive(java.time.Duration.parse((String) map.get("refreshTokenTimeToLive")));
        }
        if (map.containsKey("authorizationCodeTimeToLive")) {
            builder.authorizationCodeTimeToLive(java.time.Duration.parse((String) map.get("authorizationCodeTimeToLive")));
        }
        if (map.containsKey("deviceCodeTimeToLive")) {
            builder.deviceCodeTimeToLive(java.time.Duration.parse((String) map.get("deviceCodeTimeToLive")));
        }
        if (map.containsKey("reuseRefreshTokens")) {
            builder.reuseRefreshTokens(Boolean.TRUE.equals(map.get("reuseRefreshTokens")));
        }
        return builder.build();
    }
}

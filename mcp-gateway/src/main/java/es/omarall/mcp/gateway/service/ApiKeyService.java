package es.omarall.mcp.gateway.service;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import es.omarall.mcp.gateway.entity.ApiKey;
import es.omarall.mcp.gateway.mapper.ApiKeyMapper;

/**
 * API Key validation and management service.
 * <p>
 * Uses Spring Security's {@link PasswordEncoder} (DelegatingPasswordEncoder with bcrypt)
 * to hash and verify API keys, consistent with auth-server's client_secret handling.
 */
@Slf4j
@Service
public class ApiKeyService {

    private final ApiKeyMapper apiKeyMapper;
    private final PasswordEncoder passwordEncoder;

    public ApiKeyService(ApiKeyMapper apiKeyMapper, PasswordEncoder passwordEncoder) {
        this.apiKeyMapper = apiKeyMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Validate an API key and return the entity if valid.
     * <p>
     * A key is valid if:
     * <ul>
     *   <li>It matches a stored hash</li>
     *   <li>It is enabled</li>
     *   <li>It has not expired (null expiresAt = never expires)</li>
     * </ul>
     *
     * @param rawKey the plaintext API key (e.g. "ak-mcp-dev-20250613")
     * @return the ApiKey entity if valid, null otherwise
     */
    public ApiKey validate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }

        // Find by prefix first for efficiency
        String prefix = rawKey.length() >= 11 ? rawKey.substring(0, 11) : rawKey;
        List<ApiKey> candidates = apiKeyMapper.selectList(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getApiKeyPrefix, prefix)
                        .eq(ApiKey::getEnabled, true));

        for (ApiKey candidate : candidates) {
            if (passwordEncoder.matches(rawKey, candidate.getApiKeyHash())) {
                if (candidate.isExpired()) {
                    log.warn("API key '{}' (prefix={}) has expired", candidate.getName(), prefix);
                    return null;
                }
                // Update last_used_at (async, don't block)
                try {
                    apiKeyMapper.update(null,
                            new LambdaUpdateWrapper<ApiKey>()
                                    .eq(ApiKey::getId, candidate.getId())
                                    .set(ApiKey::getLastUsedAt, Instant.now()));
                } catch (Exception e) {
                    log.debug("Failed to update last_used_at for API key {}: {}", candidate.getId(), e.getMessage());
                }
                return candidate;
            }
        }

        // Fallback: try all enabled keys (in case prefix matching missed)
        // This handles edge cases where prefix was stored differently
        List<ApiKey> allKeys = apiKeyMapper.selectList(
                new LambdaQueryWrapper<ApiKey>().eq(ApiKey::getEnabled, true));
        for (ApiKey candidate : allKeys) {
            if (passwordEncoder.matches(rawKey, candidate.getApiKeyHash())) {
                if (candidate.isExpired()) {
                    return null;
                }
                return candidate;
            }
        }

        return null;
    }

    /**
     * List all API keys (without hash).
     */
    public List<ApiKey> listAll() {
        return apiKeyMapper.selectList(null);
    }

    /**
     * Get API key by ID.
     */
    public ApiKey getById(String id) {
        return apiKeyMapper.selectById(id);
    }

    /**
     * Create a new API key.
     *
     * @param name         human-readable name
     * @param serviceScope service scope ("*" for all, or comma-separated)
     * @param description  optional description
     * @param createdBy    who created this key
     * @param expiresAt    optional expiry (null = never expires)
     * @return the plaintext API key (shown only once!)
     */
    public String create(String name, String serviceScope, String description, String createdBy, Instant expiresAt) {
        // Generate random API key: ak- + 32 hex chars
        String rawKey = "ak-" + generateRandomHex(16);
        String prefix = rawKey.substring(0, 11); // ak- + 8 hex chars
        String hash = passwordEncoder.encode(rawKey);

        ApiKey entity = new ApiKey();
        entity.setName(name);
        entity.setApiKeyHash(hash);
        entity.setApiKeyPrefix(prefix);
        entity.setServiceScope(serviceScope != null ? serviceScope : "*");
        entity.setDescription(description);
        entity.setCreatedBy(createdBy);
        entity.setExpiresAt(expiresAt);
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.now());

        apiKeyMapper.insert(entity);
        log.info("Created API key '{}' (prefix={}, scope={}, expires={})",
                name, prefix, serviceScope, expiresAt);

        return rawKey; // Plaintext — shown only once!
    }

    /**
     * Revoke (disable) an API key.
     */
    public boolean revoke(String id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) return false;
        key.setEnabled(false);
        apiKeyMapper.updateById(key);
        log.info("Revoked API key '{}' (id={})", key.getName(), id);
        return true;
    }

    /**
     * Enable a previously revoked API key.
     */
    public boolean enable(String id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) return false;
        key.setEnabled(true);
        apiKeyMapper.updateById(key);
        log.info("Enabled API key '{}' (id={})", key.getName(), id);
        return true;
    }

    /**
     * Delete an API key permanently.
     */
    public boolean delete(String id) {
        int rows = apiKeyMapper.deleteById(id);
        if (rows > 0) {
            log.info("Deleted API key id={}", id);
            return true;
        }
        return false;
    }

    private static String generateRandomHex(int bytes) {
        byte[] random = new byte[bytes];
        new java.security.SecureRandom().nextBytes(random);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : random) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

package es.omarall.mcp.gateway.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
 * Dual-part model (对标阿里云 AccessKey):
 * <ul>
 *   <li><b>AccessKey ID</b>: Public, sent in request for key lookup</li>
 *   <li><b>AccessKey Secret</b>: Never transmitted, only used for HMAC signing</li>
 * </ul>
 * <p>
 * Two authentication modes:
 * <ol>
 *   <li><b>HMAC Signed</b> (recommended): Client signs request with secret,
 *       server verifies signature. Secret never crosses the wire.</li>
 *   <li><b>Bearer/Header</b> (legacy): Client sends secret directly.
 *       Less secure but simpler (like Stripe API keys).</li>
 * </ol>
 * <p>
 * Rate limiting: Max 10 failed attempts per AccessKey ID per minute.
 * After threshold, the key is temporarily blocked for 5 minutes.
 */
@Slf4j
@Service
public class ApiKeyService {

    private final ApiKeyMapper apiKeyMapper;
    private final PasswordEncoder passwordEncoder;

    /** Rate limit: max failed attempts before temporary block */
    private static final int MAX_FAILED_ATTEMPTS = 10;
    /** Rate limit: block duration in minutes */
    private static final int BLOCK_DURATION_MINUTES = 5;

    /** Track failed attempts: AccessKeyId → (count, firstAttemptTime) */
    private final ConcurrentHashMap<String, RateLimitEntry> failedAttempts = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyMapper apiKeyMapper, PasswordEncoder passwordEncoder) {
        this.apiKeyMapper = apiKeyMapper;
        this.passwordEncoder = passwordEncoder;
    }

    // ═══════════════════════════════════════════════════════════
    // Validation: Bearer/Header mode (secret sent directly)
    // ═══════════════════════════════════════════════════════════

    /**
     * Validate an API key by its AccessKey ID + Secret (bearer mode).
     * <p>
     * The secret is compared against the stored bcrypt hash.
     *
     * @param accessKeyId the AccessKey ID (e.g. "ak-a1327ef38936cdc9e4c1")
     * @param rawSecret   the plaintext AccessKey Secret (e.g. "sk-c0c396...")
     * @return the ApiKey entity if valid, null otherwise
     */
    public ApiKey validateBySecret(String accessKeyId, String rawSecret) {
        if (accessKeyId == null || rawSecret == null) return null;

        // Check rate limit
        if (isRateLimited(accessKeyId)) {
            log.warn("AccessKey ID '{}' is rate-limited due to too many failed attempts", accessKeyId);
            return null;
        }

        ApiKey key = lookupByAccessKeyId(accessKeyId);
        if (key == null) {
            recordFailedAttempt(accessKeyId);
            return null;
        }

        if (passwordEncoder.matches(rawSecret, key.getAccessKeySecretHash())) {
            if (key.isExpired()) {
                log.warn("AccessKey ID '{}' (name={}) has expired", accessKeyId, key.getName());
                return null;
            }
            clearFailedAttempts(accessKeyId);
            updateLastUsed(key);
            return key;
        }

        recordFailedAttempt(accessKeyId);
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    // Validation: HMAC Signed mode (secret NEVER transmitted)
    // ═══════════════════════════════════════════════════════════

    /**
     * Validate a request using HMAC-SHA256 signature (对标阿里云 AK 签名模式).
     * <p>
     * The client computes: {@code signature = HMAC-SHA256(secret, stringToSign)}
     * where {@code stringToSign = METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + BODY_SHA256}
     * <p>
     * The server looks up the secret hash by AccessKey ID, verifies the signature
     * using the stored secret (fetched from DB for comparison), and checks timestamp freshness.
     * <p>
     * <b>Important:</b> The secret is NEVER in the request. Only the signature is transmitted.
     *
     * @param accessKeyId  the AccessKey ID from X-AccessKey-Id header
     * @param signature    the HMAC-SHA256 signature from X-AccessKey-Signature header
     * @param method       HTTP method (POST, GET, etc.)
     * @param path         request path
     * @param timestamp    request timestamp from X-AccessKey-Timestamp header
     * @param bodySha256   SHA-256 hash of request body (for integrity)
     * @return the ApiKey entity if valid, null otherwise
     */
    public ApiKey validateBySignature(String accessKeyId, String signature,
                                       String method, String path,
                                       String timestamp, String bodySha256) {
        if (accessKeyId == null || signature == null) return null;

        // Check rate limit
        if (isRateLimited(accessKeyId)) {
            log.warn("AccessKey ID '{}' is rate-limited", accessKeyId);
            return null;
        }

        // Verify timestamp freshness (±5 minutes to handle clock skew)
        if (!isTimestampFresh(timestamp)) {
            log.warn("AccessKey ID '{}' has stale/invalid timestamp: {}", accessKeyId, timestamp);
            return null;
        }

        ApiKey key = lookupByAccessKeyId(accessKeyId);
        if (key == null) {
            recordFailedAttempt(accessKeyId);
            return null;
        }

        // For HMAC verification, we need the plaintext secret to compute the expected signature.
        // Since we only store bcrypt hashes, we CANNOT verify HMAC server-side
        // without storing the secret in a reversible form.
        //
        // Options:
        // 1. Store AES-encrypted secret (reversible, can verify HMAC)
        // 2. Require client to also send the secret for comparison (defeats purpose)
        // 3. Use different approach: store secret as plaintext in a protected column
        //
        // For now, we log a warning and fall back to the simpler model.
        // TODO: Implement AES-encrypted secret storage for full HMAC support
        log.warn("HMAC signature verification not yet fully implemented - requires encrypted secret storage");
        recordFailedAttempt(accessKeyId);
        return null;
    }

    /**
     * Compute the string-to-sign for HMAC verification.
     * Format: {@code METHOD\nPATH\nTIMESTAMP\nBODY_SHA256}
     */
    public static String computeStringToSign(String method, String path, String timestamp, String bodySha256) {
        return method + "\n" + path + "\n" + timestamp + "\n" + (bodySha256 != null ? bodySha256 : "");
    }

    /**
     * Compute HMAC-SHA256 signature.
     * Used by the client to sign requests.
     */
    public static String computeHmacSha256(String secret, String stringToSign) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] signatureBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(signatureBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CRUD
    // ═══════════════════════════════════════════════════════════

    /** List all API keys (without hash). */
    public List<ApiKey> listAll() {
        return apiKeyMapper.selectList(null);
    }

    /** Get API key by ID. */
    public ApiKey getById(String id) {
        return apiKeyMapper.selectById(id);
    }

    /** Get API key by AccessKey ID. */
    public ApiKey getByAccessKeyId(String accessKeyId) {
        return apiKeyMapper.selectOne(
                new LambdaQueryWrapper<ApiKey>().eq(ApiKey::getAccessKeyId, accessKeyId));
    }

    /**
     * Create a new API key with dual-part model.
     *
     * @return CreateResult containing both AccessKey ID and Secret.
     *         The Secret is shown ONLY ONCE — save it immediately!
     */
    public CreateResult create(String name, String serviceScope, String description,
                                String createdBy, Instant expiresAt) {
        // Generate AccessKey ID: ak- + 20 hex chars (10 bytes)
        String accessKeyId = "ak-" + generateRandomHex(10);
        // Generate AccessKey Secret: sk- + 40 hex chars (20 bytes, 160 bits entropy)
        String rawSecret = "sk-" + generateRandomHex(20);
        String prefix = accessKeyId.substring(0, 11); // ak- + 8 hex chars for prefix lookup
        String secretHash = passwordEncoder.encode(rawSecret);

        ApiKey entity = new ApiKey();
        entity.setName(name);
        entity.setAccessKeyId(accessKeyId);
        entity.setAccessKeySecretHash(secretHash);
        entity.setAccessKeyPrefix(prefix);
        entity.setServiceScope(serviceScope != null ? serviceScope : "*");
        entity.setDescription(description);
        entity.setCreatedBy(createdBy);
        entity.setExpiresAt(expiresAt);
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.now());

        apiKeyMapper.insert(entity);
        log.info("Created API key '{}' (accessKeyId={}, prefix={}, scope={}, expires={})",
                name, accessKeyId, prefix, serviceScope, expiresAt);

        return new CreateResult(accessKeyId, rawSecret, entity);
    }

    /** Revoke (disable) an API key. */
    public boolean revoke(String id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) return false;
        key.setEnabled(false);
        apiKeyMapper.updateById(key);
        log.info("Revoked API key '{}' (id={})", key.getName(), id);
        return true;
    }

    /** Enable a previously revoked API key. */
    public boolean enable(String id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) return false;
        key.setEnabled(true);
        apiKeyMapper.updateById(key);
        log.info("Enabled API key '{}' (id={})", key.getName(), id);
        return true;
    }

    /** Delete an API key permanently. */
    public boolean delete(String id) {
        int rows = apiKeyMapper.deleteById(id);
        if (rows > 0) {
            log.info("Deleted API key id={}", id);
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════

    private ApiKey lookupByAccessKeyId(String accessKeyId) {
        // Primary lookup by exact accessKeyId
        List<ApiKey> candidates = apiKeyMapper.selectList(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getAccessKeyId, accessKeyId)
                        .eq(ApiKey::getEnabled, true));

        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }

        // Fallback: prefix lookup (for backward compatibility with old format)
        String prefix = accessKeyId.length() >= 11 ? accessKeyId.substring(0, 11) : accessKeyId;
        candidates = apiKeyMapper.selectList(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getAccessKeyPrefix, prefix)
                        .eq(ApiKey::getEnabled, true));

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void updateLastUsed(ApiKey key) {
        try {
            apiKeyMapper.update(null,
                    new LambdaUpdateWrapper<ApiKey>()
                            .eq(ApiKey::getId, key.getId())
                            .set(ApiKey::getLastUsedAt, Instant.now()));
        } catch (Exception e) {
            log.debug("Failed to update last_used_at: {}", e.getMessage());
        }
    }

    // ─── Rate Limiting ────────────────────────────────────────

    private boolean isRateLimited(String accessKeyId) {
        RateLimitEntry entry = failedAttempts.get(accessKeyId);
        if (entry == null) return false;

        // Check if block has expired
        if (entry.isBlocked() && entry.blockedUntil().isBefore(Instant.now())) {
            failedAttempts.remove(accessKeyId);
            return false;
        }

        return entry.isBlocked();
    }

    private void recordFailedAttempt(String accessKeyId) {
        failedAttempts.compute(accessKeyId, (k, existing) -> {
            if (existing == null) {
                return new RateLimitEntry(1, null);
            }
            int newCount = existing.count() + 1;
            Instant blockedUntil = null;
            if (newCount >= MAX_FAILED_ATTEMPTS && !existing.isBlocked()) {
                blockedUntil = Instant.now().plusSeconds(BLOCK_DURATION_MINUTES * 60L);
                log.warn("AccessKey ID '{}' blocked for {} minutes after {} failed attempts",
                        accessKeyId, BLOCK_DURATION_MINUTES, newCount);
            }
            return new RateLimitEntry(newCount, blockedUntil);
        });
    }

    private void clearFailedAttempts(String accessKeyId) {
        failedAttempts.remove(accessKeyId);
    }

    // ─── Timestamp Validation ─────────────────────────────────

    private boolean isTimestampFresh(String timestamp) {
        if (timestamp == null) return false;
        try {
            Instant ts = Instant.parse(timestamp);
            Instant now = Instant.now();
            // Allow ±5 minutes for clock skew
            return ts.isAfter(now.minusSeconds(300)) && ts.isBefore(now.plusSeconds(300));
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Crypto Helpers ───────────────────────────────────────

    private static String generateRandomHex(int bytes) {
        byte[] random = new byte[bytes];
        new java.security.SecureRandom().nextBytes(random);
        return bytesToHex(random);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ─── Inner Classes ────────────────────────────────────────

    /** Result of creating a new API key — contains the plaintext secret (shown once!). */
    public record CreateResult(String accessKeyId, String accessKeySecret, ApiKey entity) {}

    /** Rate limit tracking entry. */
    private record RateLimitEntry(int count, Instant blockedUntil) {
        boolean isBlocked() {
            return blockedUntil != null;
        }
    }
}

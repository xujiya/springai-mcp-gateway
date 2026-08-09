package es.omarall.mcp.gateway.entity;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * API Key entity — dual-part model (对标阿里云 AccessKey).
 * <p>
 * <b>AccessKey ID</b> ({@code accessKeyId}): Public identifier, sent in every request.
 * Used for key lookup — like Alibaba Cloud's AccessKey ID.
 * Format: {@code ak-<20 hex chars>} (e.g. {@code ak-a1327ef38936cdc9e4c1}).
 * <p>
 * <b>AccessKey Secret</b>: Stored only as bcrypt hash ({@code accessKeySecretHash}).
 * <b>NEVER transmitted over the wire</b> — only used for HMAC-SHA256 request signing.
 * Like Alibaba Cloud's AccessKey Secret.
 * <p>
 * Two auth modes supported:
 * <ol>
 *   <li><b>HMAC Signed</b> (recommended, 对标阿里云): Client sends AccessKey ID +
 *       HMAC-SHA256 signature of the request. Secret never leaves the client.</li>
 *   <li><b>Bearer Token</b> (legacy/simple): Client sends secret directly as
 *       {@code X-API-Key: sk-xxx} or {@code Authorization: Bearer sk-xxx}.
 *       Less secure — secret transmitted on every request.</li>
 * </ol>
 */
@Data
@Accessors(chain = true)
@TableName("mcp_api_key")
public class ApiKey {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** Human-readable name */
    private String name;

    /** AccessKey ID — public, sent in requests for key lookup (e.g. "ak-a1327ef38936cdc9e4c1") */
    private String accessKeyId;

    /** AccessKey Secret — bcrypt hash only, never stored as plaintext */
    private String accessKeySecretHash;

    /** First 8 hex chars of AccessKey ID for efficient prefix lookup */
    private String accessKeyPrefix;

    /** Service scope: "*" = all services, "weather" = weather only, "weather,climate" = both */
    private String serviceScope;

    /** Optional description */
    private String description;

    /** Who created this key */
    private String createdBy;

    /** When this key was created */
    private Instant createdAt;

    /** When this key expires (null = never expires, 对标阿里云 AK 永不过期) */
    private Instant expiresAt;

    /** Whether this key is enabled */
    private Boolean enabled;

    /** When this key was last used */
    private Instant lastUsedAt;

    /**
     * Check if this API key has expired.
     * null expiresAt = never expires (永久有效, 对标阿里云).
     */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    /**
     * Check if this key allows access to a specific service.
     */
    public boolean allowsService(String serviceName) {
        if (serviceScope == null || serviceScope.isBlank()) return false;
        if ("*".equals(serviceScope)) return true;
        for (String scope : serviceScope.split(",")) {
            if (scope.trim().equalsIgnoreCase(serviceName)) return true;
        }
        return false;
    }
}

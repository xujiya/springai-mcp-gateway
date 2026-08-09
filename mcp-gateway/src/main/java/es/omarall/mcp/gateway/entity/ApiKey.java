package es.omarall.mcp.gateway.entity;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * MCP API Key entity — AK static credential (对标阿里云 AccessKey).
 * <p>
 * Features:
 * <ul>
 *   <li>Never expires by default ({@code expiresAt = null}), like Alibaba Cloud AK</li>
 *   <li>Service-scoped: restrict which MCP services this key can access</li>
 *   <li>API key format: {@code ak-xxxxxxxxxxxx...} (ak- prefix + 32 hex chars)</li>
 * </ul>
 */
@Data
@TableName("mcp_api_key")
public class ApiKey {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** Human-readable name */
    private String name;

    /** bcrypt hash of the API key (with {bcrypt} prefix for DelegatingPasswordEncoder) */
    private String apiKeyHash;

    /** First 8 chars for identification (e.g. "ak-mcp-de") — never the full key */
    private String apiKeyPrefix;

    /** Comma-separated service names or "*" for all services */
    private String serviceScope;

    /** Optional description */
    private String description;

    /** Who created this key */
    private String createdBy;

    /** Creation timestamp */
    private Instant createdAt;

    /**
     * Expiry timestamp. NULL = never expires (AK mode, 对标阿里云).
     * Non-null = key expires at this time.
     */
    private Instant expiresAt;

    /** Whether this key is enabled */
    private Boolean enabled;

    /** Last time this key was used */
    private Instant lastUsedAt;

    /**
     * Check if this key has expired.
     * Returns false if expiresAt is null (never expires).
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if this key allows access to the given service.
     * "*" allows all services; otherwise check comma-separated list.
     */
    public boolean allowsService(String serviceName) {
        if (serviceScope == null || serviceScope.isBlank() || "*".equals(serviceScope.trim())) {
            return true;
        }
        for (String scope : serviceScope.split(",")) {
            if (scope.trim().equals(serviceName)) {
                return true;
            }
        }
        return false;
    }
}

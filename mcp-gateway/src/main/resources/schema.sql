-- mcp_api_key table: dual-part AccessKey model (对标阿里云)
-- AccessKey ID (public, for lookup) + AccessKey Secret (bcrypt hash, never transmitted)
-- + Bearer token (mcp_sk_xxx, for MCP clients that only support Authorization: Bearer)
CREATE TABLE IF NOT EXISTS mcp_api_key (
    id                      VARCHAR(64)   NOT NULL PRIMARY KEY,
    name                    VARCHAR(128)  NOT NULL,
    access_key_id           VARCHAR(32)   NOT NULL UNIQUE COMMENT 'AccessKey ID (ak-xxx, public, for lookup)',
    access_key_secret_hash  VARCHAR(256)  NOT NULL COMMENT 'AccessKey Secret bcrypt hash ({bcrypt}$2a$10$...)',
    access_key_prefix       VARCHAR(16)   NOT NULL COMMENT 'First 11 chars of AccessKey ID for prefix lookup',
    token_hash              VARCHAR(256)  NULL COMMENT 'Bearer token bcrypt hash (mcp_sk_xxx, for MCP clients)',
    service_scope           VARCHAR(256)  NOT NULL DEFAULT '*' COMMENT 'Service scope: * = all, weather = weather only',
    description             VARCHAR(512),
    created_by              VARCHAR(128),
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at              TIMESTAMP     NULL COMMENT 'NULL = never expires (AK mode, 对标阿里云)',
    enabled                 BOOLEAN       NOT NULL DEFAULT TRUE,
    last_used_at            TIMESTAMP     NULL,
    INDEX idx_access_key_id (access_key_id),
    INDEX idx_prefix_enabled (access_key_prefix, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Keys — dual-part AccessKey model (对标阿里云) + Bearer token';

-- MCP API Key table (shared with mcp_auth database)
-- This is also created by auth-server's schema.sql, so IF NOT EXISTS is safe
CREATE TABLE IF NOT EXISTS mcp_api_key (
    id              VARCHAR(36)   NOT NULL PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    api_key_hash    VARCHAR(255)  NOT NULL COMMENT 'bcrypt hash of the API key',
    api_key_prefix  VARCHAR(12)   NOT NULL COMMENT 'first 8 chars for identification (ak-xxxx)',
    service_scope   VARCHAR(255)  NOT NULL DEFAULT '*' COMMENT 'comma-separated service names or *',
    description     VARCHAR(500)  DEFAULT NULL,
    created_by      VARCHAR(100)  DEFAULT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP     DEFAULT NULL COMMENT 'NULL = never expires (AK mode, 对标阿里云)',
    enabled         TINYINT(1)    NOT NULL DEFAULT 1,
    last_used_at    TIMESTAMP     DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

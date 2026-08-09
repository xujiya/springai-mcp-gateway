-- OAuth2 Registered Client
CREATE TABLE IF NOT EXISTS oauth2_registered_client (
    id                            VARCHAR(100)  NOT NULL PRIMARY KEY,
    client_id                     VARCHAR(100)  NOT NULL,
    client_id_issued_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret                 VARCHAR(255)  DEFAULT NULL,
    client_secret_expires_at      TIMESTAMP     DEFAULT NULL,
    client_name                   VARCHAR(255)  NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types     VARCHAR(1000) NOT NULL,
    redirect_uris                 VARCHAR(1000) DEFAULT NULL,
    scopes                        VARCHAR(1000) DEFAULT NULL,
    client_settings               VARCHAR(2000) NOT NULL,
    token_settings                VARCHAR(2000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- OAuth2 Authorization
CREATE TABLE IF NOT EXISTS oauth2_authorization (
    id                            VARCHAR(100)  NOT NULL PRIMARY KEY,
    registered_client_id          VARCHAR(100)  NOT NULL,
    principal_name                VARCHAR(200)  NOT NULL,
    authorization_grant_type     VARCHAR(100)  NOT NULL,
    authorized_scopes             VARCHAR(1000) DEFAULT NULL,
    attributes                    LONGTEXT      DEFAULT NULL,
    state                         VARCHAR(500)  DEFAULT NULL,
    authorization_code_value      VARCHAR(500)  DEFAULT NULL,
    authorization_code_issued_at  TIMESTAMP     DEFAULT NULL,
    authorization_code_expires_at TIMESTAMP     DEFAULT NULL,
    authorization_code_metadata   VARCHAR(2000) DEFAULT NULL,
    access_token_value            TEXT          DEFAULT NULL,
    access_token_issued_at        TIMESTAMP     DEFAULT NULL,
    access_token_expires_at       TIMESTAMP     DEFAULT NULL,
    access_token_metadata         TEXT          DEFAULT NULL,
    access_token_type             VARCHAR(100)  DEFAULT NULL,
    access_token_scopes           VARCHAR(1000) DEFAULT NULL,
    oidc_id_token_value           TEXT          DEFAULT NULL,
    oidc_id_token_issued_at       TIMESTAMP     DEFAULT NULL,
    oidc_id_token_expires_at      TIMESTAMP     DEFAULT NULL,
    oidc_id_token_metadata        TEXT          DEFAULT NULL,
    refresh_token_value           TEXT          DEFAULT NULL,
    refresh_token_issued_at       TIMESTAMP     DEFAULT NULL,
    refresh_token_expires_at      TIMESTAMP     DEFAULT NULL,
    refresh_token_metadata        TEXT          DEFAULT NULL,
    user_code_value               TEXT          DEFAULT NULL,
    user_code_issued_at           TIMESTAMP     DEFAULT NULL,
    user_code_expires_at          TIMESTAMP     DEFAULT NULL,
    user_code_metadata            TEXT          DEFAULT NULL,
    device_code_value             TEXT          DEFAULT NULL,
    device_code_issued_at         TIMESTAMP     DEFAULT NULL,
    device_code_expires_at        TIMESTAMP     DEFAULT NULL,
    device_code_metadata          TEXT          DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- OAuth2 Authorization Consent
CREATE TABLE IF NOT EXISTS oauth2_authorization_consent (
    registered_client_id VARCHAR(100)  NOT NULL,
    principal_name       VARCHAR(200)  NOT NULL,
    authorities          VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- System User
CREATE TABLE IF NOT EXISTS sys_user (
    id                      BIGINT        NOT NULL PRIMARY KEY,
    username                VARCHAR(100)  NOT NULL UNIQUE,
    password                VARCHAR(255)  NOT NULL,
    enabled                 TINYINT(1)    NOT NULL DEFAULT 1,
    account_non_expired     TINYINT(1)    NOT NULL DEFAULT 1,
    account_non_locked      TINYINT(1)    NOT NULL DEFAULT 1,
    credentials_non_expired TINYINT(1)    NOT NULL DEFAULT 1,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- MCP API Key — dual-part AccessKey model (对标阿里云 AccessKey)
-- AccessKey ID (public, for lookup) + AccessKey Secret (bcrypt hash, never transmitted)
-- 永不过期(默认) 或可选设置过期时间
-- service_scope: '*' = all services, 'weather' = only weather, 'weather,climate' = both
CREATE TABLE IF NOT EXISTS mcp_api_key (
    id                      VARCHAR(64)   NOT NULL PRIMARY KEY,
    name                    VARCHAR(128)  NOT NULL,
    access_key_id           VARCHAR(32)   NOT NULL UNIQUE COMMENT 'AccessKey ID (ak-xxx, public, for lookup)',
    access_key_secret_hash  VARCHAR(256)  NOT NULL COMMENT 'AccessKey Secret bcrypt hash ({bcrypt}$2a$10$...)',
    access_key_prefix       VARCHAR(16)   NOT NULL COMMENT 'First 11 chars of AccessKey ID for prefix lookup',
    service_scope           VARCHAR(256)  NOT NULL DEFAULT '*' COMMENT 'Service scope: * = all, weather = weather only',
    description             VARCHAR(512)  DEFAULT NULL,
    created_by              VARCHAR(128)  DEFAULT NULL,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at              TIMESTAMP     DEFAULT NULL COMMENT 'NULL = never expires (AK mode, 对标阿里云)',
    enabled                 BOOLEAN       NOT NULL DEFAULT TRUE,
    last_used_at            TIMESTAMP     DEFAULT NULL,
    INDEX idx_access_key_id (access_key_id),
    INDEX idx_prefix_enabled (access_key_prefix, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Keys — dual-part AccessKey model (对标阿里云)';

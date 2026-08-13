-- Default admin user (password: admin, bcrypt hash)
INSERT IGNORE INTO sys_user (id, username, password, roles, enabled, account_non_expired, account_non_locked, credentials_non_expired)
VALUES (1, 'admin', '{bcrypt}$2a$10$v4/lTPr5mOE2OmfP9HVhWeAlHxGguZBS/rsO6n0Llzn1a2VjE6KCq', 'ADMIN', 1, 1, 1, 1);

-- 幂等保证 admin 拥有 ADMIN 角色（驱动 @PreAuthorize，解锁管理端点）
UPDATE sys_user SET roles = 'ADMIN' WHERE username = 'admin';

-- Pre-registered springai-gateway-client (confidential, supports client_credentials)
INSERT IGNORE INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
    client_authentication_methods, authorization_grant_types, redirect_uris, scopes,
    client_settings, token_settings, registration_source
) VALUES (
    'springai-gateway-client',
    'springai-gateway-client',
    CURRENT_TIMESTAMP,
    '{bcrypt}$2b$10$ABsfnCtp6Hkmzsk4myJCee0MHC/ogOWXR/DPKQMDI0VLXakrvKo46',
    NULL,
    'Spring AI MCP Gateway',
    '["client_secret_post","client_secret_basic"]',
    '["authorization_code","client_credentials","refresh_token"]',
    '["http://localhost:6274/oauth/callback","https://claude.ai/api/mcp/auth_callback","http://localhost:8080/login/oauth2/code/authserver","http://127.0.0.1:8080/login/oauth2/code/authserver"]',
    '["offline_access","mcp:read","mcp:write"]',
    '{"requireProofKey":true,"requireAuthorizationConsent":false}',
    '{"accessTokenTimeToLive":"PT24H","refreshTokenTimeToLive":"P30D"}',
    'PRE-REGISTERED'
);

-- Pre-registered PKCE public clients for MCP services (stable client_id, like Alibaba Cloud)
-- Weather MCP client: use authorization_code + PKCE, no client_credentials
INSERT IGNORE INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
    client_authentication_methods, authorization_grant_types, redirect_uris, scopes,
    client_settings, token_settings, registration_source
) VALUES (
    'mcp-weather-client',
    'mcp-weather-client',
    CURRENT_TIMESTAMP,
    NULL,
    NULL,
    'Weather MCP Service',
    '["none"]',
    '["authorization_code","refresh_token"]',
    '["http://localhost:6274/oauth/callback","https://claude.ai/api/mcp/auth_callback","http://localhost:19876/callback"]',
    '["offline_access","mcp:read","mcp:write"]',
    '{"requireProofKey":true,"requireAuthorizationConsent":false}',
    '{"accessTokenTimeToLive":"PT24H","refreshTokenTimeToLive":"P30D"}',
    'PRE-REGISTERED'
);

-- Climate MCP client: use authorization_code + PKCE, no client_credentials
INSERT IGNORE INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
    client_authentication_methods, authorization_grant_types, redirect_uris, scopes,
    client_settings, token_settings, registration_source
) VALUES (
    'mcp-climate-client',
    'mcp-climate-client',
    CURRENT_TIMESTAMP,
    NULL,
    NULL,
    'Climate MCP Service',
    '["none"]',
    '["authorization_code","refresh_token"]',
    '["http://localhost:6274/oauth/callback","https://claude.ai/api/mcp/auth_callback","http://localhost:19876/callback"]',
    '["offline_access","mcp:read","mcp:write"]',
    '{"requireProofKey":true,"requireAuthorizationConsent":false}',
    '{"accessTokenTimeToLive":"PT24H","refreshTokenTimeToLive":"P30D"}',
    'PRE-REGISTERED'
);

-- Default API Key — dual-part AccessKey model (对标阿里云 AccessKey)
-- AccessKey ID: ak-36f8ea0fc5ad9937572d (public, for request lookup)
-- AccessKey Secret: sk-8665c9bbdd338e3ce03a0fdf115fbf65685b2b94 (NEVER transmitted, only for HMAC signing)
-- ⚠️  In Bearer mode, format is: ak-36f8ea0fc5ad9937572d:sk-8665c9bbdd338e3ce03a0fdf115fbf65685b2b94
-- Hash: {bcrypt}$2a$10$UIKzscdqwqZ3geT.JWZ4IOLvjca7lkGfHzkfyZQ.t0f4Z2wHk4CR2
-- Service scope: * (all services)
-- Expires: NULL = never expires (AK mode, 对标阿里云)
INSERT IGNORE INTO mcp_api_key (id, name, access_key_id, access_key_secret_hash, access_key_prefix, service_scope, description, created_by, expires_at, enabled)
VALUES (
    '1',
    'default-dev-key',
    'ak-36f8ea0fc5ad9937572d',
    '{bcrypt}$2a$10$UIKzscdqwqZ3geT.JWZ4IOLvjca7lkGfHzkfyZQ.t0f4Z2wHk4CR2',
    'ak-36f8ea0f',
    '*',
    'Default dev API key (AK mode, never expires, 160-bit secret)',
    'system',
    NULL,
    1
);

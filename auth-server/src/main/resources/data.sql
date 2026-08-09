-- Default admin user (password: admin, bcrypt hash)
INSERT IGNORE INTO sys_user (id, username, password, enabled, account_non_expired, account_non_locked, credentials_non_expired)
VALUES (1, 'admin', '{bcrypt}$2a$10$v4/lTPr5mOE2OmfP9HVhWeAlHxGguZBS/rsO6n0Llzn1a2VjE6KCq', 1, 1, 1, 1);

-- Pre-registered springai-gateway-client
INSERT IGNORE INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
    client_authentication_methods, authorization_grant_types, redirect_uris, scopes,
    client_settings, token_settings
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
    '{"accessTokenTimeToLive":"PT24H","refreshTokenTimeToLive":"P30D"}'
);

-- Pre-registered PKCE public clients for MCP services (stable client_id, like Alibaba Cloud)
-- Weather MCP client: use authorization_code + PKCE, no client_credentials
INSERT IGNORE INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
    client_authentication_methods, authorization_grant_types, redirect_uris, scopes,
    client_settings, token_settings
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
    '{"accessTokenTimeToLive":"PT24H","refreshTokenTimeToLive":"P30D"}'
);

-- Climate MCP client: use authorization_code + PKCE, no client_credentials
INSERT IGNORE INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
    client_authentication_methods, authorization_grant_types, redirect_uris, scopes,
    client_settings, token_settings
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
    '{"accessTokenTimeToLive":"PT24H","refreshTokenTimeToLive":"P30D"}'
);

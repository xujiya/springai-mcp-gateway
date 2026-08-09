# Auth Server — Spring Authorization Server + DCR + MySQL

> v0.13.1 封板 | 端口: 9090 | 内部 issuer: `http://localhost:9090` | 公开: `http://localhost:8080/api-gateway/auth`

## 功能

- **OAuth2 Authorization Server** (JWT RS256)
- **DCR** (Dynamic Client Registration) — 可通过 `mcp.dcr.enabled` 开关
- **两层客户端模型**: DCR 客户端禁止 `client_credentials`; 预注册客户端可用
- **Public Client (PKCE)** 支持 MCP SDK
- **MySQL 持久化** (mcp_auth 数据库)
- **LoginController** — text/html Content-Type 的 Vue 登录页
- **可配置 DCR 安全参数**

## DCR 两层客户端模型

| 类型 | 注册方式 | Grant Types | 用途 |
|------|---------|-------------|------|
| DCR 动态注册 | POST /oauth2/register | authorization_code + refresh_token | MCP 客户端自动注册 |
| 预注册管理客户端 | MySQL data.sql | client_credentials | 服务间调用 |
| 预注册 PKCE 客户端 | MySQL data.sql | authorization_code + refresh_token | pi / Claude Code |

## 预注册客户端

| client_id | 类型 | Grant Types | Scope |
|-----------|------|-------------|-------|
| `springai-gateway-client` | confidential | client_credentials | mcp:read |
| `mcp-weather-client` | public (PKCE) | authorization_code + refresh_token | mcp:read mcp:write |
| `mcp-climate-client` | public (PKCE) | authorization_code + refresh_token | mcp:read mcp:write |

redirect_uris 包含 `http://localhost:19876/callback` (pi OAuth2 回调)。

## 配置

```yaml
mcp:
  dcr:
    enabled: true                      # 生产设 false (预注册模式)
    client-secret-expires-in: 90d      # DCR client_secret 有效期
    access-token-time-to-live: 24h     # Access Token TTL
    refresh-token-time-to-live: 1h     # Refresh Token TTL

server:
  servlet:
    session:
      cookie:
        name: MCP_AUTHORIZATION_SERVER_SESSIONID
        path: /api-gateway/auth
        http-only: true
        same-site: lax
```

## Cookie 安全

- `Path=/api-gateway/auth` — 限制作用域
- `HttpOnly` — JavaScript 不可读
- `SameSite=Lax` — 防 CSRF
- 生产环境加 `Secure: true`

## CORS

```
AllowedOriginPatterns: http://localhost:*, http://127.0.0.1:*, null
```

`null` 用于浏览器表单 POST 提交后的 `Origin: null` (W3C opaque origin)。

## MySQL Schema (mcp_auth)

| 表 | 说明 |
|----|------|
| `oauth2_registered_client` | OAuth2 客户端注册信息 |
| `oauth2_authorization` | 授权码 / Token 存储 |
| `oauth2_authorization_consent` | 用户授权同意记录 |
| `sys_user` | 系统用户 (admin 登录) |
| `mcp_api_key` | API Key (access_key_id, access_key_secret_hash, access_key_prefix) |

## 关键文件

```
auth-server/src/main/java/
├── config/
│   ├── AuthorizationServerConfiguration.java
│   ├── McpAuthorizationServerConfigurer.java      # DCR 可配置参数
│   └── OAuth2PersistenceConfig.java                # MySQL 持久化
├── controller/
│   ├── LoginController.java                        # Vue 登录页 (text/html)
│   ├── AuthInfoController.java                     # 脱敏 auth-info
│   └── ClientRegistrationAdminController.java      # 客户端管理
├── repository/
│   ├── MybatisRegisteredClientRepository.java      # ClientSettings/TokenSettings 手动解析
│   ├── MybatisUserDetailsService.java
│   ├── MybatisOAuth2AuthorizationService.java
│   └── MybatisOAuth2AuthorizationConsentService.java
└── entity/
    ├── OAuth2AuthorizationEntity.java
    ├── RegisteredClientEntity.java
    └── SysUser.java
```

## 获取 Token

### client_credentials (预注册客户端)

```bash
curl -X POST http://localhost:8080/api-gateway/auth/oauth2/token \
  -d "grant_type=client_credentials&client_id=springai-gateway-client&client_secret=secret&scope=mcp:read"
```

### authorization_code + PKCE (MCP 客户端)

完整流程见 [ARCHITECTURE.md](../ARCHITECTURE.md) §12。

## Security Hardening

- DCR `denyAll()` → 403 JSON (不泄露内部地址)
- auth-info 脱敏 (不返回 clientId/redirectUri)
- Session Cookie: SameSite=Lax + Path 限制
- CORS 限制到 localhost/127.0.0.1/null
- 两层客户端模型: DCR 禁止 client_credentials

# 公网端口 :8080 安全评估 — 封板版

> 封板时间: 2026-08-09  
> 版本范围: v0.7.0 → v0.14.0  
> 范围: HAProxy:8080 暴露的所有 HTTP 接口  
> 方法: 黑盒扫描 + 白盒代码审查  
> 状态: **封板 (Final Freeze)**

---

## 接口全量清单

> 共 29 个公开接口，覆盖 OAuth2 AS、Vue 前端、MCP Gateway Admin、MCP Tools 四大子系统。

| # | URL | Method | 认证方式 | 敏感度 | 风险 | 状态 |
|---|-----|--------|----------|--------|------|------|
| 1 | `/` | GET | 无 → 404 | — | — | ✅ 已加固 (M4) |
| 2 | `/api-gateway/auth/.well-known/oauth-authorization-server` | GET | Public | 🟢 RFC 8414 标准 | LOW | ✅ 正常 |
| 3 | `/api-gateway/auth/oauth2/jwks` | GET | Public | 🟢 RFC 7517 标准 | LOW | ✅ 正常 |
| 4 | `/api-gateway/auth/oauth2/authorize` | GET | Session | 🟢 标准授权流程 | LOW | ✅ 正常 |
| 5 | `/api-gateway/auth/oauth2/token` | POST | client_auth | 🟢 标准令牌流程 | LOW | ✅ 正常 |
| 6 | `/api-gateway/auth/oauth2/introspect` | POST | client_auth | 🟢 标准内省流程 | LOW | ✅ 正常 |
| 7 | `/api-gateway/auth/oauth2/revoke` | POST | client_auth | 🟢 标准撤销流程 | LOW | ✅ 正常 |
| 8 | `/api-gateway/auth/oauth2/register` | POST | denyAll / open | 🟡 DCR 受控 | LOW | ✅ 已加固 (H1) |
| 9 | `/api-gateway/auth/login` | GET | Session | 🟢 Vue 登录页 | LOW | ✅ 正常 |
| 10 | `/api-gateway/auth/login` | POST | Session | 🟡 用户名/密码认证 | MEDIUM | ✅ 正常 |
| 11 | `/api-gateway/auth/oauth2/auth-info` | GET | Session | 🟢 已脱敏 | LOW | ✅ 已加固 (H2) |
| 12 | `/api-gateway/vue/` | GET | Public | 🟢 Vue 登录 UI 静态资源 | LOW | ✅ 正常 |
| 13 | `/api-gateway/admin/` | GET | Public | 🟡 UI 公开,API 需 admin token | LOW | ✅ 正常 |
| 14 | `/mcp-gateway/admin/login` | POST | None (username/password) | 🔴 管理员认证入口 | HIGH | ⚠️ 见 M6 |
| 15 | `/mcp-gateway/admin/users` | GET | Admin token | 🔴 用户列表 | MEDIUM | ✅ 正常 |
| 16 | `/mcp-gateway/admin/users` | POST | Admin token | 🔴 用户创建 | MEDIUM | ✅ 正常 |
| 17 | `/mcp-gateway/admin/users` | PUT | Admin token | 🔴 用户修改 | MEDIUM | ✅ 正常 |
| 18 | `/mcp-gateway/admin/users` | DELETE | Admin token | 🔴 用户删除 | MEDIUM | ✅ 正常 |
| 19 | `/mcp-gateway/admin/clients` | GET | Admin token | 🔴 客户端列表 | MEDIUM | ✅ 正常 |
| 20 | `/mcp-gateway/admin/clients` | POST | Admin token | 🔴 客户端创建 | MEDIUM | ✅ 正常 |
| 21 | `/mcp-gateway/admin/clients` | DELETE | Admin token | 🔴 客户端删除 | MEDIUM | ✅ 正常 |
| 22 | `/mcp-gateway/admin/system` | GET | Admin token | 🔴 系统信息 | MEDIUM | ✅ 正常 |
| 23 | `/mcp-gateway/admin/api-keys` | GET | Admin token | 🔴 API Key 列表 | MEDIUM | ✅ 正常 |
| 24 | `/mcp-gateway/admin/api-keys` | POST | Admin token | 🔴 API Key 创建 | MEDIUM | ✅ 正常 |
| 25 | `/mcp-gateway/admin/api-keys` | DELETE | Admin token | 🔴 API Key 删除 | MEDIUM | ✅ 正常 |
| 26 | `/mcp-gateway/weather/mcp` | POST | Bearer (JWT / API Key) | 🔴 MCP 工具调用入口 | MEDIUM | ✅ 正常 |
| 27 | `/mcp-gateway/climate/mcp` | POST | Bearer (JWT / API Key) | 🔴 MCP 工具调用入口 | MEDIUM | ✅ 正常 |
| 28 | `/mcp-gateway/weather/.well-known/oauth-protected-resource` | GET | Public | 🟢 RFC 9728 标准 | LOW | ✅ 正常 |
| 29 | `/mcp-gateway/climate/.well-known/oauth-protected-resource` | GET | Public | 🟢 RFC 9728 标准 | LOW | ✅ 正常 |

---

## 安全加固记录 (v0.7.0 — v0.13.1)

### H1: DCR denyAll → 403 JSON

- **原问题**: `POST /oauth2/register` 被 `denyAll()` 拦截后，Spring Security 默认返回 `302 Location: http://localhost:9090/vue-login`，泄露内部服务地址
- **修复**: 自定义 `AccessDeniedHandler`，返回 `403` + JSON body，不再触发 302 重定向
- **版本**: v0.7.0
- **代码路径**: `McpAccessDeniedHandler implements AccessDeniedHandler`

### H2: auth-info 脱敏

- **原问题**: `GET /oauth2/auth-info` 返回完整 `clientId`、`redirectUri`、`clientName`，任何持有 session 的用户可获取
- **修复**: 从响应中移除 `clientId` 和 `redirectUri` 字段，仅保留 `pending` + `scope` 等非敏感信息
- **版本**: v0.7.0

### M1: Session Cookie 加固

- **原问题**: Cookie `Path=/;` 无 `SameSite`、无 `Secure`
- **修复**:
  ```yaml
  server:
    servlet:
      session:
        cookie:
          same-site: lax
          path: /api-gateway/auth
          http-only: true
  ```
- **版本**: v0.8.0

### M2: CORS 限制

- **原问题**: `allowedOriginPatterns: *` + `allowCredentials: true`，任意 Origin 可跨域携带 Cookie
- **修复**:
  ```java
  configuration.setAllowedOriginPatterns(List.of(
      "http://localhost:*",
      "http://127.0.0.1:*",
      "null"    // W3C opaque origin: 浏览器 form POST 重定向后 Origin 为 null
  ));
  ```
- **说明**: `null` 模式必须保留 — OAuth2 authorize 重定向后浏览器发送的 `Origin: null`（W3C 规范定义的 opaque origin），否则重定向回调的 CORS preflight 会失败
- **版本**: v0.8.0

### M3: HAProxy del-header Server

- **原问题**: 响应头 `Server: nginx/1.27.4` 泄露版本
- **修复**: `haproxy.cfg` 添加 `http-response del-header Server`
- **版本**: v0.8.0

### M4: 根路径 / → 404

- **原问题**: `GET /` 返回 HTML 页面,暴露完整路由架构(`/api-gateway/auth/**`、`/mcp-gateway/mcp` 等)
- **修复**: HAProxy 配置 `default_backend api_gw` (API Gateway 处理 404)
- **版本**: v0.8.0

### M5: LoginController Content-Type

- **原问题**: 登录响应 Content-Type 不明确
- **修复**: 显式设置 `text/html; charset=UTF-8`
- **版本**: v0.8.0

### Two-tier 客户端模型 (v0.9.0)

DCR 动态注册与预注册客户端实施分层权限控制：

| 客户端类型 | 注册方式 | 授权类型 | client_credentials | 说明 |
|-----------|---------|---------|-------------------|------|
| DCR 客户端 | `POST /oauth2/register` | `authorization_code` + `PKCE` | ❌ 禁止 | 自动分配，权限最低 |
| Public 客户端 | 预注册 | `authorization_code` + `refresh_token` | ❌ 禁止 | 需 PKCE，无 secret |
| Admin 客户端 | 预注册 | `client_credentials` | ✅ 允许 | 人工分配，权限最高 |

- DCR 客户端禁止获取 `client_credentials` grant，即使 DCR 开放也无法提权
- Public 客户端仅限 `authorization_code` + `refresh_token`，强制 PKCE
- 预注册 admin 客户端可拥有 `client_credentials`，需人工配置

### API Key 安全体系 (v0.11.0)

双段式 AccessKey 模型，替代简单 token 方案：

**格式**: `ak-<20hex>:sk-<40hex>`

- `ak-` 前缀: Access Key ID（20 hex = 80 bit），用于数据库查找
- `sk-` 前缀: Secret Key（40 hex = 160 bit），仅创建时返回一次
- 总密钥熵: 160 bit secret

**安全机制**:
- 数据库仅存储 `sk-` 的 bcrypt hash，不存明文
- 验证时: 查找 `ak-` → bcrypt.compare(`sk-`, hash)
- 速率限制: 10 次失败 → 5 分钟自动封禁
- Admin token 比较: bcrypt（时序安全，防 timing attack）
- `BearerTokenResolver`: `ak-*` 和 `adm-*` 前缀的 token 不走 JWT 解析，直接路由到 API Key / Admin 认证

**创建流程**:
```
POST /mcp-gateway/admin/api-keys
→ 201 { "accessKey": "ak-xxx:sk-yyy", ... }    ← sk 仅此一次返回
→ DB  { "accessKeyId": "ak-xxx", "secretHash": "$2a$10$..." }
```

### 双 SecurityFilterChain 架构 (v0.12.1)

两条独立的 Spring Security 过滤链，职责分离：

| Chain | 匹配路径 | JWT | API Key | 说明 |
|-------|---------|-----|---------|------|
| Chain 0 | `/admin/**` | ❌ 不加载 | ❌ 不加载 | `permitAll`，Controller 自行验证 admin token |
| Chain 1 | 其他所有路径 | ✅ | ✅ | 标准 JWT + API Key 认证 |

- `/admin/**` 路径不走 JWT `BearerTokenResolver`，避免 admin token 被误解析为 JWT
- Admin Controller 内部自行校验 `adm-*` token（bcrypt hash 比较）
- Chain 0 的 `requestMatchers` 精确匹配 `/admin/**`，不影响其他路径

### MCP SDK Issuer 修复 (v0.13.1)

- **原问题**: `@modelcontextprotocol/client` SDK 内部做 `origin === issuer` 严格比较
  - 经过 HAProxy 网关代理后,OAuth2 issuer 是 path-based URL(如 `http://host:8080/api-gateway/auth`)
  - SDK 用 `new URL(issuer).origin` 只取到 `http://host:8080`，与 issuer 不匹配 → 抛出 `IssuerMismatch` 错误
- **修复**: Monkey-patch SDK 的 issuer 验证逻辑，允许 path-based issuer（网关代理场景）
- **影响**: 所有使用 `@modelcontextprotocol/client` 的客户端需应用此 patch
- **⚠️ 升级警告**: SDK 升级后需重新应用 patch，否则 MCP OAuth2 流程会中断

---

## 剩余风险

### 🔴 HIGH (1 项)

#### H3: 内部端口 :9090/:9092/:9093 直接可达

- **现象**: 所有 Spring Boot 内部端口可从本机直接访问，绕过 HAProxy 全部安全控制
  ```
  :9090 → 302 (auth-server, Spring Security 重定向)
  :9092 → 404 (weather-server, MCP 工具)
  :9093 → 404 (climate-server, MCP 工具)
  ```
- **危害**: 
  - 绕过 HAProxy 的 CORS、速率限制、del-header Server
  - 绕过 Spring Security 的 forward-headers 处理
  - 直接访问内部 API，无审计日志
- **修复**: 
  ```yaml
  # 每个 Spring Boot 服务
  server:
    address: 127.0.0.1
  ```
  ```bash
  # 防火墙: 阻止外部访问内部端口
  iptables -A INPUT -p tcp --dport 9090 -s !127.0.0.1 -j DROP
  iptables -A INPUT -p tcp --dport 9092 -s !127.0.0.1 -j DROP
  iptables -A INPUT -p tcp --dport 9093 -s !127.0.0.1 -j DROP
  ```

### 🟡 MEDIUM (4 项)

#### M5: Token 端点无速率限制

- **现象**: 连续错误 secret 请求无延迟/封禁
- **危害**: 暴力破解 `client_secret`
- **修复**: HAProxy 层 rate limiting
  ```haproxy
  # 在 frontend 或 backend 中添加
  # rate-limit sessions per source IP
  ```

#### M6: 默认管理员凭据 admin/admin

- **现象**: MCP Gateway Admin 默认用户名密码为 `admin/admin`
- **危害**: 未修改则任何人可获取 admin token，完全控制用户/客户端/API Key
- **修复**: 生产环境必须修改，并通过 `mcp.admin-token-hash` 配置 bcrypt hash

#### M7: API Key HMAC 模式未完成

- **现象**: 当前仅存储 `sk-` 的 bcrypt hash，无法执行 HMAC-SHA256 签名验证
- **危害**: 无法支持需要 HMAC 签名的 MCP 工具调用模式
- **修复**: 增加 AES-GCM 加密存储 `sk-` 明文，用于 HMAC 计算
  - 存储: `AES-GCM-encrypt(sk, masterKey)` → 可逆，HMAC 时解密
  - 验证: `HMAC-SHA256(decrypt(stored), message)` → 时序安全
  - **注意**: bcrypt hash 仍保留用于简单 Bearer token 认证

#### M8: Cookie Secure 属性未启用

- **现象**: `cookie.secure: false`（开发环境 HTTP）
- **危害**: Cookie 在 HTTP 连接上可被中间人窃取
- **修复**: 生产环境 `cookie.secure: true`（需 HTTPS）

### 🟢 LOW (4 项)

#### L1: JWT Payload 明文可解码

- **评估**: JWT 标准设计如此，RS256 签名保证不可篡改。可接受。
- **改进**: 生产环境 `iss` 应使用 HTTPS URL

#### L2: JWKS 端点公开

- **评估**: RFC 7517 标准，公钥本就公开。可接受。

#### L3: AS Metadata 暴露 token_endpoint_auth_methods_supported

- **评估**: RFC 8414 标准要求，帮助客户端选择认证方法。可接受。

#### L4: Vue 前端 500 错误含 requestId

- **现象**: `{"status": 500, "error": "Internal Server Error", "requestId": "0959155c-167"}`
- **评估**: requestId 对调试有用，不泄露内部信息。可接受。
- **改进**: 生产环境可关闭

---

## 架构安全总览

```
                         ┌──────────────────────────────────────────────────┐
                         │              HAProxy :8080 (唯一公网入口)            │
                         │  del-header Server, / → default_backend api_gw      │
                         └──────────┬───────────────────────┬───────────────┘
                                    │                       │
                    ┌───────────────┴────────┐    ┌────────┴────────────┐
                    │  /api-gateway/*    │    │  /mcp-gateway/*     │
                    │  → proxy_pass :9090     │    │  → proxy_pass :9092 │
                    │                         │    │               :9093 │
                    └───────────┬────────────┘    └────────┬────────────┘
                                │                          │
              ┌─────────────────┴──────────────┐  ┌───────┴────────────────┐
              │     auth-server :9090           │  │  MCP Gateway          │
              │                                │  │  weather-server :9092  │
              │  ┌─ OAuth2 AS (RFC 8414) ───┐ │  │  climate-server :9093 │
              │  │ .well-known/oauth-auth.. │ │  │                        │
              │  │ /oauth2/jwks            │ │  │  ┌─ Admin API ───────┐ │
              │  │ /oauth2/authorize       │ │  │  │ POST /admin/login │ │
              │  │ /oauth2/token           │ │  │  │ *   /admin/users  │ │
              │  │ /oauth2/introspect      │ │  │  │ *   /admin/clients│ │
              │  │ /oauth2/revoke          │ │  │  │ *   /admin/system │ │
              │  │ /oauth2/register (deny) │ │  │  │ *   /admin/api-keys│ │
              │  └────────────────────────┘ │  │  └───────────────────┘ │
              │                                │  │                        │
              │  ┌─ Login ──────────────────┐ │  │  ┌─ MCP Tools ──────┐ │
              │  │ GET  /login (Vue page)   │ │  │  │ POST /weather/mcp│ │
              │  │ POST /login (credentials)│ │  │  │ POST /climate/mcp│ │
              │  └──────────────────────────┘ │  │  └──────────────────┘ │
              │                                │  │                        │
              │  ┌─ Vue Static ─────────────┐ │  │  ┌─ RFC 9728 ───────┐ │
              │  │ /vue/ (login UI)    │ │  │  │ .well-known/oauth│ │
              │  │ /admin/ (admin UI)  │ │  │  │ -protected-res.  │ │
              │  └──────────────────────────┘ │  │  └──────────────────┘ │
              └────────────────────────────────┘  └────────────────────────┘
```

---

## 认证流程概览

### OAuth2 Authorization Code + PKCE (Public Client)

```
Client                          HAProxy:8080                    auth-server:9090
  │                                 │                              │
  │ GET /oauth2/authorize           │                              │
  │ + code_challenge (PKCE)         │                              │
  │────────────────────────────────→│─────────────────────────────→│
  │                                 │                              │
  │                302 → /login (Session Cookie)                    │
  │←────────────────────────────────│←─────────────────────────────│
  │                                 │                              │
  │ POST /login (user credentials)  │                              │
  │────────────────────────────────→│─────────────────────────────→│
  │                                 │                              │
  │                302 → /oauth2/authorize (consent)               │
  │←────────────────────────────────│←─────────────────────────────│
  │                                 │                              │
  │                 302 → redirect_uri?code=xxx                    │
  │←────────────────────────────────│←─────────────────────────────│
  │                                 │                              │
  │ POST /oauth2/token              │                              │
  │ + code_verifier (PKCE)          │                              │
  │────────────────────────────────→│─────────────────────────────→│
  │                                 │                              │
  │                 { access_token: JWT }                          │
  │←────────────────────────────────│←─────────────────────────────│
```

### MCP Tool 调用 (JWT 或 API Key)

```
Client                          HAProxy:8080                    mcp-server:9092
  │                                 │                              │
  │ POST /weather/mcp               │                              │
  │ Authorization: Bearer <JWT>     │                              │
  │────────────────────────────────→│─────────────────────────────→│
  │                                 │   (Chain 1: JWT validation)  │
  │                 MCP Response    │                              │
  │←────────────────────────────────│←─────────────────────────────│
  │                                 │                              │
  │ POST /weather/mcp               │                              │
  │ Authorization: Bearer ak-:sk-   │                              │
  │────────────────────────────────→│─────────────────────────────→│
  │                                 │   (Chain 1: API Key lookup)  │
  │                 MCP Response    │                              │
  │←────────────────────────────────│←─────────────────────────────│
```

### Admin 操作 (Admin Token)

```
Admin                           HAProxy:8080                    mcp-server:9092
  │                                 │                              │
  │ POST /admin/login               │                              │
  │ { username, password }          │                              │
  │────────────────────────────────→│─────────────────────────────→│
  │                                 │   (Chain 0: permitAll)       │
  │                 { token: adm-xxx }                             │
  │←────────────────────────────────│←─────────────────────────────│
  │                                 │                              │
  │ GET /admin/users                │                              │
  │ Authorization: Bearer adm-xxx   │                              │
  │────────────────────────────────→│─────────────────────────────→│
  │                                 │   (Chain 0: Controller       │
  │                                 │    self-validates adm-*)     │
  │                 [ users... ]    │                              │
  │←────────────────────────────────│←─────────────────────────────│
```

---

## 风险汇总

| 等级 | 数量 | 项目 |
|------|------|------|
| 🔴 HIGH | 1 | H3: 内部端口直接可达 |
| 🟡 MEDIUM | 4 | M5: Token 无速率限制, M6: 默认 admin 凭据, M7: API Key HMAC 未完成, M8: Cookie Secure 未启用 |
| 🟢 LOW | 4 | L1: JWT 明文, L2: JWKS 公开, L3: AS Metadata, L4: requestId 泄露 |

**已修复 (本次封板前)**:

| 编号 | 原风险 | 修复版本 |
|------|--------|---------|
| H1 | DCR 302 泄露 localhost:9090 → 403 JSON | v0.7.0 |
| H2 | auth-info 泄露 clientId/redirectUri → 脱敏 | v0.7.0 |
| M1 | Cookie 缺 SameSite/Path/Secure | v0.8.0 |
| M2 | CORS * → localhost/null 限制 | v0.8.0 |
| M3 | HAProxy 版本泄露 → del-header Server | v0.8.0 |
| M4 | 根路径泄露架构 → 404 | v0.8.0 |
| M5-orig | LoginController Content-Type | v0.8.0 |

---

## 生产部署清单

### 网络层

- [ ] 内部端口绑定 `127.0.0.1`（`server.address: 127.0.0.1`，每个 Spring Boot 服务）
- [ ] 防火墙规则阻止外部访问 9090/9092/9093
- [ ] HAProxy 启用 TLS (HTTPS)，配置证书
- [ ] HAProxy rate limiting 配置 `/oauth2/token` 速率限制

### 应用层

- [ ] `cookie.secure: true`（需 HTTPS 先就绪）
- [ ] 修改默认管理员密码（`admin/admin` → 强密码）
- [ ] 配置 `mcp.admin-token-hash`（bcrypt hash，替代明文比较）
- [ ] DCR 关闭：`mcp.dcr.enabled: false`
- [ ] CORS Origin 限制为生产域名（移除 `localhost:*`）
- [ ] AES-GCM 加密存储 API Key secret（支持 HMAC 模式）
- [ ] JWT issuer 使用 HTTPS URL

### MCP SDK

- [ ] 重新应用 MCP SDK issuer patch（`@modelcontextprotocol/client` 版本升级后）
- [ ] 验证 MCP 客户端 OAuth2 流程端到端可用

### 验证

- [ ] 黑盒扫描确认 `/` 返回 404
- [ ] 黑盒扫描确认 9090/9092/9093 外部不可达
- [ ] 黑盒扫描确认 `Server` 头无版本号
- [ ] 验证 DCR 端点返回 403 JSON（非 302）
- [ ] 验证 auth-info 响应无 clientId/redirectUri
- [ ] 验证 Cookie 属性: SameSite=Lax, Path=/api-gateway/auth, HttpOnly, Secure
- [ ] 验证 CORS 拒绝未知 Origin
- [ ] 验证 Token 端点速率限制生效
- [ ] 验证 admin 默认密码已修改
- [ ] 验证 API Key 创建仅返回一次 secret

---

> **封板签名**: 本文档为 :8080 安全评估的最终冻结版本，涵盖 v0.7.0 至 v0.13.1 全部安全加固。后续变更需新建版本。

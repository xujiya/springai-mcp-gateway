# 公网端口 :8080 安全评估

> 扫描时间: 2026-08-09  
> 范围: nginx:8080 暴露的所有 HTTP 接口  
> 方法: 黑盒扫描 + 白盒代码审查

---

## 📋 接口清单

| # | URL | 方法 | 认证 | 敏感度 | 风险 |
|---|-----|------|------|--------|------|
| 1 | `/` | GET | 无 | ⚠️ 泄露架构 | MEDIUM |
| 2 | `/api-gateway/ecso/auth/.well-known/oauth-authorization-server` | GET | 无 | 🟢 公开标准 | LOW |
| 3 | `/api-gateway/ecso/auth/oauth2/jwks` | GET | 无 | 🟢 公开标准 | LOW |
| 4 | `/api-gateway/ecso/auth/oauth2/authorize` | GET | Session | 🟢 标准流程 | LOW |
| 5 | `/api-gateway/ecso/auth/oauth2/token` | POST | client_auth | 🟢 标准流程 | LOW |
| 6 | `/api-gateway/ecso/auth/oauth2/introspect` | POST | client_auth | 🟢 标准流程 | LOW |
| 7 | `/api-gateway/ecso/auth/oauth2/revoke` | POST | client_auth | 🟢 标准流程 | LOW |
| 8 | `/api-gateway/ecso/auth/oauth2/register` | POST | 🔴 denyAll | 🟢 已关闭 | LOW |
| 9 | `/api-gateway/ecso/auth/vue-login` | GET | Session | 🟡 登录页 | LOW |
| 10 | `/api-gateway/ecso/auth/login` | POST | Session | 🟡 认证入口 | LOW |
| 11 | `/api-gateway/ecso/auth/oauth2/auth-info` | GET | Session | 🔴 **泄露clientId** | **HIGH** |
| 12 | `/api-gateway/ecso/auth/oauth2/admin/clients` | * | ADMIN | 🟡 管理端点 | MEDIUM |
| 13 | `/api-gateway/ecso/vue/` | GET | 无 | 🟡 前端静态 | LOW |
| 14 | `/mcp-gateway/weather/mcp` | POST | Bearer | 🔴 **MCP入口** | MEDIUM |
| 15 | `/mcp-gateway/climate/mcp` | POST | Bearer | 🔴 **MCP入口** | MEDIUM |
| 16 | `/mcp-gateway/mcp` | POST | Bearer | 🔴 **MCP入口** | MEDIUM |
| 17 | `/mcp-gateway/{svc}/.well-known/oauth-protected-resource` | GET | 无 | 🟢 RFC 9728 | LOW |
| 18 | `/mcp-gateway/.well-known/oauth-protected-resource/mcp` | GET | 无 | 🟢 RFC 9728 | LOW |

---

## 🔴 HIGH 风险 (3项)

### H1. 302 Location 泄露内部地址 `localhost:9090`

**现象**: 访问已关闭的 DCR 端点，302 Location 指向内部 auth-server:
```
POST /api-gateway/ecso/auth/oauth2/register
→ 302 Location: http://localhost:9090/vue-login;SESSIONID=xxx
```

**危害**: 攻击者获知内部服务端口 9090，可直接攻击。

**根因**: `AuthorizationServerConfiguration` 对 `/oauth2/register` 设了 `.denyAll()`，
但 Spring Security 的 302 跳转仍使用 auth-server 内部地址 (`server.port=9090`)。
`forward-headers-strategy: framework` 仅处理代理转发，不处理 Spring Security 自身的 302。

**修复**: 
```yaml
server:
  servlet:
    session:
      cookie:
        path: /api-gateway/ecso/auth   # 限制cookie作用域
```
+ 自定义 `AuthenticationSuccessHandler` / `LoginUrlAuthenticationEntryPoint` 使用 public URL

---

### H2. `/oauth2/auth-info` 泄露 client_id + redirect_uri

**现象**: 任何持有 session 的用户（登录前）都能获取:
```json
{
  "scope": "mcp:read",
  "pending": true,
  "redirectUri": "http://localhost:6274/oauth/callback",
  "clientName": "Weather MCP Service",
  "clientId": "mcp-weather-client"
}
```

**危害**: 
- 暴露预注册 `clientId`，攻击者可用此发起 authorize 请求（虽需用户登录确认）
- 暴露 `redirectUri`，可用于钓鱼攻击构造
- 暴露 `clientName`，信息收集

**修复**: 该端点仅供前端登录页使用，应限制 CORS 或移除敏感字段:
```java
// 返回时脱敏
return Map.of("pending", authorization.isPending(), "scope", scope);
// 不返回 clientId, redirectUri, clientName
```

---

### H3. 内部端口 :9090/:9092/:9093 直接可达

**现象**: 所有内部端口可从本机直接访问，无需经过 nginx:
```
:9090 → 302 (auth-server)
:9092 → 404 (weather-server)  
:9093 → 404 (climate-server)
```

**危害**: 绕过 nginx 的所有安全控制，直接访问内部服务。

**修复**: 生产环境绑定 `server.address=127.0.0.1` + 防火墙规则:
```yaml
# auth-server
server:
  address: 127.0.0.1  # 仅本机
```
```bash
# 防火墙: 阻止外部访问内部端口
iptables -A INPUT -p tcp --dport 9090 -s !127.0.0.1 -j DROP
```

---

## 🟡 MEDIUM 风险 (5项)

### M1. Session Cookie 缺少安全属性

**现象**:
```
Set-Cookie: MCP_AUTHORIZATION_SERVER_SESSIONID=xxx; Path=/; HttpOnly
```

**缺失**:
- ❌ `Secure` — cookie 在 HTTP 下可被中间人窃取
- ❌ `SameSite` — 跨站请求可携带 cookie (CSRF)
- ⚠️ `Path=/` — cookie 对所有路径生效（应限制为 `/api-gateway/ecso/auth`）

**修复**:
```yaml
server:
  servlet:
    session:
      cookie:
        secure: true      # HTTPS only
        same-site: lax    # 防CSRF
        path: /api-gateway/ecso/auth  # 限制作用域
        http-only: true   # 已有
```

---

### M2. CORS 允许任意 Origin + Credentials

**现象**:
```
Origin: http://evil.com
→ Access-Control-Allow-Origin: http://evil.com
→ Access-Control-Allow-Credentials: true
```

**危害**: 任意恶意网站可携带用户 cookie 发起跨域请求。

**修复**: 限制 CORS 到已知前端 Origin:
```java
configuration.setAllowedOriginPatterns(List.of(
    "http://localhost:8080",    // 开发
    "https://your-domain.com"   // 生产
));
```

---

### M3. nginx 版本泄露

**现象**: `Server: nginx/1.27.4`

**修复**: `nginx.conf` 添加 `server_tokens off;`

---

### M4. API Gateway 首页泄露架构信息

**现象**: `GET /` 返回:
```html
<h1>ECSO Gateway</h1>
<p><a href="/api-gateway/ecso/vue">Login</a></p>
<p><code>/api-gateway/ecso/auth/**</code> - OAuth2 + DCR</p>
<p><code>/mcp-gateway/mcp</code> - MCP Tools</p>
```

**危害**: 暴露完整路由架构，攻击者无需探测。

**修复**: 删除或替换为空白页/404。

---

### M5. Token 端点无速率限制

**现象**: 5次连续错误 secret 请求，每次 ~400ms 响应，无延迟/封禁。

**危害**: 可暴力破解 client_secret。

**修复**: 
- Spring Security 内置 `AuthenticationFailureHandler` 增加延迟
- nginx 层: `limit_req_zone` + `limit_req`
```nginx
limit_req_zone $binary_remote_addr zone=token:10m rate=5r/m;
location /api-gateway/ecso/auth/oauth2/token {
    limit_req zone=token burst=3 nodelay;
}
```

---

## 🟢 LOW 风险 (4项)

### L1. JWT payload 可被任何人解码

**现象**: JWT 无加密，payload 明文:
```json
{
  "iss": "http://localhost:8080/api-gateway/ecso/auth",
  "sub": "springai-gateway-client",
  "aud": "springai-gateway-client",
  "exp": "2026-08-09T03:04:05.000Z",
  "scope": ["mcp:read"]
}
```

**评估**: JWT 标准设计如此，签名为 RS256 保证不可篡改。可接受。
**改进**: 生产环境 `iss` 应使用 HTTPS URL。

---

### L2. JWKS 端点公开

**评估**: RFC 7517 标准，公钥本就公开。可接受。

---

### L3. AS Metadata 暴露 `token_endpoint_auth_methods_supported`

**现象**: 包含 `tls_client_auth`, `self_signed_tls_client_auth` 等高级方法。

**评估**: RFC 8414 标准要求，帮助客户端选择认证方法。可接受。

---

### L4. Vue 前端 500 错误泄露 requestId

**现象**: 
```json
{"status": 500, "error": "Internal Server Error", "requestId": "0959155c-167"}
```

**评估**: requestId 对调试有用，不泄露内部信息。可接受。
**改进**: 生产环境可关闭 requestId。

---

## 📊 总结

| 等级 | 数量 | 最紧急 |
|------|------|--------|
| 🔴 HIGH | 3 | H1: 302泄露内部地址, H2: auth-info泄露clientId, H3: 内部端口可达 |
| 🟡 MEDIUM | 5 | M1: Cookie安全属性, M2: CORS *, M3: nginx版本, M4: 架构泄露, M5: 无速率限制 |
| 🟢 LOW | 4 | L1-L4: 标准行为，可接受 |

**最优先修复顺序**:
1. **H1** — 302 Location 使用 public URL（避免泄露 :9090）
2. **H2** — auth-info 脱敏（不返回 clientId/redirectUri）
3. **M1** — Cookie Secure + SameSite + Path
4. **M2** — CORS 限制 Origin
5. **M5** — Token 速率限制
6. **H3** — 内部端口绑定 127.0.0.1 + 防火墙
7. **M3+M4** — 去版本号 + 去架构首页

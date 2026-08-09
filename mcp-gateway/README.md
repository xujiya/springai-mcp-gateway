# MCP Gateway — 纯透明代理 + JWT + API Key 认证

> v0.13.1 封板 | 端口: 8082

## 架构 (v0.8.0+ 重构)

**纯透明代理** — 不聚合 MCP Client，不暴露 MCP Server 端点。

```
请求 → JWT/API Key 验证 → McpServiceRouterController → Java HttpClient → 后端 MCP Server
```

### 路由规则

| 路径 | 目标 | 工具 |
|------|------|------|
| `/weather/mcp` | `http://localhost:9092/mcp` | getAlerts, getWeatherForecast |
| `/climate/mcp` | `http://localhost:9093/mcp` | getStormWarnings, getClimateForecast |
| `/mcp` | **404** (统一端点已移除) | — |

新增后端 = 在 `ecso.mcp.services` 加一行 + 重启。

## 安全

### 双 SecurityFilterChain

| Chain | Order | 匹配 | 认证 |
|-------|-------|------|------|
| 0 | `/admin/**` | permitAll | 控制器自行验证 admin token |
| 1 | 其余所有 | JWT + API Key | BearerTokenAuthenticationFilter |

### 认证方式

| 方式 | 格式 | 适用 |
|------|------|------|
| JWT Bearer | `Authorization: Bearer <jwt>` | MCP 客户端 OAuth2 流程 |
| API Key Bearer | `Authorization: Bearer ak-xxx:sk-yyy` | 服务间调用 |
| API Key HMAC | `X-AccessKey-Id` + `X-AccessKey-Signature` + `X-AccessKey-Timestamp` | 高安全模式 |
| Admin Token | `Authorization: Bearer adm-xxx` | 管理控制台 |

### API Key 双部件模型 (对标阿里云 AccessKey)

- **AccessKey ID**: `ak-<20hex>` — 公开标识，用于查找
- **AccessKey Secret**: `sk-<40hex>` — 私密，HMAC 模式下不传输，160-bit 熵
- **暴力破解防护**: 10 次失败 → 5 分钟封禁 (ConcurrentHashMap)
- **Admin Token**: bcrypt 比较 (timing-safe)

### Per-Service WWW-Authenticate

每个 MCP 服务端点 401 时返回自己的 PRM URL:
```
WWW-Authenticate: Bearer resource_metadata="http://localhost:8080/mcp-gateway/weather/.well-known/oauth-protected-resource"
```

## Admin API (AdminConsoleController)

所有 `/admin/**` 路径走 Chain 0 (无 JWT 过滤)，控制器自行验证 admin token。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/admin/login` | POST | sys_user 登录 → admin token |
| `/admin/users` | GET/POST/PUT/DELETE | 用户 CRUD |
| `/admin/clients` | GET/POST/DELETE | OAuth 客户端 CRUD |
| `/admin/api-keys` | GET/POST/DELETE | API Key CRUD |
| `/admin/system` | GET | Java 运行时信息 |

## 关键文件

```
mcp-gateway/src/main/java/es/omarall/mcp/gateway/
├── McpServiceRouterController.java    # 透明代理 (Java HttpClient streaming)
├── SecurityConfiguration.java         # 双 SecurityFilterChain + BearerTokenResolver
├── ApiKeyAuthenticationFilter.java     # AK 认证 (Bearer + HMAC)
├── ServiceAwareBearerEntryPoint.java  # Per-Service WWW-Authenticate
├── ApiKeyService.java                 # AK 验证 + 暴力破解防护
├── controller/
│   └── AdminConsoleController.java    # 管理 API
├── entity/
│   ├── ApiKey.java                    # 双部件 AccessKey
│   ├── SysUser.java
│   └── RegisteredClientEntity.java
└── mapper/
    ├── ApiKeyMapper.java
    ├── SysUserMapper.java
    └── RegisteredClientMapper.java
```

## 配置

```yaml
ecso:
  mcp:
    services:
      weather: { url: http://localhost:9092/mcp }
      climate: { url: http://localhost:9093/mcp }
  auth-server:
    public-url: http://localhost:8080/api-gateway/ecso/auth
  mcp-server:
    public-url: http://localhost:8080/mcp-gateway
  api-key:
    admin-token: adm-xxx          # dev
    # admin-token-hash: {bcrypt}  # production
```

## 默认凭证 (开发环境)

- API Key: `ak-36f8ea0fc5ad9937572d:sk-8665c9bbdd338e3ce03a0fdf115fbf65685b2b94`
- Admin Token: `adm-a4596ca59d33d7cd005c2367a0c657c7`
- **生产环境务必修改!**

## Troubleshooting

- **401 + WWW-Authenticate**: 正常 — MCP 客户端需先获取 token
- **403 (scope)**: API Key 缺少 `mcp:read` scope
- **401 (invalid_token)**: JWT 过期或 issuer 不匹配
- **401 (rate limited)**: API Key 认证失败超 10 次，等 5 分钟
- **Session ID missing**: 需先 `initialize` 获取 `Mcp-Session-Id`

# MCP Gateway — 完整安全参考项目

> 封板版本: **v0.13.1** | 最后更新: 2026-08-10

端到端实现 **MCP (Model Context Protocol) 网关安全架构**的参考项目，包含：

- **Nginx** (8080) → **API Gateway** (8081) → Auth Server / Vue / Admin Console
- **MCP Gateway** (8082) → Weather Server / Climate Server（纯透明代理）
- **OAuth2 + DCR + PKCE** 完整 RFC 9728 流程
- **双模认证**: JWT Bearer + API Key (AccessKey 双部件模型, 对标阿里云)
- **管理控制台**: Vue3 SPA — AK 凭证 / OAuth 客户端 / 用户 / MCP 服务 / 安全仪表盘
- **MySQL 8 + MyBatis-Plus** 持久化

---

## 项目结构

```
springai-mcp-gateway/
├── api-gateway/          # Spring Cloud Gateway (WebFlux) :8081
│   └── 前端透传 + Auth路由 + URL重写 + CORS
├── auth-server/          # Spring Authorization Server :9090
│   └── OAuth2 + DCR + PKCE + MySQL持久化
├── mcp-gateway/          # 纯透明代理 + JWT验证 + AK认证 :8082
│   └── McpServiceRouterController + AdminConsoleController
├── weather-server/       # 天气MCP工具后端 :9092
├── climate-server/       # 气候MCP工具后端 :9093
├── login-ui-server/      # Vue 登录前端 (Vite :9091)
├── admin-console/        # Vue3 管理控制台 (Vite :9094)
├── nginx.conf            # Nginx :8080 (不改!)
└── mcp-bearer-proxy.mjs  # 调试用 Bearer Token 代理 :9099
```

## 服务清单

| 服务 | 端口 | 说明 |
|------|------|------|
| **Nginx** | 8080 | 统一外部入口, server_tokens off |
| **API Gateway** | 8081 | 前端透传 + Auth 路由 + URL 重写 |
| **MCP Gateway** | 8082 | 纯透明代理 + JWT + API Key + Admin API |
| **Auth Server** | 9090 | Spring Authorization Server + DCR |
| **Vite (Login)** | 9091 | Vue 前端开发服务器 |
| **Weather Server** | 9092 | getAlerts + getWeatherForecast |
| **Climate Server** | 9093 | getStormWarnings + getClimateForecast |
| **Vite (Admin)** | 9094 | 管理控制台 |

## 快速启动

### 1. 构建

```bash
JAVA_HOME="C:/Users/USER365110/.jdks/loom-ea-25-loom+1-11"
mvn clean package -DskipTests
```

### 2. 启动 MySQL

确保 MySQL 8 运行，数据库 `mcp_auth` 已创建（schema.sql + data.sql 自动初始化）。

### 3. 启动所有服务

```bash
# Auth Server
java -jar auth-server/target/auth-server-0.0.3-SNAPSHOT.jar &

# MCP Gateway
java -jar mcp-gateway/target/mcp-gateway-0.0.3-SNAPSHOT.jar &

# API Gateway
java -jar api-gateway/target/api-gateway-0.0.3-SNAPSHOT.jar &

# MCP 后端
java -jar weather-server/target/weather-server-0.0.3-SNAPSHOT.jar &
java -jar climate-server/target/climate-server-0.0.3-SNAPSHOT.jar &

# Vue 前端
cd login-ui-server && npm run dev &

# Admin 控制台
cd admin-console && npm run dev &

# Nginx
nginx
```

### 4. 验证

```bash
# AS Metadata
curl http://localhost:8080/api-gateway/auth/.well-known/oauth-authorization-server

# PRM (Weather)
curl http://localhost:8080/mcp-gateway/weather/.well-known/oauth-protected-resource

# 管理控制台
open http://localhost:8080/api-gateway/admin/
```

## 核心架构

### 流量链路

```
浏览器/pi MCP ──→ Nginx(:8080) ──→ API Gateway(:8081) ──→ Auth Server(:9090)
                                    └→ Vite(:9091/:9094)
                 ──→ MCP Gateway(:8082) ──→ Weather(:9092)
                                    └→ Climate(:9093)
```

### MCP 路由 (Pattern B: Per-Service)

```
/mcp-gateway/weather/mcp  →  http://localhost:9092/mcp  (getAlerts, getWeatherForecast)
/mcp-gateway/climate/mcp  →  http://localhost:9093/mcp  (getStormWarnings, getClimateForecast)
/mcp-gateway/mcp          →  404  (统一端点已移除)
```

新增 MCP 后端 = 在 `mcp-gateway/application.yml` 的 `mcp.services` 下加一行 + 重启。

### 认证模式

| 模式 | 适用场景 | 格式 |
|------|---------|------|
| **JWT Bearer** | MCP 客户端 OAuth2 流程 | `Authorization: Bearer <jwt>` |
| **API Key Bearer** | 服务间调用 / 脚本 | `Authorization: Bearer ak-xxx:sk-yyy` |
| **API Key HMAC** | 高安全服务间 (对标阿里云) | `X-AccessKey-Id` + `X-AccessKey-Signature` |
| **Admin Token** | 管理控制台 API | `Authorization: Bearer adm-xxx` |

### DCR 两层客户端模型

| 客户端类型 | 注册方式 | 允许的 Grant Types | 用途 |
|-----------|---------|-------------------|------|
| DCR 动态注册 | POST /oauth2/register | authorization_code + refresh_token (PKCE) | MCP 客户端自动注册 |
| 预注册管理客户端 | MySQL data.sql | client_credentials | 服务间 / 管理调用 |
| 预注册 PKCE 客户端 | MySQL data.sql | authorization_code + refresh_token | pi / Claude Code |

## MCP SDK RFC 8414 §3.3 修复

**问题**: `@modelcontextprotocol/client` v2.0.0-beta.5 用 `new URL(asUrl).origin === issuer` 校验，
当 issuer 带路径 (`http://localhost:8080/api-gateway/auth`) 与 origin (`http://localhost:8080`) 不匹配时报错。

**修复**: Patch SDK 允许同 origin 的 path-based issuer:

```js
// 原始 (index.mjs:1093)
parsed.issuer === expectedIssuer

// Patch 后 — 额外允许同 origin
parsed.issuer === expectedIssuer
  || new URL(expectedIssuer).origin === new URL(parsed.issuer).origin
```

> ⚠️ SDK 升级后 patch 会丢失，需重新应用。

## 关键配置

### Auth Server (`auth-server/application.yml`)

```yaml
mcp:
  dcr:
    enabled: true                    # 生产环境设 false
    client-secret-expires-in: 90d
    access-token-time-to-live: 24h
    refresh-token-time-to-live: 1h
```

### MCP Gateway (`mcp-gateway/application.yml`)

```yaml
mcp:
  services:
    weather:
      url: http://localhost:9092/mcp
    climate:
      url: http://localhost:9093/mcp
auth-server:
  public-url: http://localhost:8080/api-gateway/auth
mcp-server:
  public-url: http://localhost:8080/mcp-gateway
api-key:
  admin-token: adm-a4596ca59d33d7cd005c2367a0c657c7    # dev
  # admin-token-hash: {bcrypt}$2a$10$...                 # production
```

### Nginx — **不要修改!**

所有 URL 重写在 API Gateway 层完成，Nginx 只做 `proxy_pass`。

## 文档

| 文档 | 说明 |
|------|------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 完整架构文档 |
| [SECURITY-8080.md](SECURITY-8080.md) | :8080 安全评估 |
| [SECURITY-AUDIT.md](SECURITY-AUDIT.md) | 详细安全审计 |
| [CLOUDFLARE.md](CLOUDFLARE.md) | Cloudflare Tunnel 配置 |
| [CHATGPT.md](CHATGPT.md) | ChatGPT Connector 配置 |

## 版本历史

| 版本 | 里程碑 |
|------|--------|
| v0.1.0 | 基线 (pre-security) |
| v0.2.0 | Security fix (DCR secret expiry, token TTL) |
| v0.3.0 | DCR 两层客户端模型 |
| v0.4.0 | 双 MCP 后端 (weather + climate) |
| v0.5.0 | MySQL + MyBatis-Plus 持久化 |
| v0.6.0 | 预注册模式 (对标阿里云) |
| v0.7.0 | Security 硬化验证 |
| v0.8.0 | 纯透明代理重构 |
| v0.9.0 | CORS + RFC 9728 + 浏览器登录 |
| v0.10.0 | API Key 静态凭证 (对标阿里云 AccessKey) |
| v0.11.0 | AK 双部件安全模型 + 暴力破解防护 |
| v0.12.0 | Admin 控制台 Vue3 SPA |
| v0.13.0 | Admin v2 (仪表盘 + 客户端详情 + 系统状态) |
| **v0.13.1** | **MCP SDK RFC 9728 issuer patch** |

## 生产部署检查清单

- [ ] TLS (HTTPS) on nginx
- [ ] Bind 内部端口到 127.0.0.1 + 防火墙
- [ ] DCR 关闭 (`mcp.dcr.enabled: false`)
- [ ] 修改默认 admin 密码
- [ ] bcrypt `admin-token-hash` (不用 plaintext admin-token)
- [ ] `cookie.secure: true`
- [ ] CORS 限制到生产域名
- [ ] nginx `limit_req_zone` for `/oauth2/token`
- [ ] AES-GCM 加密 secret 存储 (HMAC 签名验证)
- [ ] 重新应用 MCP SDK issuer patch (如 SDK 升级)

## 参考

- [RFC 9728 — OAuth 2.0 for MCP](https://datatracker.ietf.org/doc/html/rfc9728)
- [RFC 8414 — Authorization Server Metadata](https://datatracker.ietf.org/doc/html/rfc8414)
- [Spring AI MCP Server Security](https://spring.io/blog/2025/09/30/spring-ai-mcp-server-security)
- [Understanding MCP Authorization (Christian Posta)](https://blog.christianposta.com/understanding-mcp-authorization-step-by-step/)
- [API Keys are a Bad Idea for Enterprise LLM/MCP Access](https://blog.christianposta.com/api-keys-are-a-bad-idea-for-enterprise-llm-agent-and-mcp-access/)

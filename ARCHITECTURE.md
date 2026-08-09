# MCP Gateway 系统 — 完整架构文档

> 最后更新: 2026-08-10 (封板 v0.14.0)
> 项目根目录: `D:/AI Coding/mcp-gateways/springai-mcp-gateway/`

---

## 目录

1. [系统总览](#1-系统总览)
2. [服务清单与端口](#2-服务清单与端口)
3. [架构图](#3-架构图)
4. [流量转发全链路](#4-流量转发全链路)
5. [HAProxy 层详解](#5-haproxy-层详解)
6. [API Gateway 层详解](#6-api-gateway-层详解)
7. [MCP Gateway 层详解](#7-mcp-gateway-层详解)
8. [Auth Server 层详解](#8-auth-server-层详解)
9. [Vue 前端详解](#9-vue-前端详解)
10. [全部公开接口清单](#10-全部公开接口清单)
11. [MCP 暴露方式](#11-mcp-暴露方式)
12. [OAuth2 + DCR 完整流程](#12-oauth2--dcr-完整流程)
13. [MCP 客户端接入流程 (RFC 9728)](#13-mcp-客户端接入流程-rfc-9728)
14. [Vue 登录认证 12 步流程](#14-vue-登录认证-12-步流程)
15. [URL 重写规则汇总](#15-url-重写规则汇总)
16. [白名单与安全策略](#16-白名单与安全策略)
17. [配置文件索引](#17-配置文件索引)
18. [API Key 认证详解](#18-api-key-认证详解)
19. [管理控制台详解](#19-管理控制台详解)
20. [MCP SDK RFC 8414 §3.3 修复](#20-mcp-sdk-rfc-8414-§33-修复)
21. [CORS 配置详解](#21-cors-配置详解)
22. [安全硬化汇总](#22-安全硬化汇总)
23. [版本历史](#23-版本历史)
24. [生产部署检查清单](#24-生产部署检查清单)

---

## 1. 系统总览

本系统实现了一个 **完整的 MCP (Model Context Protocol) 网关安全架构**，包含：

- **Vue 前端** 通过 HAProxy → API Gateway → Vite Dev Server 透传路由暴露
- **OAuth2 Authorization Server** 通过 HAProxy → API Gateway → Auth Server 暴露
- **MCP Gateway** 通过 HAProxy → MCP Gateway 暴露，受 OAuth2 保护
- **DCR (Dynamic Client Registration)** 支持 MCP 客户端自动注册
- **RFC 8414** Authorization Server 发现机制
- **RFC 9728** OAuth2 for MCP 标准保护资源元数据

**核心设计原则**：

> 所有 URL 重写在 **API Gateway 层**完成。HAProxy 只做简单 backend 路由，不做 `rewrite`。
> 所有外部接口统一经过 HAProxy (:8080) 入口，内部微服务间通信使用内部端口。

---

## 2. 服务清单与端口

| 服务 | 端口 | 协议 | 说明 |
|------|------|------|------|
| **HAProxy** | 8080 | HTTP | 统一外部入口，反向代理，del-header Server |
| **API Gateway** | 8081 | HTTP (WebFlux) | 前端透传 + Auth 路由 + URL 重写 |
| **MCP Gateway** | 8082 | HTTP (Servlet) | 纯透明代理 + JWT验证 + API Key认证 + Admin API |
| **Auth Server** | 9090 | HTTP (Servlet) | Spring Authorization Server + DCR + MySQL |
| **Vite Dev Server** | 9091 | HTTP | Vue 登录前端开发服务器 (host: 0.0.0.0) |
| **Weather MCP Server** | 9092 | HTTP | 天气 MCP 工具后端 |
| **Climate MCP Server** | 9093 | HTTP | 气候 MCP 工具后端 |
| **Vite Admin Console** | 9094 | HTTP | Vue3 管理控制台 (host: 0.0.0.0) |
| **MCP Bearer Proxy** | 9099 | HTTP | 自动获取 Bearer Token 的代理 (调试用) |

**内部通信关系**：

```
HAProxy(8080) ──backend──→ API Gateway(8081) ──proxy──→ Auth Server(9090)
                                         └──proxy──→ Vite Dev(9091)
                                         └──proxy──→ Vite Admin(9094)
HAProxy(8080) ──backend──→ MCP Gateway(8082) ──HTTP──→ Weather Server(9092)
                                                     └──HTTP──→ Climate Server(9093)
API Gateway(8081) ──JWKS──→ Auth Server(9090)   (JWT 验证)
MCP Gateway(8082) ──JWKS──→ Auth Server(9090)   (JWT 验证)
```

---

## 3. 架构图

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        外部客户端                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────────────────┐  │
│  │  浏览器   │  │  pi MCP  │  │  其他 MCP Client (Claude, etc.) │  │
│  └────┬─────┘  └────┬─────┘  └────────────┬─────────────────────┘  │
│       │              │                     │                        │
└───────┼──────────────┼─────────────────────┼────────────────────────┘
        │              │                     │
        ▼              ▼                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     HAProxy (:8080)                                   │
│                                                                     │
│  acl is_mcp_gateway path_beg /mcp-gateway/                           │
│  use_backend mcp_gw if is_mcp_gateway                               │
│  default_backend api_gw                                             │
│                                                                     │
│  api_gw:  /api-gateway/ → :8081 (regsub 剥 /api-gateway/)             │
│  mcp_gw:  /mcp-gateway/ → :8082 (regsub 剥 /mcp-gateway/)             │
│                                                                     │
│  ✗ 无 rewrite  ✓ 仅 backend 路由 + regsub 路径重写                   │
│  ✓ del-header Server  ✓ 每 backend 完整 http-request set-header      │
└───────┬──────────────────────┬──────────────────────────────────────┘
        │                      │
        ▼                      ▼
┌──────────────────────┐  ┌──────────────────────────────────────────┐
│  API Gateway (:8081) │  │  MCP Gateway (:8082)                    │
│  (Spring WebFlux)    │  │  (Spring Servlet + MCP Server)          │
│                      │  │                                          │
│  ┌─ 路由 ──────────┐│  │  ┌─ 安全 ─────────────────────────────┐ │
│  │ RFC 8414 发现    ││  │  │ 所有请求需 Bearer Token           │ │
│  │ Auth 路由 (9条)  ││  │  │ 401 + WWW-Authenticate            │ │
│  │ Vue 透传 (2条)   ││  │  │ Protected Resource Metadata        │ │
│  └─────────────────┘│  │  │ PublicUrlFilter (重写401头)        │ │
│                      │  │  └────────────────────────────────────┘ │
│  ┌─ 过滤器 ────────┐│  │                                          │
│  │ RewritePath     ││  │  ┌─ MCP 协议 ────────────────────────┐ │
│  │ StripPrefix     ││  │  │ Streamable HTTP (POST /mcp)        │ │
│  │ RewriteResponse ││  │  │ SSE (GET /mcp) — 也受保护          │ │
│  │   Header        ││  │  │ Mcp-Session-Id 会话管理             │ │
│  │ RewriteAuthUrls ││  │  └────────────────────────────────────┘ │
│  └─────────────────┘│  │                                          │
│                      │  │  ┌─ 后端 MCP 服务 ───────────────────┐ │
│  ┌─ 白名单 ────────┐│  │  │ weather (SSE → :9092/mcp)          │ │
│  │ 12 条公开路径    ││  │  └────────────────────────────────────┘ │
│  └─────────────────┘│  │                                          │
└──────┬───────┬──────┘  └──────────────────────────────────────────┘
       │       │                          │
       ▼       ▼                          ▼
┌────────────┐ ┌────────────┐  ┌──────────────────────┐
│Auth Server │ │Vite Dev    │  │Weather MCP Server    │
│(:9090)     │ │(:9091)     │  │(:9092)               │
│            │ │            │  │                      │
│OAuth2 +   │ │Vue SPA     │  │4 MCP Tools:          │
│ DCR +     │ │base:       │  │ • getWeatherForecast │
│ Login     │ │/api-gateway│  │ • getAlerts          │
│  /vue-login│ │/vue/  │  │ • gw_m_c_w_weather_ │
│  → 302    │ │            │  │   getWeatherForecast │
│            │ │proxy:      │  │ • gw_m_c_w_weather_ │
│            │ │/api-gateway│  │   getAlerts          │
│            │ │→ :8081     │  └──────────────────────┘
└────────────┘ └────────────┘
```

### 3.2 MCP 客户端接入架构

```
┌──────────────────────────────────────────────────────┐
│                 MCP 客户端 (pi, Claude, etc.)         │
│                                                      │
│  1. POST /mcp-gateway/mcp                            │
│     → 401 + WWW-Authenticate                         │
│                                                      │
│  2. GET /mcp-gateway/.well-known/                    │
│     oauth-protected-resource/mcp                     │
│     → { resource, authorization_servers }            │
│                                                      │
│  3. GET /.well-known/oauth-authorization-server/     │
│     api-gateway/auth                            │
│     → AS metadata (RFC 8414)                         │
│                                                      │
│  4. POST /api-gateway/auth/oauth2/register      │
│     → { client_id, client_secret }  (DCR)            │
│                                                      │
│  5. 打开浏览器 → authorize → 用户登录 → callback     │
│                                                      │
│  6. POST /api-gateway/auth/oauth2/token         │
│     → { access_token }                               │
│                                                      │
│  7. POST /mcp-gateway/mcp                            │
│     Authorization: Bearer <token>                    │
│     → 200 MCP 响应                                   │
└──────────────────────────────────────────────────────┘
```

### 3.3 安全边界

```
┌─────────────────────────────────────────────────────────┐
│                    公网 (HAProxy :8080)                    │
│                                                         │
│  /api-gateway/vue/**      → 公开 (Vue 前端)        │
│  /api-gateway/auth/**    → 部分公开 (白名单)       │
│  /mcp-gateway/mcp             → 需 Bearer Token         │
│  /.well-known/oauth-**        → 公开 (RFC 8414 发现)    │
│  /mcp-gateway/.well-known/**  → 公开 (资源元数据)       │
│                                                         │
├─────────────────────────────────────────────────────────┤
│                  内网 (直连端口)                         │
│                                                         │
│  :8081  API Gateway  — 白名单路径公开，其余需 JWT       │
│  :8082  MCP Gateway  — 所有请求需 Bearer Token          │
│  :9090  Auth Server  — Spring Security 管理             │
│  :9091  Vite Dev     — 无认证 (开发模式)                │
│  :9092  Weather      — 无认证 (仅 MCP Gateway 连接)     │
└─────────────────────────────────────────────────────────┘
```

---

## 4. 流量转发全链路

### 4.1 Vue 前端请求

```
浏览器
  │
  │ GET /api-gateway/vue/              (入口)
  │ GET /api-gateway/vue/assets/index-xxx.js
  │ GET /api-gateway/vue/assets/index-xxx.css
  │
  ▼
HAProxy (:8080)
  │  backend api_gw: /api-gateway/ → :8081 (regsub 剥 /api-gateway/ 前缀)
  │
  │ 转发路径: /vue/  (无 /api-gateway/ 前缀)
  │
  ▼
API Gateway (:8081)
  │  路由 vue-login:  Path=/vue        → RewritePath → /api-gateway/vue/
  │  路由 vue-assets: Path=/vue/**     → RewritePath → /api-gateway/vue/${path}
  │  ⚠️ 加回 /api-gateway/ 前缀! (因为 Vite base 含此前缀)
  │
  │ 转发路径: /api-gateway/vue/        (含 /api-gateway/ 前缀)
  │
  ▼
Vite Dev Server (:9091)
  │  base: /api-gateway/vue/
  │  匹配 /api-gateway/vue/ → 返回 index.html
  │  匹配 /api-gateway/vue/assets/* → 返回静态资源
  │
  ▼
浏览器渲染 Vue SPA
```

**关键点**：HAProxy 剥 `/api-gateway/`，API Gateway 加回 `/api-gateway/`，因为 Vite 的 `base` 配置包含此前缀，资源 URL 都以 `/api-gateway/vue/` 开头。

### 4.2 Auth 请求

```
浏览器 / MCP 客户端
  │
  │ GET /api-gateway/auth/.well-known/openid-configuration
  │ POST /api-gateway/auth/oauth2/register
  │ POST /api-gateway/auth/oauth2/token
  │ GET /api-gateway/auth/oauth2/authorize?...
  │ POST /api-gateway/auth/login
  │
  ▼
HAProxy (:8080)
  │  backend api_gw: /api-gateway/ → :8081 (regsub 剥前缀)
  │
  │ 转发路径: /auth/oauth2/authorize  (无 /api-gateway/ 前缀)
  │
  ▼
API Gateway (:8081)
  │  路由匹配: Path=/auth/oauth2/authorize
  │  StripPrefix=1  → 剥掉 /auth/
  │
  │ 转发路径: /oauth2/authorize
  │
  ▼
Auth Server (:9090)
  │  处理 OAuth2 授权请求
  │  返回 302 Location: http://localhost:9090/vue-login;...
  │
  ▼
API Gateway (:8081) — 响应后处理
  │  RewriteResponseHeader (post-filter, 逆序执行):
  │    ① 先执行: ^http://localhost:9090/vue-login → http://localhost:8080/api-gateway/vue/
  │    ② 后执行: ^http://localhost:9090/          → http://localhost:8080/api-gateway/auth/
  │
  │  结果 Location: http://localhost:8080/api-gateway/vue/;SESSIONID=xxx
  │
  ▼
浏览器 → 跟随 302 → 加载 Vue 登录页
```

### 4.3 MCP 请求

```
MCP 客户端
  │
  │ POST /mcp-gateway/mcp
  │
  ▼
HAProxy (:8080)
  │  backend mcp_gw: /mcp-gateway/ → :8082 (regsub 剥前缀)
  │
  │ 转发路径: /mcp
  │
  ▼
MCP Gateway (:8082)
  │  所有请求需 Bearer Token
  │
  │  若无 Token:
  │    → 401 + WWW-Authenticate: Bearer resource_metadata=http://localhost:8080/mcp-gateway/.well-known/oauth-protected-resource/mcp
  │    (PublicUrlFilter 把内部 http://127.0.0.1:8082 重写为公网 http://localhost:8080/mcp-gateway)
  │
  │  若有有效 Token:
  │    → 处理 MCP JSON-RPC 请求
  │    → 转发到后端 Weather Server (:9092)
  │
  ▼
Weather MCP Server (:9092)
  │  处理 MCP 工具调用
  │  返回结果
```

### 4.4 RFC 8414 AS 发现请求

```
MCP 客户端
  │
  │ GET /.well-known/oauth-authorization-server/api-gateway/auth
  │ (按 RFC 8414: <origin>/.well-known/oauth-authorization-server/<issuer_path>)
  │
  ▼
HAProxy (:8080)
  │  backend api_gw: /.well-known/ → :8081 (无 regsub，保留完整路径)
  │
  │ 转发路径: /.well-known/oauth-authorization-server/api-gateway/auth
  │           (完整路径保留)
  │
  ▼
API Gateway (:8081)
  │  路由 rfc8414-well-known-first:
  │    Path=/.well-known/oauth-authorization-server/**
  │    RewritePath: /.well-known/oauth-authorization-server/.* → /.well-known/openid-configuration
  │    RewriteAuthUrls: http://localhost:9090 → http://localhost:8080/api-gateway/auth
  │
  │ 转发路径: /.well-known/openid-configuration
  │
  ▼
Auth Server (:9090)
  │  返回 AS metadata (内部 URL: http://localhost:9090/...)
  │
  ▼
API Gateway (:8081) — 响应后处理
  │  RewriteAuthUrls 过滤器:
  │    JSON body 中 http://localhost:9090 → http://localhost:8080/api-gateway/auth
  │
  │  结果: 所有 URL 指向公网地址
```

### 4.5 MCP Protected Resource Metadata 请求

```
MCP 客户端
  │
  │ GET /mcp-gateway/.well-known/oauth-protected-resource/mcp
  │
  ▼
HAProxy (:8080)
  │  backend mcp_gw: /mcp-gateway/ → :8082 (regsub 剥前缀)
  │
  │ 转发路径: /.well-known/oauth-protected-resource/mcp
  │
  ▼
MCP Gateway (:8082)
  │  返回 Protected Resource Metadata:
  │  {
  │    "resource": "http://localhost:8080/mcp-gateway/mcp",
  │    "authorization_servers": ["http://localhost:8080/api-gateway/auth"],
  │    "resource_name": "Spring MCP Gateway",
  │    "bearer_methods_supported": ["header"],
  │    "scopes_supported": ["mcp:read", "mcp:write"]
  │  }
```

### 4.6 Vue 前端登录表单提交

```
Vue SPA (浏览器)
  │
  │ POST /api-gateway/auth/login
  │ (gatewayPrefix = '/api-gateway/auth', 由 pathname.indexOf('/auth') 动态计算)
  │
  ▼
HAProxy (:8080) → API Gateway (:8081)
  │  StripPrefix=1 → /login
  │
  ▼
Auth Server (:9090)
  │  处理登录，返回 302 Location: http://localhost:9090/oauth2/authorize?...
  │
  ▼
API Gateway (:8081) — RewriteResponseHeader
  │  Location: http://localhost:8080/api-gateway/auth/oauth2/authorize?...
  │
  ▼
浏览器 → 跟随 302 → 授权确认 → 302 → callback?code=xxx
```

---

## 5. HAProxy 层详解

### 5.1 配置

```haproxy
global

defaults
    mode http
    timeout connect 10s
    timeout client  65s
    timeout server  65s
    option dontlognull

frontend public
    bind *:8080
    http-response del-header Server

    acl is_mcp_gateway path_beg /mcp-gateway/
    use_backend mcp_gw if is_mcp_gateway

    default_backend api_gw

backend api_gw
    server s 127.0.0.1:8081
    acl has_prefix path_beg /api-gateway/
    http-request set-path %[path,regsub(^/api-gateway/,/)] if has_prefix
    http-request set-header X-Forwarded-Prefix /api-gateway if has_prefix
    http-request set-header Host              %[req.hdr(Host)]
    http-request set-header X-Real-IP         %[src]
    http-request set-header X-Forwarded-For   %[src]
    http-request set-header X-Forwarded-Proto http
    http-request set-header X-Forwarded-Host  %[req.hdr(Host)]
    http-request set-header X-Forwarded-Port  8080

backend mcp_gw
    server s 127.0.0.1:8082
    http-request set-path %[path,regsub(^/mcp-gateway/,/)]
    http-request set-header Host              %[req.hdr(Host)]
    http-request set-header X-Real-IP         %[src]
    http-request set-header X-Forwarded-For   %[src]
    http-request set-header X-Forwarded-Proto http
    http-request set-header X-Forwarded-Host  %[req.hdr(Host)]
    http-request set-header X-Forwarded-Port  8080
    http-request set-header X-Forwarded-Prefix /mcp-gateway
    timeout server 3600s
    timeout tunnel 3600s
```

### 5.2 路径重写行为

| 请求路径 | 匹配 ACL/Backend | 转发到 | 转发路径 | 说明 |
|----------|-----------------|--------|----------|------|
| `/.well-known/oauth-authorization-server/api-gateway/auth` | `default_backend api_gw` | :8081 | **原路径不变** | 无 regsub，不剥前缀 |
| `/api-gateway/vue/` | `backend api_gw` | :8081 | `/vue/` | regsub 剥 `/api-gateway/` |
| `/api-gateway/auth/oauth2/token` | `backend api_gw` | :8081 | `/auth/oauth2/token` | regsub 剥 `/api-gateway/` |
| `/mcp-gateway/mcp` | `acl is_mcp_gateway → backend mcp_gw` | :8082 | `/mcp` | regsub 剥 `/mcp-gateway/` |
| `/mcp-gateway/.well-known/...` | `acl is_mcp_gateway → backend mcp_gw` | :8082 | `/.well-known/...` | regsub 剥 `/mcp-gateway/` |
| `/其他` | `default_backend api_gw` | :8081 | (由 API Gateway 处理) | api_gw 兜底 |

### 5.3 设计原则

- ✅ **仅 backend 路由**：不做任何 URL rewrite
- ✅ **路径重写用 regsub**：`set-path %[path,regsub(^/prefix/,/)]` 剥前缀
- ✅ **无 302 Location 重写**：Location 重写在 API Gateway 层完成
- ✅ **SSE 长连接支持**：mcp_gw 的 timeout server/tunnel 设为 3600s
- ✅ **Server 头隐藏**：`http-response del-header Server`
- ✅ **每 backend 独立设置 header**：无继承，显式声明

---

## 6. API Gateway 层详解

### 6.1 路由清单 (11 条)

| # | ID | 路径谓词 | 目标 URI | 核心过滤器 | 说明 |
|---|-----|---------|----------|-----------|------|
| 1 | `rfc8414-well-known-first` | `/.well-known/oauth-authorization-server/**` | :9090 | RewritePath + RewriteAuthUrls | RFC 8414 AS 发现 |
| 2 | `auth-well-known` | `/auth/.well-known/**` | :9090 | StripPrefix=1 + RewriteAuthUrls | AS 元数据 (标准路径) |
| 3 | `auth-register` | `/auth/oauth2/register` | :9090 | StripPrefix=1 | DCR 注册 |
| 4 | `auth-token` | `/auth/oauth2/token` | :9090 | StripPrefix=1 | Token 端点 |
| 5 | `auth-jwks` | `/auth/oauth2/jwks` | :9090 | StripPrefix=1 | JWKS |
| 6 | `auth-authorize` | `/auth/oauth2/authorize` | :9090 | StripPrefix=1 + RewriteResponseHeader×2 | 授权端点 |
| 7 | `auth-login` | `/auth/login` | :9090 | StripPrefix=1 + RewriteResponseHeader×2 | 登录 POST |
| 8 | `auth-info` | `/auth/oauth2/auth-info` | :9090 | StripPrefix=1 | Auth 信息 API |
| 9 | `vue-login` | `/vue` | :9091 | RewritePath | Vue 登录页 (精确匹配) |
| 10 | `vue-assets` | `/vue/**` | :9091 | RewritePath | Vue 子路由 & 静态资源 |
| 11 | `auth-other` | `/auth/**` | :9090 | StripPrefix=1 + RewriteAuthUrls + RewriteResponseHeader×2 | 兜底: 其他 Auth 路由 |

### 6.2 过滤器详解

#### StripPrefix=1
剥掉路径前 1 段：`/auth/oauth2/token` → `/oauth2/token`

#### RewritePath
| 路由 | 输入 | 输出 | 说明 |
|------|------|------|------|
| rfc8414 | `/.well-known/oauth-authorization-server/任意` | `/.well-known/openid-configuration` | 重写到标准 AS 元数据路径 |
| vue-login | `/vue` | `/api-gateway/vue/` | 加回 `/api-gateway/` + 尾部斜杠 |
| vue-assets | `/vue/(path)` | `/api-gateway/vue/(path)` | 加回 `/api-gateway/` 前缀 |

#### RewriteResponseHeader (Location 头重写)

**重要：Spring Cloud Gateway 的 post-filter 按声明逆序执行！**

| 路由 | 声明顺序 | regex | replacement | 实际执行顺序 |
|------|---------|-------|-------------|------------|
| auth-authorize/login/other | ① 先声明 | `^http://localhost:9090/` | `http://localhost:8080/api-gateway/auth/` | 后执行 (通用兜底) |
| auth-authorize/login/other | ② 后声明 | `^http://localhost:9090/vue-login` | `http://localhost:8080/api-gateway/vue/` | 先执行 (精确匹配) |

**执行逻辑**：
1. 先检查 `Location` 是否匹配 `^http://localhost:9090/vue-login` → 替换为 Vue 登录页
2. 再检查 `Location` 是否匹配 `^http://localhost:9090/` → 替换为 Auth 公网地址

这样确保 `vue-login` 的精确匹配优先于通用的 `/` 匹配。

#### RewriteAuthUrls (JSON Body URL 重写)

自定义过滤器 `RewriteAuthUrlsGatewayFilterFactory`，处理 JSON 响应体中的内部 URL：

```
http://localhost:9090  →  http://localhost:8080/api-gateway/auth
```

仅在 `Content-Type: application/json` 或 `application/jwk-set+json` 时生效。

### 6.3 白名单 (12 条公开路径)

```yaml
  whitelist:
    paths:
      - /.well-known/oauth-authorization-server/**   # RFC 8414 AS 发现
      - /auth/.well-known/**                     # AS 元数据
      - /auth/oauth2/register                    # DCR 注册
      - /auth/oauth2/token                       # Token 端点
      - /auth/oauth2/authorize                   # 授权端点
      - /auth/oauth2/jwks                        # JWKS
      - /auth/login                              # 登录 POST
      - /auth/oauth2/auth-info                   # Auth 信息
      - /vue/**                                  # Vue 前端 (全部公开)
```

不在白名单中的路径需要 Bearer Token (JWT) 认证。

---

## 7. MCP Gateway 层详解

### 7.1 核心配置

```yaml
server:
  port: 8082

spring:
  ai:
    mcp:
      server:
        enabled: true
        protocol: streamable         # Streamable HTTP 协议
        name: springai-mcp-gateway
      client:
        enabled: true
        streamable-http:
          connections:
            weather:
              url: http://localhost:9092/mcp    # Weather 后端

mcp:
  gateway:
    prefixMode: STATIC               # 工具名前缀模式
    delimiter: "_"
    staticPrefix: "gw"               # gw_m_c_w_weather_getWeatherForecast
```

### 7.2 安全架构

```
请求 → SecurityFilterChain
  │
  ├─ 所有请求需认证 (anyRequest().authenticated())
  │
  ├─ McpServerOAuth2Configurer:
  │    ├─ authorizationServer: http://localhost:8080/api-gateway/auth
  │    ├─ resource: http://localhost:8080/mcp-gateway/mcp
  │    ├─ resourceName: "Spring MCP Gateway"
  │    ├─ bearerMethod: "header"
│    ├─ scopes: mcp:read, mcp:write
│    └─ Protected Resource Metadata 自动生成
│
├─ PublicUrlFilter:
│    └─ 401 响应中 WWW-Authenticate 头的 resource_metadata URL
│       从内部 http://127.0.0.1:8082 → 公网 http://localhost:8080/mcp-gateway
│
└─ CORS: 全开 (开发环境)```

### 7.3 MCP 工具清单 (4 个)

| 工具名 | 参数 | 说明 | 来源 |
|--------|------|------|------|
| `getWeatherForecast` | `latitude` (double), `longitude` (double) | 获取指定经纬度的天气预报 | 原始工具 |
| `getAlerts` | `state` (string, 两字母美国州代码) | 获取指定州的天气警报 | 原始工具 |
| `gw_m_c_w_weather_getWeatherForecast` | `latitude`, `longitude` | 同上，网关添加前缀 | 网关前缀工具 |
| `gw_m_c_w_weather_getAlerts` | `state` | 同上，网关添加前缀 | 网关前缀工具 |

> 前缀规则: `gw` (staticPrefix) + `_` + `m` (mcp) + `_` + `c` (client) + `_` + `w` (weather) + `_` + `weather` (service名) + `_` + 原始工具名

### 7.4 MCP 协议交互 (Streamable HTTP)

```
客户端                                    MCP Gateway (:8082)
  |                                          |
  |  POST /mcp                               |
  |  { method: "initialize", ... }           |
  |  ──────────────────────────────────────→ |
  |  ← 200 + Mcp-Session-Id: <uuid>         |
  |                                          |
  |  POST /mcp                               |
  |  Mcp-Session-Id: <uuid>                  |
  |  { method: "notifications/initialized" } |
  |  ──────────────────────────────────────→ |
  |  ← 202 Accepted                          |
  |                                          |
  |  POST /mcp                               |
  |  Mcp-Session-Id: <uuid>                  |
  |  { method: "tools/list" }                |
  |  ──────────────────────────────────────→ |
  |  ← 200 { tools: [...] }                  |
  |                                          |
  |  POST /mcp                               |
  |  Mcp-Session-Id: <uuid>                  |
  |  { method: "tools/call", params: {...} } |
  |  ──────────────────────────────────────→ |
  |  ← 200 { result: {...} }                 |
```

---

## 8. Auth Server 层详解

### 8.1 核心功能

- **Spring Authorization Server** 实现
- 支持 `authorization_code`, `client_credentials`, `refresh_token` 三种 grant_type
- DCR (Dynamic Client Registration) 开放注册
- 自定义登录页 `/vue-login` → 302 重定向到 Vue 前端

### 8.2 内部端点

| 端点 | 说明 |
|------|------|
| `/.well-known/openid-configuration` | AS 元数据 (issuer=http://localhost:9090) |
| `/oauth2/authorize` | 授权端点 |
| `/oauth2/token` | Token 端点 |
| `/oauth2/jwks` | JWKS |
| `/oauth2/register` | DCR 注册 |
| `/oauth2/introspect` | Token 内省 |
| `/oauth2/revoke` | Token 撤销 |
| `/login` | 登录处理 (POST) |
| `/vue-login` | 返回 Vue 登录页 HTML shell |

### 8.3 LoginController

`/vue-login` 端点返回一个 HTML shell，引用 Vue 前端的 JS/CSS 资源：

```html
<!DOCTYPE html>
<html lang="zh">
<head>
  <script type="module" src="/api-gateway/vue/assets/index-DBNAin-e.js"></script>
  <link rel="stylesheet" href="/api-gateway/vue/assets/index-D4U55mnN.css">
</head>
<body>
  <div id="app"></div>
</body>
</html>
```

> 资源路径使用公网地址 `/api-gateway/vue/assets/...`，浏览器直接请求 HAProxy。

### 8.4 issuer 与公网地址

| 用途 | URL |
|------|-----|
| 内部 issuer | `http://localhost:9090` |
| 公网 issuer | `http://localhost:8080/api-gateway/auth` |
| JWT 验证用 | 内部 issuer (直连 :9090 获取 JWKS) |
| 外部暴露用 | 公网 issuer (通过 Gateway RewriteAuthUrls 重写) |

---

## 9. Vue 前端详解

### 9.1 Vite 配置

```js
export default defineConfig({
  base: '/api-gateway/vue/',    // 资源 URL 前缀
  server: {
    port: 9091,
    proxy: {
      // 开发模式: API 请求代理到 api-gateway
      '/api-gateway/auth': {
        target: 'http://localhost:8081',
        rewrite: (path) => path.replace(/^\/api-gateway/, ''),
      },
    }
  }
})
```

**base = `/api-gateway/vue/`** 的原因：
- 浏览器解析 JS/CSS 资源时，URL 包含 `/api-gateway/` 前缀
- 这样请求能匹配 HAProxy 的 `backend api_gw` (path_beg /api-gateway/)
- 经过 HAProxy → API Gateway → Vite 链路正确返回资源

### 9.2 动态前缀检测 (App.vue)

```js
// 根据 URL 路径自动检测是否在网关后面
const _pathname = window.location.pathname
const _idx = _pathname.indexOf('/auth')
const gatewayPrefix = _idx >= 0 ? _pathname.slice(0, _idx) + '/auth' : ''
const loginAction = gatewayPrefix + '/login'
```

**工作原理**：
- URL = `http://localhost:8080/api-gateway/vue/` → indexOf('/auth') = 14
- gatewayPrefix = `/api-gateway/auth`
- 登录表单 action = `/api-gateway/auth/login`
- auth-info 请求 = `/api-gateway/auth/oauth2/auth-info`

> 这样无论部署在 `/api-gateway/` 还是直接 `/` 后面，都能自动适配。

### 9.3 开发模式代理

Vite dev server 的 proxy 仅用于**开发模式直接访问 :9091** 的场景：

```
浏览器 → :9091/api-gateway/auth/login
       → Vite proxy 剥 /api-gateway
       → :8081/auth/login
       → API Gateway → Auth Server
```

生产环境下，浏览器请求直接走 HAProxy :8080，不经过 Vite proxy。

---

## 10. 全部公开接口清单

### 10.1 前端接口 (Vue)

| # | 方法 | 公网 URL | 状态码 | 说明 |
|---|------|----------|--------|------|
| 1 | GET | `http://localhost:8080/api-gateway/vue/` | 200 | Vue 登录页 (index.html) |
| 2 | GET | `http://localhost:8080/api-gateway/vue` | 200 | 同上 (无尾部斜杠，Gateway 加斜杠) |
| 3 | GET | `http://localhost:8080/api-gateway/vue/assets/*` | 200 | JS/CSS 静态资源 |

### 10.2 OAuth2 / Auth 接口

| # | 方法 | 公网 URL | 状态码 | 说明 |
|---|------|----------|--------|------|
| 4 | GET | `http://localhost:8080/api-gateway/auth/.well-known/openid-configuration` | 200 | AS 元数据 |
| 5 | GET | `http://localhost:8080/.well-known/oauth-authorization-server/api-gateway/auth` | 200 | RFC 8414 AS 发现 |
| 6 | POST | `http://localhost:8080/api-gateway/auth/oauth2/register` | 201 | DCR 注册 |
| 7 | POST | `http://localhost:8080/api-gateway/auth/oauth2/token` | 200 | Token 端点 |
| 8 | GET | `http://localhost:8080/api-gateway/auth/oauth2/authorize?...` | 302 | 授权端点 (重定向到登录) |
| 9 | POST | `http://localhost:8080/api-gateway/auth/login` | 302 | 登录处理 (重定向到授权) |
| 10 | GET | `http://localhost:8080/api-gateway/auth/oauth2/jwks` | 200 | JWKS |
| 11 | GET | `http://localhost:8080/api-gateway/auth/oauth2/auth-info` | 200 | Auth 信息 API |

### 10.3 MCP 接口

| # | 方法 | 公网 URL | 状态码 | 说明 |
|---|------|----------|--------|------|
| 12 | GET | `http://localhost:8080/mcp-gateway/.well-known/oauth-protected-resource/mcp` | 200 | 资源元数据 (公开) |
| 13 | POST | `http://localhost:8080/mcp-gateway/mcp` | 401/200 | MCP 入口 (需 Bearer Token) |
| 14 | GET | `http://localhost:8080/mcp-gateway/mcp` | 401/200 | MCP SSE 入口 (需 Bearer Token) |

> 接口 13/14：无 Token 返回 401 + WWW-Authenticate；有有效 Token 返回 200。

### 10.4 辅助接口

| # | 方法 | URL | 状态码 | 说明 |
|---|------|-----|--------|------|
| 15 | GET | `http://localhost:8080/` | 200 | HAProxy 首页 (HTML) |
| 16 | * | `http://localhost:9099/mcp` | — | MCP Bearer Proxy (调试用) |

---

## 11. MCP 暴露方式

### 11.1 协议: Streamable HTTP (RFC 标准)

MCP Gateway 使用 **Streamable HTTP** 协议 (非 SSE-only)，符合 MCP 2025-03-26 规范：

- **POST `/mcp`**: JSON-RPC 请求/响应 (主要交互方式)
- **GET `/mcp`**: SSE 连接 (可选，用于服务端推送)
- **Session 管理**: `Mcp-Session-Id` 头，initialize 后获取

### 11.2 认证: OAuth2 Bearer Token (RFC 9728)

```
┌─────────────────────────────────────────────────────────┐
│              MCP OAuth2 保护架构 (RFC 9728)              │
│                                                         │
│  ① 客户端请求 MCP 端点 (无 Token)                       │
│     → 401 + WWW-Authenticate: Bearer                    │
│            resource_metadata=<protected-resource-url>    │
│                                                         │
│  ② 客户端获取 Protected Resource Metadata               │
│     → { resource, authorization_servers, scopes }        │
│                                                         │
│  ③ 客户端发现 AS (RFC 8414 或直接)                      │
│     → AS metadata (token_endpoint, register, etc.)       │
│                                                         │
│  ④ 客户端 DCR 注册 (自动)                               │
│     → { client_id, client_secret }                      │
│                                                         │
│  ⑤ 客户端获取 Token (authorization_code 或               │
│     client_credentials)                                 │
│     → { access_token }                                  │
│                                                         │
│  ⑥ 客户端请求 MCP 端点 (带 Bearer Token)                │
│     → 200 MCP 响应                                      │
└─────────────────────────────────────────────────────────┘
```

### 11.3 工具暴露: 双模式

MCP Gateway 对每个后端工具暴露两个版本：

| 模式 | 工具名格式 | 示例 | 说明 |
|------|-----------|------|------|
| 原始 | 原始名 | `getWeatherForecast` | 直接透传 |
| 前缀 | `gw_m_c_w_服务_原始名` | `gw_m_c_w_weather_getWeatherForecast` | 网关添加命名空间前缀 |

前缀规则: `{staticPrefix}_{m}_{c}_{连接首字母}_{服务名}_{原始工具名}`
- `gw` = staticPrefix
- `m` = mcp
- `c` = client
- `w` = weather (连接名首字母)
- `weather` = 服务名

### 11.4 后端 MCP 服务连接

MCP Gateway 通过 **SSE** 连接到后端 Weather Server：

```yaml
streamable-http:
  connections:
    weather:
      url: http://localhost:9092/mcp    # 内部直连，不经过 HAProxy
```

> 后端 MCP Server 与 MCP Gateway 之间是**内部通信**，不经过 HAProxy，无需认证。

### 11.5 MCP Bearer Proxy (调试辅助)

`mcp-bearer-proxy.mjs` 运行在 :9099，自动完成 OAuth2 流程：

```
MCP 客户端 → :9099/mcp (无 Token)
  → Proxy 自动: DCR + client_credentials → 获取 Bearer Token
  → Proxy 转发: :8080/mcp-gateway/mcp (带 Token)
  → 返回 MCP 响应
```

用途：为不支持 RFC 9728 OAuth2 的 MCP 客户端提供透明接入。

---

## 12. OAuth2 + DCR 完整流程

### 12.1 MCP 客户端自动认证流程 (pi 实测)

```
Step 1: 发现需要认证
─────────────────────────────────────────────────────
POST http://localhost:8080/mcp-gateway/mcp
  → 401 Unauthorized
  → WWW-Authenticate: Bearer resource_metadata=http://localhost:8080/mcp-gateway/.well-known/oauth-protected-resource/mcp

Step 2: 获取 Protected Resource Metadata
─────────────────────────────────────────────────────
GET http://localhost:8080/mcp-gateway/.well-known/oauth-protected-resource/mcp
  → 200 { "resource": "...", "authorization_servers": [...], "scopes_supported": [...] }

Step 3: RFC 8414 发现 Authorization Server
─────────────────────────────────────────────────────
GET http://localhost:8080/.well-known/oauth-authorization-server/api-gateway/auth
  → 200 (AS metadata, 所有 URL 已重写为公网地址)

Step 4: DCR 动态注册
─────────────────────────────────────────────────────
POST http://localhost:8080/api-gateway/auth/oauth2/register
  Content-Type: application/json
  Body: { "client_name": "...", "grant_types": [...], "scope": "..." }
  → 201 { "client_id": "...", "client_secret": "..." }

Step 5: 浏览器授权 (authorization_code flow)
─────────────────────────────────────────────────────
浏览器打开: http://localhost:8080/api-gateway/auth/oauth2/authorize?...
  → 302 → Vue 登录页
  → 用户输入 user/password → 提交
  → 302 → callback?code=xxx&state=yyy

Step 5-alt: 或自动获取 Token (client_credentials flow)
─────────────────────────────────────────────────────
POST http://localhost:8080/api-gateway/auth/oauth2/token
  grant_type=client_credentials&client_id=...&client_secret=...&scope=mcp:read+mcp:write
  → 200 { "access_token": "...", "expires_in": 300 }

Step 6: 带 Token 请求 MCP
─────────────────────────────────────────────────────
POST http://localhost:8080/mcp-gateway/mcp
  Authorization: Bearer <access_token>
  Mcp-Session-Id: <session-id>
  → 200 MCP 响应
```

### 12.2 预注册客户端

| client_id | grant_types | 用途 |
|-----------|------------|------|
| `springai-gateway-client` | authorization_code, refresh_token | Vue 前端登录 |
| DCR 动态注册 | authorization_code, client_credentials, refresh_token | MCP 客户端自动注册 |

> DCR 注册的客户端自动获得 `client_credentials` grant，支持自动化 Token 获取。

---

## 13. MCP 客户端接入流程 (RFC 9728)

### 13.1 标准 MCP 客户端 (支持 RFC 9728)

```
1. 配置 MCP Server URL: http://localhost:8080/mcp-gateway/mcp

2. 首次请求 → 401 + WWW-Authenticate
   ├── 解析 resource_metadata URL
   ├── GET Protected Resource Metadata
   ├── RFC 8414 发现 AS
   ├── DCR 注册
   ├── 获取 Token (authorization_code 或 client_credentials)
   └── 带 Token 重试请求

3. MCP 协议交互:
   ├── POST /mcp { initialize }  → 获取 Mcp-Session-Id
   ├── POST /mcp { notifications/initialized }
   ├── POST /mcp { tools/list }  → 获取工具列表
   └── POST /mcp { tools/call }  → 调用工具
```

### 13.2 pi MCP 客户端

pi 已实现完整的 RFC 9728 流程：

```json
// C:/Users/USER365110/.pi/agent/mcp.json
{
  "mcpServers": {
    "mcp-gateway-weather": {
      "url": "http://localhost:8080/mcp-gateway/mcp"
    }
  }
}
```

pi 的 OAuth2 流程 (HAProxy access log 实测):

```
POST /mcp-gateway/mcp                    → 401  (发现需认证)
GET /mcp-gateway/.well-known/.../mcp     → 200  (Protected Resource Metadata)
GET /.well-known/oauth-.../api-.../auth  → 200  (RFC 8414 AS 发现)
POST /api-gateway/auth/oauth2/register → 201  (DCR 注册)
GET /api-gateway/auth/oauth2/authorize → 302  (浏览器授权)
```

### 13.3 不支持 RFC 9728 的客户端

使用 MCP Bearer Proxy (:9099) 作为适配层：

```json
{
  "mcpServers": {
    "mcp-gateway-weather": {
      "url": "http://localhost:9099/mcp"
    }
  }
}
```

---

## 14. Vue 登录认证 12 步流程

完整的用户登录 → MCP 调用流程，涉及 12 个公开接口：

```
Step 1:  浏览器访问 Vue 登录页
         GET /api-gateway/vue/ → 200

Step 2:  加载 JS/CSS 资源
         GET /api-gateway/vue/assets/index-xxx.js → 200
         GET /api-gateway/vue/assets/index-xxx.css → 200

Step 3:  Vue App 动态检测 gateway prefix
         pathname.indexOf('/auth') → gatewayPrefix = /api-gateway/auth

Step 4:  Vue 获取 auth-info
         GET /api-gateway/auth/oauth2/auth-info → 200

Step 5:  用户提交登录表单
         POST /api-gateway/auth/login (username=user, password=password)

Step 6:  Auth Server 返回 302 → 授权确认
         Location: /api-gateway/auth/oauth2/authorize?...

Step 7:  授权确认 → 302 → callback
         Location: http://localhost:62211/callback?code=xxx&state=yyy

Step 8:  MCP 客户端用 code 换 Token
         POST /api-gateway/auth/oauth2/token
         → { access_token: "..." }

Step 9:  MCP 客户端获取 JWKS 验证 JWT
         GET /api-gateway/auth/oauth2/jwks → 200

Step 10: MCP initialize
         POST /mcp-gateway/mcp (Bearer Token)
         → 200 + Mcp-Session-Id

Step 11: MCP notifications/initialized
         POST /mcp-gateway/mcp (Bearer Token + Session-Id)

Step 12: MCP tools/list 或 tools/call
         POST /mcp-gateway/mcp (Bearer Token + Session-Id)
         → 200 MCP 响应
```

---

## 15. URL 重写规则汇总

### 15.1 请求路径重写

| 层 | 位置 | 输入 | 输出 | 机制 |
|----|------|------|------|------|
| HAProxy | `api_gw` (default) | `/.well-known/oauth-authorization-server/...` | 同 (不剥前缀) | 无 regsub，保留完整路径 |
| HAProxy | `api_gw` | `/api-gateway/auth/oauth2/token` | `/auth/oauth2/token` | regsub 剥 /api-gateway/ 前缀 |
| HAProxy | `mcp_gw` | `/mcp-gateway/mcp` | `/mcp` | regsub 剥 /mcp-gateway/ 前缀 |
| Gateway | rfc8414 | `/.well-known/oauth-authorization-server/任意` | `/.well-known/openid-configuration` | RewritePath |
| Gateway | auth-* | `/auth/oauth2/token` | `/oauth2/token` | StripPrefix=1 |
| Gateway | vue-login | `/vue` | `/api-gateway/vue/` | RewritePath (加前缀+斜杠) |
| Gateway | vue-assets | `/vue/assets/xxx` | `/api-gateway/vue/assets/xxx` | RewritePath (加前缀) |

### 15.2 响应头重写 (302 Location)

| 场景 | 内部 Location | 公网 Location | 机制 |
|------|--------------|--------------|------|
| 授权 → Vue 登录 | `http://localhost:9090/vue-login;...` | `http://localhost:8080/api-gateway/vue/;...` | RewriteResponseHeader |
| 授权 → Auth 路径 | `http://localhost:9090/oauth2/authorize?...` | `http://localhost:8080/api-gateway/auth/oauth2/authorize?...` | RewriteResponseHeader |
| 登录 → 授权 | `http://localhost:9090/oauth2/authorize?...` | `http://localhost:8080/api-gateway/auth/oauth2/authorize?...` | RewriteResponseHeader |

### 15.3 响应体重写 (JSON)

| 场景 | 内部 URL | 公网 URL | 机制 |
|------|---------|---------|------|
| AS 元数据 | `http://localhost:9090` | `http://localhost:8080/api-gateway/auth` | RewriteAuthUrls |
| RFC 8414 响应 | `http://localhost:9090` | `http://localhost:8080/api-gateway/auth` | RewriteAuthUrls |

### 15.4 MCP 401 头重写

| 场景 | 内部 URL | 公网 URL | 机制 |
|------|---------|---------|------|
| WWW-Authenticate | `http://127.0.0.1:8082/.well-known/...` | `http://localhost:8080/mcp-gateway/.well-known/...` | PublicUrlFilter |
| WWW-Authenticate | `http://localhost:8082/.well-known/...` | `http://localhost:8080/mcp-gateway/.well-known/...` | PublicUrlFilter |

---

## 16. 白名单与安全策略

### 16.1 API Gateway 白名单

```
公开路径 (无需认证):
  /.well-known/oauth-authorization-server/**   ← RFC 8414 AS 发现
  /auth/.well-known/**                     ← AS 元数据
  /auth/oauth2/register                    ← DCR 注册
  /auth/oauth2/token                       ← Token 端点
  /auth/oauth2/authorize                   ← 授权端点
  /auth/oauth2/jwks                        ← JWKS
  /auth/login                              ← 登录 POST
  /auth/oauth2/auth-info                   ← Auth 信息
  /vue/**                                  ← Vue 前端

需认证路径 (Bearer JWT):
  其他所有路径
```

### 16.2 MCP Gateway 安全

```
所有请求需 Bearer Token (anyRequest().authenticated())
  → 401 + WWW-Authenticate (RFC 9728)
  → Protected Resource Metadata 自动生成
  → PublicUrlFilter 重写内部 URL
```

### 16.3 HAProxy 安全

```
仅暴露 :8080 端口
  /api-gateway/  → :8081 (backend api_gw)
  /mcp-gateway/  → :8082 (backend mcp_gw)
  /.well-known/  → :8081 (default_backend api_gw，无特殊处理)
  /              → :8081 (default_backend api_gw 兜底)

内部端口 :8081/:8082/:9090/:9091/:9092 不对外暴露
```

---

## 17. 配置文件索引

| 文件 | 路径 | 说明 |
|------|------|------|
| HAProxy 配置 | `haproxy.cfg` | 反向代理，仅 backend 路由 + regsub |
| API Gateway 配置 | `api-gateway/src/main/resources/application.yml` | 路由 + 过滤器 + 白名单 |
| API Gateway 安全 | `api-gateway/src/main/java/es/omarall/mcp/apigateway/GatewaySecurityConfig.java` | 白名单 + JWT 验证 |
| RewriteAuthUrls | `api-gateway/src/main/java/es/omarall/mcp/apigateway/RewriteAuthUrlsGatewayFilterFactory.java` | JSON body URL 重写 |
| WhitelistProperties | `api-gateway/src/main/java/es/omarall/mcp/apigateway/WhitelistProperties.java` | 白名单配置绑定 |
| MCP Gateway 配置 | `mcp-gateway/src/main/resources/application.yml` | MCP 协议 + 后端连接 + OAuth2 |
| MCP Gateway 安全 | `mcp-gateway/src/main/java/es/omarall/mcp/gateway/SecurityConfiguration.java` | RFC 9728 OAuth2 配置 |
| PublicUrlFilter | `mcp-gateway/src/main/java/es/omarall/mcp/gateway/PublicUrlFilter.java` | 401 头 URL 重写 |
| LoginController | `auth-server/src/main/java/.../LoginController.java` | Vue 登录页 HTML shell |
| Vite 配置 | `login-ui-server/vite.config.js` | base + proxy |
| Vue App | `login-ui-server/src/App.vue` | 动态前缀检测 |
| MCP Bearer Proxy | `mcp-bearer-proxy.mjs` | 自动 Token 获取代理 |
| pi MCP 配置 | `C:/Users/USER365110/.pi/agent/mcp.json` | MCP 客户端连接配置 |

---

## 附录 A: 流量转发完整示例

### A.1 用户打开 Vue 登录页

```
URL: http://localhost:8080/api-gateway/vue/

[浏览器] GET /api-gateway/vue/
    |
    ▼
[HAProxy] backend api_gw: /api-gateway/ → :8081 (regsub 剥前缀)
    |
    ▼
[API Gateway] 路由 vue-login: Path=/vue
    | RewritePath: /vue → /api-gateway/vue/
    |
    ▼
[Vite :9091] base=/api-gateway/vue/
    | 返回 index.html (含 Vue SPA)
    |
    ▼
[浏览器] 解析 index.html, 请求:
    GET /api-gateway/vue/assets/index-DBNAin-e.js
    GET /api-gateway/vue/assets/index-D4U55mnN.css
    |
    ▼
[HAProxy] → [API Gateway vue-assets] → [Vite] → 返回 JS/CSS
    |
    ▼
[浏览器] Vue SPA 渲染, 执行 App.vue:
    pathname = /api-gateway/vue/
    indexOf('/auth') = 14
    gatewayPrefix = /api-gateway/auth
    fetch(/api-gateway/auth/oauth2/auth-info) → 显示登录表单
```

### A.2 MCP 客户端调用 getWeatherForecast

```
[MCP Client] POST /mcp-gateway/mcp
    Authorization: Bearer <token>
    Mcp-Session-Id: <session>
    Body: { "method": "tools/call", "params": { "name": "getWeatherForecast", "arguments": { "latitude": 47.6062, "longitude": -122.3321 } } }
    |
    ▼
[HAProxy] backend mcp_gw: /mcp-gateway/ → :8082 (regsub 剥前缀)
    |
    ▼
[MCP Gateway :8082]
    | 验证 Bearer Token (JWT)
    | 解析 JSON-RPC
    | 路由到 weather 后端
    |
    ▼
[Weather Server :9092] (SSE 连接)
    | 执行 getWeatherForecast(47.6062, -122.3321)
    | 返回天气预报数据
    |
    ▼
[MCP Gateway] 组装 JSON-RPC 响应
    |
    ▼
[HAProxy] → [MCP Client]
    200 { "jsonrpc": "2.0", "result": { "content": [...] } }
```

---

## 附录 B: 端口与依赖关系矩阵

```
          │ HAProxy│ API-GW │ MCP-GW │ Auth │ Vite │ Weather │ Proxy
          │ :8080 │ :8081  │ :8082  │:9090 │:9091 │  :9092  │:9099
──────────┼───────┼────────┼────────┼──────┼──────┼─────────┼──────
HAProxy   │  self │   →    │   →    │      │      │         │
API-GW    │       │  self  │        │  →   │  →   │         │
MCP-GW    │       │        │  self  │  →   │      │    →    │
Auth      │       │        │        │ self │      │         │
Vite      │       │   →    │        │      │ self │         │
Weather   │       │        │        │      │      │  self   │
Proxy     │   →   │        │        │  →   │      │         │  self

self = 自己监听    → = 依赖/连接到
```

---

## 附录 C: AS Metadata 完整内容 (公网)

```json
{
  "issuer": "http://localhost:8080/api-gateway/auth",
  "authorization_endpoint": "http://localhost:8080/api-gateway/auth/oauth2/authorize",
  "token_endpoint": "http://localhost:8080/api-gateway/auth/oauth2/token",
  "registration_endpoint": "http://localhost:8080/api-gateway/auth/oauth2/register",
  "jwks_uri": "http://localhost:8080/api-gateway/auth/oauth2/jwks",
  "introspection_endpoint": "http://localhost:8080/api-gateway/auth/oauth2/introspect",
  "revocation_endpoint": "http://localhost:8080/api-gateway/auth/oauth2/revoke",
  "scopes_supported": ["openid", "offline_access", "mcp:read", "mcp:write"],
  "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token"],
  "response_types_supported": ["code"],
  "code_challenge_methods_supported": ["S256"],
  "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "none"],
  "revocation_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post"],
  "introspection_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post"]
}
```

---

## 附录 D: MCP Protected Resource Metadata

```json
{
  "resource": "http://localhost:8080/mcp-gateway/weather/mcp",
  "authorization_servers": ["http://localhost:8080/api-gateway/auth"],
  "resource_name": "Spring MCP Gateway",
  "bearer_methods_supported": ["header"],
  "scopes_supported": ["mcp:read", "mcp:write"]
}
```

---

## 18. API Key 认证详解

### 双部件 AccessKey 模型 (对标阿里云 AccessKey)

| 部件 | 格式 | 说明 |
|------|------|------|
| AccessKey ID | `ak-<20hex>` | 公开标识，用于查找 |
| AccessKey Secret | `sk-<40hex>` | 私密，160-bit 熵，HMAC 模式下不传输 |

### 认证模式

| 模式 | 格式 | 安全级别 |
|------|------|----------|
| Bearer | `Authorization: Bearer ak-xxx:sk-yyy` | 中 (secret 传输) |
| HMAC | `X-AccessKey-Id` + `X-AccessKey-Signature` + `X-AccessKey-Timestamp` | 高 (secret 不传输) |
| Admin | `Authorization: Bearer adm-xxx` | 管理 (bcrypt 验证) |

### 暴力破解防护
- 10 次失败 → 5 分钟封禁 (per AccessKey ID)
- ConcurrentHashMap 实现 (单实例)

### Scope 执行
- `mcp:read`: MCP 工具调用
- `mcp:admin`: 管理操作
- 缺少 scope → 403

### 默认凭证 (开发环境)
- API Key: `ak-36f8ea0fc5ad9937572d:sk-8665c9bbdd338e3ce03a0fdf115fbf65685b2b94`
- Admin Token: `adm-a4596ca59d33d7cd005c2367a0c657c7`
- **生产环境务必修改!**

---

## 19. 管理控制台详解

### URL
`http://localhost:8080/api-gateway/admin/`

### 架构
- **页面**: API Gateway whitelist `/admin/**` → Vite:9094
- **API 调用**: `/mcp-gateway/admin/**` (HAProxy 直连 8082, 避免 JWT 过滤)
- **登录**: sys_user username/password → admin token (bcrypt)

### Dual SecurityFilterChain
| Chain | Order | 匹配 | 认证 |
|-------|-------|------|------|
| 0 | `/admin/**` | permitAll | 控制器自行验证 adm-xxx token |
| 1 | 其余所有 | JWT + API Key | BearerTokenAuthenticationFilter |

### 页面
| 页面 | 功能 |
|------|------|
| 📊 仪表盘 | 统计卡片 + 认证模式概览 |
| 🔑 AK 凭证 | API Key CRUD + scope |
| 📋 OAuth 客户端 | 客户端列表 + 详情面板 + 搜索/过滤 |
| 👤 用户 | 用户 CRUD (admin 不可删除) |
| 📡 MCP 服务 | 服务状态 + PRM + 连通性测试 |
| 🛡️ 安全 | 认证流程图 + 姿态概览 |
| 🖥️ 系统 | Java 版本/内存/线程/uptime/DCR |

---

## 20. MCP SDK RFC 8414 §3.3 修复

### 问题
`@modelcontextprotocol/client` v2.0.0-beta.5 的 issuer 校验有 bug:
```js
// index.mjs:1093
const expectedIssuer = typeof authorizationServerUrl === "string" ? authorizationServerUrl : authorizationServerUrl.href;
if (!(parsed.issuer === expectedIssuer || ...)) throw new IssuerMismatchError(...);
```

当 AS 的 issuer 带路径 (`http://localhost:8080/api-gateway/auth`) 时，
SDK 用 `new URL(authorizationServers[0]).origin` (`http://localhost:8080`) 作 expectedIssuer，导致不匹配。

### 修复 (Patch SDK, 不改 Gateway)
```js
// Patch 后: 额外允许同 origin 的 path-based issuer
parsed.issuer === expectedIssuer
  || new URL(expectedIssuer).origin === new URL(parsed.issuer).origin
```

### Patch 位置
- `~/.pi/agent/npm/node_modules/@modelcontextprotocol/client/dist/index.mjs` (line 1093)
- `~/.pi/agent/npm/node_modules/@modelcontextprotocol/client/dist/index.cjs` (line 1093)

> ⚠️ SDK 升级后 patch 会丢失，需重新应用。

---

## 21. CORS 配置详解

### AllowedOriginPatterns
```
http://localhost:*
http://127.0.0.1:*
null
```

### 为什么包含 `null`
浏览器在 redirect 后的表单 POST 中发送 `Origin: null` (W3C opaque origin)。
如果不允许 `null`，CORS 拦截导致 403。

### 应用范围
所有 3 个服务 (api-gateway, auth-server, mcp-gateway) 统一配置。

### 生产环境
限制为 `https://your-domain.com`，移除 `null` 和 `localhost:*`。

---

## 22. 安全硬化汇总

| 版本 | 修复项 | 详情 |
|------|--------|------|
| v0.3.0 | 两层客户端模型 | DCR 客户端禁止 client_credentials |
| v0.7.0 | H1: DCR denyAll → 403 JSON | 不泄露 localhost:9090 |
| v0.7.0 | H2: auth-info 脱敏 | 不返回 clientId/redirectUri |
| v0.7.0 | M1: Cookie 安全 | SameSite=Lax + Path=/api-gateway/auth |
| v0.7.0 | M2: CORS 限制 | localhost/127.0.0.1/null |
| v0.7.0 | M3: del-header Server | HAProxy 版本不泄露 |
| v0.7.0 | M4: root / → 404 | 不泄露架构信息 |
| v0.7.0 | M5: text/html Content-Type | LoginController |
| v0.9.0 | CORS Origin:null | 浏览器表单 POST 支持 |
| v0.9.0 | RFC 9728 动态 host | PRM/WWW-Authenticate 匹配请求 host |
| v0.9.0 | HAProxy http-request set-header | 每个 backend 完整 7 个 header |
| v0.11.0 | AK 双部件模型 | ak-xxx:sk-yyy, 强随机密钥 |
| v0.11.0 | 暴力破解防护 | 10 次 → 5 分钟封禁 |
| v0.11.0 | bcrypt admin token | timing-safe 比较 |
| v0.12.1 | Dual SecurityFilterChain | /admin/** 无 JWT 过滤 |
| v0.13.1 | SDK issuer patch | 允许 path-based issuer |
| v0.14.0 | HAProxy 替换 nginx | haproxy 2.8.26 (Cygwin), del-header Server, regsub 路径重写 |

---

## 23. 版本历史

| 版本 | 里程碑 | 关键变更 |
|------|--------|----------|
| v0.1.0 | 基线 | HAProxy + API Gateway + Auth + MCP Gateway + Vue |
| v0.2.0 | Security fix | DCR secret 90d 过期, token TTL 可配置 |
| v0.3.0 | 两层客户端 | DCR 禁止 client_credentials, public PKCE |
| v0.4.0 | 双 MCP 后端 | +climate-server, 多租户路由 |
| v0.5.0 | MySQL 持久化 | MyBatis-Plus, ClientSettings 手动解析 |
| v0.5.1 | PKCE 验证 | 完整 DCR + PKCE + MCP 验证 |
| v0.6.0 | 预注册模式 | DCR 可开关, 稳定 client_id |
| v0.6.1 | 预注册验证 | 无 DCR 完整 PKCE 流程 |
| v0.6.2 | 多租户 PRM | Per-Service WWW-Authenticate |
| v0.7.0 | 安全硬化 | H1-H2, M1-M5, CORS, Cookie |
| v0.8.0 | 纯透明代理 | 移除 MCP 聚合, McpServiceRouterController |
| v0.9.0 | CORS+RFC9728 | Origin:null, 动态 host, 浏览器登录 |
| v0.10.0 | API Key | 静态凭证 (对标阿里云) |
| v0.11.0 | AK 安全 | 双部件 + 强随机 + 暴力破解防护 |
| v0.12.0 | Admin 控制台 | Vue3 SPA, 7 页面 |
| v0.12.1 | Admin API | Dual SecurityFilterChain, sys_user 登录 |
| v0.12.2 | Admin 登录 UI | 用户名密码 + 用户/客户端 CRUD |
| v0.13.0 | Admin v2 | 仪表盘 + 客户端详情 + 系统状态 |
| **v0.13.1** | **SDK issuer patch** | **Patch @modelcontextprotocol/client RFC 9728** |
| **v0.14.0** | **HAProxy 替换 nginx** | **haproxy 2.8.26 (Cygwin Windows build), del-header Server, regsub 路径重写** |

---

## 24. 生产部署检查清单

### 网络层
- [ ] TLS (HTTPS) — HAProxy 配置证书
- [ ] 内部端口绑定 `server.address: 127.0.0.1`
- [ ] 防火墙规则阻止外部访问 9090-9099

### 应用层
- [ ] DCR 关闭 (`mcp.dcr.enabled: false`)
- [ ] 修改默认 admin 密码 (admin/admin → 强密码)
- [ ] bcrypt `admin-token-hash` (不用 plaintext `admin-token`)
- [ ] `cookie.secure: true`
- [ ] CORS 限制到生产域名
- [ ] HAProxy rate limiting for `/oauth2/token`

### MCP 层
- [ ] AES-GCM 加密 secret 存储 (完成 HMAC 签名验证)
- [ ] 重新应用 MCP SDK issuer patch (如 SDK 升级)
- [ ] pi mcp.json 使用 `oauth.clientId` (预注册模式)

### 验证
- [ ] 完整 DCR + PKCE + MCP 流程验证 (通过 :8080)
- [ ] 安全审计 (所有 :8080 端点)
- [ ] Admin 控制台功能验证

---

*文档结束 — 封板 v0.14.0*

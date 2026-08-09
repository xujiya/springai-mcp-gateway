# API Gateway — Spring Cloud Gateway (WebFlux)

> v0.13.1 封板 | 端口: 8081

## 职责

- 前端透传路由 (Vue + Admin Console)
- Auth Server 路由 + StripPrefix
- URL 重写 (JSON body + 302 Location header)
- CORS 处理
- RFC 8414 AS 发现路由

**核心原则**: 所有 URL 重写在本层完成，Nginx 只做 `proxy_pass`。

## 路由表

| 路径 | 目标 | Filter | 说明 |
|------|------|--------|------|
| `/ecso/vue/**` | Vite:9091 | RewritePath | Vue 登录前端 |
| `/ecso/admin/**` | Vite:9094 | RewritePath | 管理控制台 |
| `/ecso/auth/**` | Auth:9090 | StripPrefix=2 | OAuth2 端点 |
| `/.well-known/oauth-authorization-server/**` | Auth:9090 | RewritePath | RFC 8414 发现 |

## URL 重写

### RewriteAuthUrls (JSON body)

将 JSON 响应中的内部 URL (`localhost:9090`) 重写为公开 URL (`localhost:8080/api-gateway/ecso/auth`)。

### RewriteResponseHeader (302 Location)

将 302 Location header 中的内部地址重写为公开地址。

## CORS

```java
AllowedOriginPatterns: http://localhost:*, http://127.0.0.1:*, null
```

- `null` 用于浏览器表单 POST 后的 `Origin: null` (W3C opaque origin)
- OPTIONS /** permitAll — 解决 preflight 403

## Whitelist (公开端点)

```
/.well-known/oauth-authorization-server/**
/ecso/vue/**
/ecso/admin/**
/ecso/auth/.well-known/**
/ecso/auth/oauth2/register
/ecso/auth/oauth2/token
/ecso/auth/oauth2/jwks
/ecso/auth/oauth2/authorize
/ecso/auth/login
/ecso/auth/oauth2/auth-info
```

## 关键文件

```
api-gateway/src/main/java/es/omarall/mcp/apigateway/
├── GatewaySecurityConfig.java            # CORS + OPTIONS permitAll + whitelist
├── WhitelistProperties.java              # 白名单配置
├── RewriteAuthUrlsGatewayFilterFactory.java  # JSON body URL 重写
└── RedirectLocationRewriteGatewayFilterFactory.java  # 302 Location 重写
```

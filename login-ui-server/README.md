# Vue 登录前端 — Vite Dev Server

> 端口: 9091 | URL: `http://localhost:8080/api-gateway/ecso/vue/`

## 说明

Auth Server 的登录页面，Vue3 SPA，由 Vite 开发服务器提供。

- `base: /api-gateway/ecso/vue/` — 确保浏览器解析的 asset URL 包含 `/api-gateway/` 前缀
- `host: '0.0.0.0'` — 必须! Vite 默认绑定 `[::1]` (IPv6 only)，API Gateway 用 IPv4 连接
- `App.vue` 动态计算 `gatewayPrefix` via `location.pathname.indexOf('/ecso')`

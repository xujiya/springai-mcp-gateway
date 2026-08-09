# Admin Console — Vue3 管理控制台

> v0.13.1 封板 | 端口: 9094 | URL: `http://localhost:8080/api-gateway/admin/`

## 功能

- 🔑 AK 凭证管理 (CRUD + scope)
- 📋 OAuth 客户端管理 (详情面板 + 搜索/过滤)
- 👤 用户管理 (CRUD, admin 不可删除)
- 📡 MCP 服务监控 (PRM + 连通性测试)
- 🛡️ 安全仪表盘 (认证流程图 + 姿态概览)
- 🖥️ 系统状态 (Java 版本/内存/线程/uptime)
- 📊 仪表盘 (统计卡片 + 认证模式概览)

## 架构

- **页面**: 通过 API Gateway whitelist 路由 (`/admin/**` → Vite:9094)
- **API 调用**: 通过 `/mcp-gateway/` (HAProxy 直连 8082, 避免 JWT 过滤)
- **登录**: sys_user 表的 username/password → admin token

## 技术栈

- Vue 3 + Vite
- axios (interceptors for admin token)
- localStorage (adminToken, username)

## 配置

```js
// vite.config.js
server: {
  port: 9094,
  host: '0.0.0.0',  // 必须! Vite 默认绑定 [::1] (IPv6 only)
  proxy: {
    '/mcp-gateway': { target: 'http://localhost:8082' }
  }
}
base: '/api-gateway/admin/'
```

## 关键文件

```
admin-console/
├── package.json
├── vite.config.js
├── index.html
└── src/
    ├── main.js
    ├── App.vue              # 7 tabs + 登录表单
    ├── api/
    │   └── admin.js         # API 层 (login + CRUD)
    └── views/
        ├── DashboardView.vue
        ├── ApiKeysView.vue
        ├── ClientsView.vue
        ├── UsersView.vue
        ├── OAuthView.vue
        ├── ServicesView.vue
        ├── SecurityView.vue
        └── SystemView.vue
```

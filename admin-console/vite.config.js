import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/api-gateway/ecso/admin/',
  plugins: [vue()],
  server: {
    host: '0.0.0.0', // 必须绑定 0.0.0.0，否则 Vite 默认只绑 IPv6 [::1]，API Gateway (IPv4) 连不上
    port: 9094,
    proxy: {
      // API 调用 /mcp-gateway/ → nginx 已有，但 dev 模式需要直接代理
      '/mcp-gateway': {
        target: 'http://localhost:8082',
        rewrite: (path) => path.replace(/^\/mcp-gateway/, ''),
      },
      // Auth server 元数据
      '/api-gateway/ecso/auth': {
        target: 'http://localhost:8081',
        rewrite: (path) => path.replace(/^\/api-gateway/, ''),
      },
    }
  },
  preview: {
    port: 9094,
  }
})

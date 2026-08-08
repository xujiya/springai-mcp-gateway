import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/api-gateway/ecso/vue/',
  plugins: [vue()],
  server: {
    port: 9091,
    proxy: {
      // Vue app API calls go through api-gateway (nginx already strips /api-gateway/)
      '/api-gateway/ecso/auth': {
        target: 'http://localhost:8081',
        rewrite: (path) => path.replace(/^\/api-gateway/, ''),
      },
    }
  },
  preview: {
    port: 9091,
  }
})

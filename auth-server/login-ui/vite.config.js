import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/login': 'http://localhost:9090',
      '/oauth2': 'http://localhost:9090',
      '/.well-known': 'http://localhost:9090',
    }
  }
})

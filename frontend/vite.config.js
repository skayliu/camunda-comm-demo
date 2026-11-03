import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物直接输出到 Spring Boot 静态目录，部署后与后端同源，
// 无需额外配置 CORS / 反向代理。
export default defineConfig({
  plugins: [vue()],
  base: '/app/',
  build: {
    outDir: '../src/main/resources/static/app',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})

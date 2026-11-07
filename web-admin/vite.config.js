import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5174,
    proxy: { '/api': { target: process.env.VITE_DEV_API_PROXY_TARGET || 'http://localhost:8080', changeOrigin: true } }
  }
})

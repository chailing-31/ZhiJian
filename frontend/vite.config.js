import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// The browser always calls /api. Only the Vite server connects to Spring Boot.
const proxy = {
  '/api': {
    target: 'http://127.0.0.1:8080',
    changeOrigin: true,
    rewrite: path => path.replace(/^\/api/, ''),
  },
}
export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 4173, strictPort: true, proxy },
})

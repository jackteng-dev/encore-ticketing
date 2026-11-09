import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
const proxy = { '/api': { target: process.env.TICKET_API_TARGET || 'http://127.0.0.1:19110', changeOrigin: false } }
export default defineConfig({ plugins: [vue()], server: { host: '127.0.0.1', port: 15180, strictPort: true, proxy }, preview: { host: '127.0.0.1', port: 15180, strictPort: true, proxy } })

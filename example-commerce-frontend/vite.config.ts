import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    // 开发期代理：免 CORS 直连后端（生产静态托管走后端 CORS）
    proxy: {
      '/agent': { target: 'http://localhost:8080', changeOrigin: true },
      '/memory': { target: 'http://localhost:8080', changeOrigin: true },
      '/rag': { target: 'http://localhost:8080', changeOrigin: true },
      '/user': { target: 'http://localhost:8080', changeOrigin: true },
      '/auth': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1024,
  },
});

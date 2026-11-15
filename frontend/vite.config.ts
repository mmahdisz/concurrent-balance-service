import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [react()],
  server: { proxy: Object.fromEntries(['/accounts', '/api', '/actuator', '/swagger-ui', '/v3'].map(path =>
    [path, { target: process.env.API_TARGET || 'http://localhost:8080', changeOrigin: true }])) },
  build: { outDir: 'dist' },
});

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies API calls to the Ktor backend (default :8080, same as the old CRA proxy).
// Override the target with VITE_API_TARGET when the tool runs on a different port.
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
  build: {
    // Keep output in build/ so the gradle buildFrontend copy step is unchanged.
    outDir: 'build',
    emptyOutDir: true,
  },
});

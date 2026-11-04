import vue from '@vitejs/plugin-vue';
import { defineConfig, loadEnv } from 'vite';

// The dev server proxies the FastAPI app so the browser stays on one origin;
// the SSE endpoint (/api/screen/stream) works through the same proxy.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_API_PROXY || 'http://127.0.0.1:8000',
          changeOrigin: true,
        },
      },
    },
    build: {
      chunkSizeWarningLimit: 1500,
    },
  };
});

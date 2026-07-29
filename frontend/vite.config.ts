import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  base: '/smartCockpit/',
  plugins: [vue()],
  build: {
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('echarts') || id.includes('zrender')) return 'vendor-echarts';
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element-plus';
          if (id.includes('vue') || id.includes('pinia')) return 'vendor-vue';
          return 'vendor-misc';
        }
      }
    }
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/smartCockpit/api': {
        target: process.env.VITE_BACKEND_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/smartCockpit\/api/, '/api')
      }
    }
  }
});

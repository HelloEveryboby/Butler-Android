import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
    // 使用相对路径，确保在 Capacitor WebView 和鸿蒙 Web 组件中都能正确加载资源
    assetsDir: 'assets',
    // 确保资源引用使用相对路径而非绝对路径
    assetsInlineLimit: 4096,
    rollupOptions: {
      output: {
        // 确保所有资源使用相对路径
        assetFileNames: 'assets/[name]-[hash][extname]',
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/[name]-[hash].js',
      },
    },
  },
  // 确保 base 为相对路径，兼容所有平台 WebView
  base: './',
});

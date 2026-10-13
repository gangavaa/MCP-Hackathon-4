import { defineConfig } from 'vite';

// The API serves the built dashboard on its own port; `npm run dev:dashboard` proxies to it for hot reload.
export default defineConfig({
  root: 'src/dashboard',
  build: {
    outDir: '../../dist/dashboard',
    emptyOutDir: true,
    target: 'es2022',
    chunkSizeWarningLimit: 1200, // ECharts
    rolldownOptions: {
      input: { index: 'src/dashboard/index.html', host: 'src/dashboard/host.html' },
    },
  },
  server: {
    port: 5180,
    proxy: { '/api': 'http://localhost:4300' },
  },
});

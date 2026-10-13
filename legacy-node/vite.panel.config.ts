import { defineConfig } from 'vite';
import { viteSingleFile } from 'vite-plugin-singlefile';

// The MCP App is served as one HTML resource, so everything — ECharts included — is inlined into it.
export default defineConfig({
  root: 'src/panel',
  plugins: [viteSingleFile()],
  build: {
    outDir: '../../dist/panel',
    emptyOutDir: true,
    target: 'es2022',
    chunkSizeWarningLimit: 1200, // ECharts, inlined
  },
});

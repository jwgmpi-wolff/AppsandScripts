import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

const projectRoot = path.resolve(__dirname).replace(/\\/g, '/')
const projectRootLowerDrive = projectRoot.replace(/^[A-Z]:/, (m) => m.toLowerCase())

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    fs: {
      strict: false,
      allow: [projectRoot, projectRootLowerDrive],
    },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8787',
        changeOrigin: true,
      },
    },
    watch: {
      // The project root is inside a .git folder, which chokidar ignores by default.
      ignored: (filePath: string) => filePath.includes('node_modules'),
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})

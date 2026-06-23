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
    fs: {
      strict: false,
      allow: [projectRoot, projectRootLowerDrive],
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})

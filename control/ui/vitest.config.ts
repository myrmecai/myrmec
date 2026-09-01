import { defineConfig } from 'vitest/config'
import path from 'path'

export default defineConfig({
  test: {
    // Only run unit tests from src/ — exclude e2e/ (Playwright specs)
    include: ['src/**/*.test.{ts,tsx}'],
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    globals: true,
    environment: 'jsdom',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
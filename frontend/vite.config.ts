import { execFileSync } from 'node:child_process'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { loadEnv } from 'vite'

// Node 25+의 실험용 localStorage는 jsdom Storage와 호환되지 않는다.
function disableNodeWebStorageArgs(): string[] {
  for (const flag of ['--no-webstorage', '--no-experimental-webstorage']) {
    try {
      execFileSync(process.execPath, [flag, '--eval', ''], { stdio: 'ignore' })
      return [flag]
    } catch {
      continue
    }
  }
  return []
}

const DEFAULT_API_PROXY_TARGET = 'http://localhost:8000'

function apiProxyTarget(mode: string): string {
  const configured = loadEnv(mode, process.cwd(), '').VITE_API_BASE_URL?.trim()
  if (configured !== undefined && /^https?:\/\//i.test(configured)) {
    return configured.replace(/\/+$/, '')
  }
  return DEFAULT_API_PROXY_TARGET
}

export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    proxy: {
      // Toss returns this same-origin server callback URL. The API itself has no /api
      // context path, so strip the browser-only prefix before forwarding it.
      '/api': {
        target: apiProxyTarget(mode),
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    execArgv: disableNodeWebStorageArgs(),
    setupFiles: ['./src/test/setup.ts'],
    globals: false,
    // Playwright 사양은 별도 인프라를 사용하는 E2E 스위트다.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
}))

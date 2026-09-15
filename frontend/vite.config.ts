import { execFileSync } from 'node:child_process'
import process from 'node:process'
import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

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

export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    execArgv: disableNodeWebStorageArgs(),
    setupFiles: ['./src/test/setup.ts'],
    globals: false,
    // Playwright 사양은 별도 인프라를 사용하는 E2E 스위트다.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})

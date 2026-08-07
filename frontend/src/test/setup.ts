import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// globals: false 설정이라 Testing Library의 자동 정리가 걸리지 않는다 — 직접 건다.
afterEach(() => {
  cleanup()
})

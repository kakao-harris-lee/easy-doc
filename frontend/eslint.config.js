import js from '@eslint/js'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import prettier from 'eslint-config-prettier'
import globals from 'globals'
import tseslint from 'typescript-eslint'

// jsx-a11y는 KWCAG 기본 원칙(label·키보드 조작·대체 텍스트)의 자동 검사 축이다.
// 사람이 봐야 하는 항목(대비, 초점 순서)까지 잡아주지는 않으므로 최소선으로만 쓴다.
export default tseslint.config(
  // playwright-report·test-results 는 실행 산출물이다(추적하지 않는다).
  { ignores: ['dist', 'coverage', 'playwright-report', 'test-results'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommended,
      jsxA11y.flatConfigs.recommended,
      reactHooks.configs.flat['recommended-latest'],
      // prettier 설정은 마지막에 둔다 — 서식 관련 규칙을 전부 끈다(포매터가 단일 기준).
      prettier,
    ],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
    plugins: { 'react-refresh': reactRefresh },
    rules: {
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // 경고는 게이트를 통과시키므로 남겨두면 의미가 없다 — 오류로 올린다.
      'no-console': 'error',
    },
  },
  {
    // 테스트 파일은 node 전역(process 등)과 vitest 전역을 함께 쓴다.
    // 브라우저 E2E(`e2e/`)도 같다 — node 에서 돌지만 `page.evaluate` 본문은 브라우저다.
    files: ['**/*.test.{ts,tsx}', 'src/test/**/*.ts', 'e2e/**/*.ts', 'playwright.config.ts'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
  },
)

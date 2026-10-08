import js from '@eslint/js'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import prettier from 'eslint-config-prettier'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  {
    ignores: ['dist', 'dist-ssr', 'coverage', 'playwright-report', 'test-results'],
  },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommended,
      jsxA11y.flatConfigs.recommended,
      reactHooks.configs.flat['recommended-latest'],
      prettier,
    ],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
    plugins: { 'react-refresh': reactRefresh },
    rules: {
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      'no-console': 'error',
    },
  },
  {
    files: ['**/*.test.{ts,tsx}', 'src/test/**/*.ts', 'e2e/**/*.ts', 'playwright*.config.ts'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
  },
  // shadcn/ui의 링크 스타일 API는 Button과 buttonVariants를 함께 내보낸다.
  {
    files: ['src/components/shadcn/button.tsx'],
    rules: {
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true, allowExportNames: ['buttonVariants'] },
      ],
    },
  },
  // 프리렌더 엔트리는 빌드 때 Node에서만 돌고 클라이언트 번들에 들어가지 않는다 —
  // Fast Refresh 규칙(컴포넌트만 내보내라)이 적용될 파일이 아니다.
  {
    files: ['src/prerender/**/*.{ts,tsx}'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
  // 빌드 스크립트는 Node에서 바로 도는 ESM이다(타입 검사 대상이 아니라 별도 블록).
  {
    files: ['scripts/**/*.mjs'],
    extends: [js.configs.recommended, prettier],
    languageOptions: { ecmaVersion: 2023, sourceType: 'module', globals: globals.node },
    rules: { 'no-console': 'error' },
  },
)

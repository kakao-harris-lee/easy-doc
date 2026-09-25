/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 서버 주소. 개발 기본값은 http://localhost:8000, 배포는 nginx 프록시 경로. */
  readonly VITE_API_BASE_URL?: string
  /** 초등 3~4학년 목표 선택 카드. 명시적으로 true일 때만 노출한다. */
  readonly VITE_EASYDOC_EXTRA_EASY_ENABLED?: string
}

/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 서버 주소. 개발 기본값은 http://localhost:8000, 배포는 nginx 프록시 경로. */
  readonly VITE_API_BASE_URL?: string
}

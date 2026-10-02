/**
 * `/` 프리렌더용 Node 전용 SSR 엔트리.
 *
 * `App` 셸(헤더·푸터·`AuthProvider`)은 렌더하지 않는다 — 셸은 인증 상태 fetch에 묶여
 * 있어 빌드 시점에 렌더할 대상이 아니다. `LandingPage`만 `StaticRouter`로 감싸
 * `renderToStaticMarkup`한다(`Link`가 라우터 컨텍스트를 요구한다).
 *
 * `renderToString`이 아니라 `renderToStaticMarkup`인 이유: 클라이언트는
 * `createRoot(...).render`로 마운트해 프리렌더 내용을 교체하므로(`src/main.tsx`)
 * hydration 표식이 필요하지 않다.
 *
 * 빌드 스크립트는 이 번들 하나만 import한다 — 순수 조립 함수와 상수까지 여기서
 * 다시 내보내는 것은 `.mjs` 스크립트가 TypeScript를 직접 읽을 수 없기 때문이다.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { StaticRouter } from 'react-router-dom'

import { COMPANY_INFO } from '../config/company'
import { SERVICE_DEFINITION } from '../content/identity'
import { LandingPage } from '../pages/LandingPage'
import { HOME_PATH } from '../routes/paths'

export function renderLandingMarkup(): string {
  return renderToStaticMarkup(
    <StaticRouter location={HOME_PATH}>
      <LandingPage />
    </StaticRouter>,
  )
}

/** JSON-LD `Organization.name`. 푸터가 쓰는 사업자 상호와 같은 출처다. */
export const ORGANIZATION_NAME = COMPANY_INFO.name

export { SERVICE_DEFINITION }
export {
  CANONICAL_URL,
  buildLandingHtml,
  buildLandingJsonLd,
  buildSitemap,
  seoulDateString,
} from './buildLandingHtml'

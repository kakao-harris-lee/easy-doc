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
 * 산출물 조립은 `buildLandingArtifacts` 한 함수에 모은다 — 빌드 스크립트(`.mjs`)는
 * TypeScript를 직접 읽을 수 없어 이 번들만 import하고, 어떤 문장·이름이 어디로
 * 들어가는지는 테스트로 고정할 수 있어야 한다.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { StaticRouter } from 'react-router-dom'

import { COMPANY_INFO } from '../config/company'
import { SERVICE_DEFINITION } from '../content/identity'
import { LandingPage } from '../pages/LandingPage'
import { HOME_PATH } from '../routes/paths'
import {
  CANONICAL_URL,
  buildLandingHtml,
  buildLandingJsonLd,
  buildSitemap,
  seoulDateString,
} from './buildLandingHtml'

export function renderLandingMarkup(): string {
  return renderToStaticMarkup(
    <StaticRouter location={HOME_PATH}>
      <LandingPage />
    </StaticRouter>,
  )
}

/** JSON-LD `Organization.name`. 푸터가 쓰는 사업자 상호와 같은 출처다. */
export const ORGANIZATION_NAME = COMPANY_INFO.name

export interface LandingArtifacts {
  readonly landingHtml: string
  readonly sitemapXml: string
}

/**
 * 셸 템플릿 하나로 `landing.html`과 `sitemap.xml` 내용을 만든다.
 *
 * `now`를 인자로 받는 이유는 테스트가 날짜를 고정할 수 있어야 하기 때문이다 —
 * 스크립트만 실제 시계를 넘긴다.
 */
export function buildLandingArtifacts({
  template,
  now,
}: {
  readonly template: string
  readonly now: Date
}): LandingArtifacts {
  return {
    landingHtml: buildLandingHtml({
      template,
      markup: renderLandingMarkup(),
      description: SERVICE_DEFINITION,
      canonicalUrl: CANONICAL_URL,
      jsonLd: buildLandingJsonLd({ organizationName: ORGANIZATION_NAME }),
    }),
    sitemapXml: buildSitemap(seoulDateString(now)),
  }
}

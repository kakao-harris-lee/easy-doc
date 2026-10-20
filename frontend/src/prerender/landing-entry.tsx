/**
 * 공개 홈페이지와 이용 가이드의 Node 전용 SSR 엔트리.
 *
 * 인증 상태 fetch에 묶인 `App` 셸은 렌더하지 않는다. 공개 페이지와 공통 푸터를
 * `StaticRouter`로 감싸 렌더한다(`Link`가 라우터 컨텍스트를 요구한다).
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

import { Footer } from '../components/Footer'
import { CONTAINER } from '../components/layoutStyles'
import { COMPANY_INFO } from '../config/company'
import {
  GUIDE_DESCRIPTION,
  GUIDE_PAGE_TITLE,
  SERVICE_DEFINITION,
  SERVICE_PAGE_TITLE,
} from '../content/identity'
import { GuidePage } from '../pages/GuidePage'
import { LandingPage } from '../pages/LandingPage'
import { GUIDE_PATH, HOME_PATH } from '../routes/paths'
import {
  CANONICAL_URL,
  GUIDE_CANONICAL_URL,
  buildLandingHtml,
  buildLandingJsonLd,
  buildSitemap,
  seoulDateString,
} from './buildLandingHtml'

export function renderLandingMarkup(): string {
  return renderToStaticMarkup(
    <StaticRouter location={HOME_PATH}>
      <main className={`${CONTAINER} py-6`}>
        <LandingPage />
      </main>
      <Footer />
    </StaticRouter>,
  )
}

/** JSON-LD `Organization.name`. 푸터가 쓰는 사업자 상호와 같은 출처다. */
export const ORGANIZATION_NAME = COMPANY_INFO.name

export interface LandingArtifacts {
  readonly landingHtml: string
  readonly guideHtml: string
  readonly sitemapXml: string
}

/**
 * 셸 템플릿 하나로 두 공개 페이지와 사이트맵을 만든다.
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
      title: SERVICE_PAGE_TITLE,
      canonicalUrl: CANONICAL_URL,
      jsonLd: buildLandingJsonLd({ organizationName: ORGANIZATION_NAME }),
    }),
    guideHtml: buildLandingHtml({
      template,
      markup: renderToStaticMarkup(
        <StaticRouter location={GUIDE_PATH}>
          <main className={`${CONTAINER} py-6`}>
            <GuidePage />
          </main>
          <Footer />
        </StaticRouter>,
      ),
      description: GUIDE_DESCRIPTION,
      title: GUIDE_PAGE_TITLE,
      canonicalUrl: GUIDE_CANONICAL_URL,
      jsonLd: [],
    }),
    sitemapXml: buildSitemap(seoulDateString(now)),
  }
}

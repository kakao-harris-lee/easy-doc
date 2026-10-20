import { describe, expect, it } from 'vitest'

import { COMPANY_INFO } from '../config/company'
import { GUIDE_PAGE_TITLE, SERVICE_DEFINITION, SERVICE_PAGE_TITLE } from '../content/identity'
import { buildLandingArtifacts } from './landing-entry'

/** Vite가 내보내는 `dist/index.html`의 모양을 줄인 템플릿. */
const TEMPLATE = `<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="robots" content="noindex" />
    <meta
      name="description"
      content="셸 기본 설명"
    />
    <meta property="og:description" content="셸 기본 설명" />
    <meta name="twitter:description" content="셸 기본 설명" />
    <meta property="og:title" content="셸 제목" />
    <meta name="twitter:title" content="셸 제목" />
    <meta property="og:url" content="https://easydoc.kr/" />
    <title>EASY-DOC AI</title>
    <script type="module" crossorigin src="/assets/index-abc123.js"></script>
    <link rel="modulepreload" crossorigin href="/assets/vendor-abc123.js">
    <link rel="stylesheet" crossorigin href="/assets/index-abc123.css">
  </head>
  <body>
    <div id="root"></div>
  </body>
</html>
`

function countOf(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1
}

describe('buildLandingArtifacts', () => {
  const artifacts = buildLandingArtifacts({
    template: TEMPLATE,
    now: new Date('2026-10-02T15:30:00Z'),
  })

  it('서비스 정의 문장을 본문과 description 메타 세 곳에 함께 싣는다', () => {
    const head = artifacts.landingHtml.slice(0, artifacts.landingHtml.indexOf('</head>'))
    const body = artifacts.landingHtml.slice(artifacts.landingHtml.indexOf('<div id="root">'))

    expect(countOf(head, `content="${SERVICE_DEFINITION}"`)).toBe(3)
    // 메타만 채우고 본문이 비면 색인해도 읽을 내용이 없다.
    expect(body).toContain(SERVICE_DEFINITION)
    expect(body).toContain('<h1')
  })

  it('색인 규칙은 canonical 하나와 noindex 없음이다', () => {
    expect(countOf(artifacts.landingHtml, 'rel="canonical"')).toBe(1)
    expect(countOf(artifacts.landingHtml, 'noindex')).toBe(0)
    expect(artifacts.landingHtml).toContain('<meta name="robots" content="index,follow" />')
  })

  it('JSON-LD의 Organization 이름은 푸터가 쓰는 사업자 상호다', () => {
    expect(artifacts.landingHtml).toContain(`"name":"${COMPANY_INFO.name}"`)
  })

  it('sitemap의 lastmod는 주어진 시각의 서울 날짜다', () => {
    expect(countOf(artifacts.sitemapXml, '<loc>https://easydoc.kr/</loc>')).toBe(1)
    expect(artifacts.sitemapXml).toContain('<lastmod>2026-10-03</lastmod>')
  })

  it('가이드는 고유 제목·대표 주소와 실제 본문을 가진 검색 가능 페이지다', () => {
    expect(artifacts.landingHtml).toContain(`<title>${SERVICE_PAGE_TITLE}</title>`)
    expect(artifacts.guideHtml).toContain(`<title>${GUIDE_PAGE_TITLE}</title>`)
    expect(artifacts.guideHtml).toContain('rel="canonical" href="https://easydoc.kr/guide"')
    expect(artifacts.guideHtml).toContain('property="og:url" content="https://easydoc.kr/guide"')
    expect(artifacts.guideHtml).toContain('무료로 먼저 써 보세요')
    expect(artifacts.guideHtml).toContain('결과 확인하고 내려받기')
    expect(artifacts.guideHtml).not.toContain('noindex')
    expect(artifacts.sitemapXml).toContain('<loc>https://easydoc.kr/guide</loc>')
    expect(countOf(artifacts.sitemapXml, '<loc>')).toBe(2)
  })

  it('검색로봇도 회사 정보와 무료 체험 조건을 읽을 수 있다', () => {
    expect(artifacts.landingHtml).toContain('카드 등록 없이 체험 5크레딧')
    expect(artifacts.landingHtml).toContain('가입·휴대폰 인증')
    expect(artifacts.landingHtml).toContain(COMPANY_INFO.businessRegistrationNumber)
    expect(artifacts.guideHtml).toContain(COMPANY_INFO.businessRegistrationNumber)
    expect(artifacts.landingHtml).toContain(COMPANY_INFO.mailOrderRegistrationNumber)
    expect(artifacts.guideHtml).toContain(COMPANY_INFO.mailOrderRegistrationNumber)
  })
})

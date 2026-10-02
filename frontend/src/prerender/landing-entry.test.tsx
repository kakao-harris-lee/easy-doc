import { describe, expect, it } from 'vitest'

import { COMPANY_INFO } from '../config/company'
import { SERVICE_DEFINITION } from '../content/identity'
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
})

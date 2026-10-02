import { describe, expect, it } from 'vitest'

import { SERVICE_NAME } from '../content/identity'
import {
  CANONICAL_URL,
  buildLandingHtml,
  buildLandingJsonLd,
  buildSitemap,
  seoulDateString,
} from './buildLandingHtml'

/** `dist/index.html`(셸)의 모양을 줄인 템플릿. 여러 줄 메타 태그를 일부러 섞는다. */
const TEMPLATE = `<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="robots" content="noindex" />
    <link rel="icon" href="/favicon.ico" />
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

const MARKUP = '<article><h1>다양한 안내·설명문을 쉬운 글로</h1><p>본문</p></article>'

const DESCRIPTION = '행정·복지·법률 안내문을 쉬운 글 초안으로 바꿉니다.'

const JSON_LD = buildLandingJsonLd({ organizationName: '몬딱 솔루션' })

function build(overrides: Partial<Parameters<typeof buildLandingHtml>[0]> = {}): string {
  return buildLandingHtml({
    template: TEMPLATE,
    markup: MARKUP,
    description: DESCRIPTION,
    canonicalUrl: CANONICAL_URL,
    jsonLd: JSON_LD,
    ...overrides,
  })
}

function countOf(html: string, needle: string): number {
  return html.split(needle).length - 1
}

describe('buildLandingHtml', () => {
  it('프리렌더 마크업을 #root 안에 넣는다', () => {
    const html = build()

    expect(html).toContain(`<div id="root">${MARKUP}</div>`)
    // 클라이언트 번들 스크립트는 그대로 남아 React가 내용을 교체한다.
    expect(html).toContain(
      '<script type="module" crossorigin src="/assets/index-abc123.js"></script>',
    )
  })

  it('canonical은 정확히 하나다', () => {
    const html = build()

    expect(countOf(html, 'rel="canonical"')).toBe(1)
    expect(html).toContain(`<link rel="canonical" href="${CANONICAL_URL}" />`)
  })

  it('셸의 canonical이 이미 있어도 중복되지 않는다', () => {
    const withCanonical = TEMPLATE.replace(
      '<meta charset="UTF-8" />',
      '<meta charset="UTF-8" />\n    <link rel="canonical" href="https://example.test/" />',
    )

    const html = build({ template: withCanonical })

    expect(countOf(html, 'rel="canonical"')).toBe(1)
    expect(html).not.toContain('https://example.test/')
  })

  it('noindex를 지우고 index,follow를 넣는다', () => {
    const html = build()

    expect(countOf(html, 'noindex')).toBe(0)
    expect(countOf(html, '<meta name="robots" content="index,follow" />')).toBe(1)
  })

  it('description·og:description·twitter:description을 모두 같은 문장으로 채운다', () => {
    const html = build()

    expect(countOf(html, '셸 기본 설명')).toBe(0)
    for (const attribute of [
      'name="description"',
      'property="og:description"',
      'name="twitter:description"',
    ]) {
      const tag = html.match(new RegExp(`<meta[^>]*${attribute}[^>]*>`))?.[0]
      expect(tag).toBeDefined()
      expect(tag).toContain(`content="${DESCRIPTION}"`)
    }
  })

  it('JSON-LD 하나에 Organization과 WebSite를 담는다', () => {
    const html = build()

    expect(countOf(html, 'application/ld+json')).toBe(1)
    const payload = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/)?.[1]
    expect(payload).toBeDefined()
    const parsed: unknown = JSON.parse((payload ?? '').replace(/\\u003c/g, '<'))
    expect(Array.isArray(parsed)).toBe(true)
    const nodes = parsed as Array<Record<string, unknown>>
    expect(nodes.map((node) => node['@type'])).toEqual(['Organization', 'WebSite'])
    expect(nodes[0]).toMatchObject({
      '@context': 'https://schema.org',
      name: '몬딱 솔루션',
      url: CANONICAL_URL,
      logo: 'https://easydoc.kr/icons/icon-320.png',
    })
    expect(nodes[1]).toMatchObject({
      '@context': 'https://schema.org',
      name: SERVICE_NAME,
      url: CANONICAL_URL,
      inLanguage: 'ko',
    })
  })

  it('JSON-LD 본문의 < 를 이스케이프해 script 밖으로 나가지 못하게 한다', () => {
    const html = build({ jsonLd: [{ '@type': 'WebSite', name: '</script><img src=x>' }] })

    expect(html).not.toContain('</script><img')
    expect(countOf(html, '</script>')).toBe(countOf(TEMPLATE, '</script>') + 1)
  })

  it('마크업·설명의 $& 같은 치환 패턴이 그대로 남는다', () => {
    // String.replace에 치환 문자열을 넘기면 `$&`·`` $` ``가 주변 텍스트로 바뀐다.
    const markup = '<article><h1>제목 $& 뒤</h1><p>$` 앞 · $$ · $1</p></article>'

    const html = buildLandingHtml({
      template: TEMPLATE,
      markup,
      description: '설명 $& 와 $` 와 $1',
      canonicalUrl: CANONICAL_URL,
      jsonLd: JSON_LD,
    })

    // 마크업은 이미 HTML이라 그대로 들어간다.
    expect(html).toContain(`<div id="root">${markup}</div>`)
    // 속성 값에서는 `&`만 이스케이프되고 `$` 시퀀스는 해석되지 않는다.
    expect(html).toContain('content="설명 $&amp; 와 $` 와 $1"')
  })

  it('마크업에 h1이 없으면 빌드를 실패시킨다', () => {
    expect(() => build({ markup: '<article><p>제목 없음</p></article>' })).toThrow(/h1/)
  })

  it('</head>가 없으면 빌드를 실패시킨다', () => {
    expect(() => build({ template: TEMPLATE.replace('</head>', '') })).toThrow(/head/)
  })

  it('#root 자리가 없으면 빌드를 실패시킨다', () => {
    expect(() => build({ template: TEMPLATE.replace('<div id="root"></div>', '') })).toThrow(
      /#root/,
    )
  })

  it('채울 description 메타가 없으면 빌드를 실패시킨다', () => {
    const withoutOg = TEMPLATE.replace(
      '<meta property="og:description" content="셸 기본 설명" />\n',
      '',
    )

    expect(() => build({ template: withoutOg })).toThrow(/og:description/)
  })

  it('robots 외의 noindex가 남으면 빌드를 실패시킨다', () => {
    const withGooglebot = TEMPLATE.replace(
      '<meta name="robots" content="noindex" />',
      '<meta name="robots" content="noindex" />\n    <meta name="googlebot" content="noindex" />',
    )

    expect(() => build({ template: withGooglebot })).toThrow(/noindex/)
  })

  it('빈 description이면 빌드를 실패시킨다', () => {
    expect(() => build({ description: '' })).toThrow(/description/)
  })
})

describe('buildSitemap', () => {
  it('loc 하나와 주어진 lastmod를 담는다', () => {
    const xml = buildSitemap('2026-10-02')

    expect(countOf(xml, '<loc>https://easydoc.kr/</loc>')).toBe(1)
    expect(xml).toContain('<lastmod>2026-10-02</lastmod>')
    expect(xml.startsWith('<?xml version="1.0" encoding="UTF-8"?>')).toBe(true)
    expect(xml).toContain('xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"')
    expect(xml.endsWith('\n')).toBe(true)
  })

  it('YYYY-MM-DD가 아닌 lastmod는 거부한다', () => {
    expect(() => buildSitemap('2026-10-2')).toThrow(/lastmod/)
    expect(() => buildSitemap('')).toThrow(/lastmod/)
  })
})

describe('seoulDateString', () => {
  it('UTC 자정 전이라도 서울 날짜로 하루를 넘긴다', () => {
    expect(seoulDateString(new Date('2026-10-02T15:30:00Z'))).toBe('2026-10-03')
  })

  it('서울 기준 같은 날이면 UTC 날짜와 같다', () => {
    expect(seoulDateString(new Date('2026-10-02T03:00:00Z'))).toBe('2026-10-02')
  })
})

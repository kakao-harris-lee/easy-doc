import { describe, expect, it } from 'vitest'

import { COMPANY_INFO } from '../config/company'
import {
  GUIDE_DESCRIPTION,
  GUIDE_PAGE_TITLE,
  LANDING_META_DESCRIPTION,
  SERVICE_DEFINITION,
  SERVICE_PAGE_TITLE,
} from '../content/identity'
import { GUIDE_LEAD } from '../content/guide/userGuide'
import { buildLandingArtifacts } from './landing-entry'
import {
  DOCUMENT_EXAMPLES,
  EXAMPLE_NOTICE,
  EXAMPLES_INTRO,
  exampleDescription,
  examplePath,
} from '../content/documentExamples'

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

/** 메타가 아니라 사람이 읽는 본문만 잘라 본다. */
function bodyOf(html: string): string {
  return html.slice(html.indexOf('<div id="root">'))
}

function parseJsonLd(html: string): Array<Record<string, unknown>> {
  const payload = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/)?.[1]
  expect(payload).toBeDefined()
  const parsed: unknown = JSON.parse((payload ?? '').replace(/\\u003c/g, '<'))
  expect(Array.isArray(parsed)).toBe(true)
  return parsed as Array<Record<string, unknown>>
}

describe('buildLandingArtifacts', () => {
  const artifacts = buildLandingArtifacts({
    template: TEMPLATE,
    now: new Date('2026-10-02T15:30:00Z'),
  })

  it('description 메타 세 곳은 검색용 설명문이고 본문은 정체성 문장을 유지한다', () => {
    const head = artifacts.landingHtml.slice(0, artifacts.landingHtml.indexOf('</head>'))
    const body = bodyOf(artifacts.landingHtml)

    expect(countOf(head, `content="${LANDING_META_DESCRIPTION}"`)).toBe(3)
    // 검색용 설명문은 정체성 문장으로 시작한다 — 화면과 검색 결과의 첫 문장이 같다.
    expect(LANDING_META_DESCRIPTION.startsWith(SERVICE_DEFINITION)).toBe(true)
    expect(LANDING_META_DESCRIPTION).not.toContain('발달장애인')
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
    expect(artifacts.guideHtml).toContain('무료 체험')
    expect(artifacts.guideHtml).toContain('결과 확인과 수정')
    expect(artifacts.guideHtml).toContain('30일 후 자동 삭제')
    expect(artifacts.guideHtml).toContain('DOCX·HWPX → 같은 형식')
    expect(artifacts.guideHtml).not.toContain('noindex')
    expect(artifacts.sitemapXml).toContain('<loc>https://easydoc.kr/guide</loc>')
    expect(countOf(artifacts.sitemapXml, '<loc>')).toBe(6)
  })

  it('가이드의 검색용 설명문은 메타에만 쓰고 본문 검사는 가이드 첫 문장이 받는다', () => {
    const head = artifacts.guideHtml.slice(0, artifacts.guideHtml.indexOf('</head>'))

    expect(countOf(head, `content="${GUIDE_DESCRIPTION}"`)).toBe(3)
    // 가이드 문구의 출처는 `user-guide.md` 하나다 — 그 첫 문장이 본문에 있어야 한다.
    expect(GUIDE_LEAD).not.toBe('')
    expect(bodyOf(artifacts.guideHtml)).toContain(GUIDE_LEAD)
  })

  it('검색로봇도 회사 정보와 무료 체험 조건을 읽을 수 있다', () => {
    expect(artifacts.landingHtml).toContain('카드 등록 없이 체험 5크레딧')
    expect(artifacts.landingHtml).toContain('가입·휴대폰 인증')
    expect(artifacts.landingHtml).toContain(COMPANY_INFO.businessRegistrationNumber)
    expect(artifacts.guideHtml).toContain(COMPANY_INFO.businessRegistrationNumber)
    expect(artifacts.landingHtml).toContain(COMPANY_INFO.mailOrderRegistrationNumber)
    expect(artifacts.guideHtml).toContain(COMPANY_INFO.mailOrderRegistrationNumber)
  })

  it('예시 목록과 상세 원문은 JavaScript 없이 읽을 수 있고 고유 대표 주소를 가진다', () => {
    expect(artifacts.examplePages).toHaveLength(4)
    expect(artifacts.sitemapXml).toContain('<loc>https://easydoc.kr/examples</loc>')
    for (const example of DOCUMENT_EXAMPLES) {
      const page = artifacts.examplePages.find(
        (item) => item.filename === `examples/${example.id}.html`,
      )
      expect(page).toBeDefined()
      expect(page?.html).toContain(example.title)
      expect(page?.html).toContain(example.original.split('\n\n')[1])
      expect(page?.html).toContain(EXAMPLE_NOTICE)
      expect(page?.html).toContain(
        `rel="canonical" href="https://easydoc.kr/examples/${example.id}"`,
      )
      expect(page?.html).toContain(
        `property="og:url" content="https://easydoc.kr/examples/${example.id}"`,
      )
      expect(page?.html).not.toContain('noindex')
      expect(countOf(page?.html ?? '', 'rel="canonical"')).toBe(1)
      expect(artifacts.sitemapXml).toContain(`<loc>https://easydoc.kr/examples/${example.id}</loc>`)
    }
    expect(artifacts.sitemapXml).not.toContain('/login')
    expect(artifacts.sitemapXml).not.toContain('/example-files')
  })

  it('예시 상세는 Article과 BreadcrumbList 구조화 데이터를 싣는다', () => {
    for (const example of DOCUMENT_EXAMPLES) {
      const page = artifacts.examplePages.find(
        (item) => item.filename === `examples/${example.id}.html`,
      )
      const nodes = parseJsonLd(page?.html ?? '')

      expect(nodes.map((node) => node['@type'])).toEqual(['Article', 'BreadcrumbList'])
      expect(nodes[0]).toMatchObject({
        headline: example.title,
        description: exampleDescription(example),
        inLanguage: 'ko',
        url: `https://easydoc.kr/examples/${example.id}`,
        image: 'https://easydoc.kr/og/landing.png',
        datePublished: example.datePublished,
        dateModified: example.dateModified,
        isPartOf: { '@type': 'WebSite', url: 'https://easydoc.kr/' },
        author: { '@type': 'Organization', name: COMPANY_INFO.name },
        publisher: { '@type': 'Organization', name: COMPANY_INFO.name },
      })
      // 검색 결과 설명문은 화면에도 보이는 문장이다 — 메타만 채우지 않는다.
      expect(bodyOf(page?.html ?? '')).toContain(exampleDescription(example))
      expect(nodes[1]?.['itemListElement']).toEqual([
        { '@type': 'ListItem', position: 1, name: '홈', item: 'https://easydoc.kr/' },
        {
          '@type': 'ListItem',
          position: 2,
          name: '쉬운 글 예시',
          item: 'https://easydoc.kr/examples',
        },
        {
          '@type': 'ListItem',
          position: 3,
          name: example.title,
          item: `https://easydoc.kr/examples/${example.id}`,
        },
      ])
      // 뒷받침할 질문·답 본문이 없는 FAQPage는 넣지 않는다.
      expect(page?.html).not.toContain('FAQPage')
      expect(page?.html).not.toContain('SoftwareApplication')
    }
  })

  it('예시 목록에는 BreadcrumbList만 싣고 어떤 문서를 다루는지 소개한다', () => {
    const page = artifacts.examplePages.find((item) => item.filename === 'examples/index.html')
    const nodes = parseJsonLd(page?.html ?? '')

    expect(nodes.map((node) => node['@type'])).toEqual(['BreadcrumbList'])
    expect(nodes[0]?.['itemListElement']).toEqual([
      { '@type': 'ListItem', position: 1, name: '홈', item: 'https://easydoc.kr/' },
      {
        '@type': 'ListItem',
        position: 2,
        name: '쉬운 글 예시',
        item: 'https://easydoc.kr/examples',
      },
    ])
    expect(page?.html).toContain(EXAMPLES_INTRO)
  })

  it('예시 상세 본문에 바꾼 점 설명과 다른 예시 링크를 함께 싣는다', () => {
    for (const example of DOCUMENT_EXAMPLES) {
      const page = artifacts.examplePages.find(
        (item) => item.filename === `examples/${example.id}.html`,
      )

      // 접힌 details 안이 아니라 항상 보이는 본문이어야 색인에 쓸 분량이 된다.
      expect(page?.html).toContain('이 예시에서 바꾼 점')
      expect(page?.html).toContain(example.audience)
      for (const point of example.rationale) {
        expect(page?.html).toContain(point)
      }
      for (const other of DOCUMENT_EXAMPLES.filter((item) => item.id !== example.id)) {
        expect(page?.html).toContain(`href="${examplePath(other)}"`)
      }
      expect(page?.html).toContain('내 문서를 쉬운 글로 바꿔 보기')
    }
  })

  it('다운로드 원문에도 가상 자료 표시를 포함하고 원문을 그대로 보존한다', () => {
    expect(artifacts.exampleFiles).toHaveLength(3)
    DOCUMENT_EXAMPLES.forEach((example) => {
      const file = artifacts.exampleFiles.find(
        (item) => item.filename === `example-files/${example.id}.txt`,
      )
      expect(file?.text).toBe(`${EXAMPLE_NOTICE}\n\n${example.original}\n`)
    })
  })
})

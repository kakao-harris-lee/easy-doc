/**
 * `/` 프리렌더 산출물(`dist/landing.html`)과 `dist/sitemap.xml`을 만드는 순수 함수들.
 *
 * 빌드 스크립트(`scripts/prerender-landing.mjs`)는 파일 입출력만 하고 문자열 조립은
 * 전부 여기서 한다 — 색인 규칙(canonical 하나, noindex 없음, 한 출처 description)은
 * Vitest로 고정할 수 있어야 하기 때문이다.
 *
 * 조립이 조용히 실패하면 검색 엔진에는 빈 `<div id="root">`만 노출되므로, 이 모듈의
 * 모든 검사는 경고가 아니라 예외다. 예외는 빌드를 non-zero로 끝낸다.
 */
import { SERVICE_NAME } from '../content/identity'

/**
 * `/`의 정본 주소. 랜딩·sitemap·JSON-LD가 같은 값을 쓴다 — 셸은 noindex라
 * canonical을 두지 않는다.
 */
export const CANONICAL_URL = 'https://easydoc.kr/'

/** JSON-LD `Organization.logo`. 1200×630 OG 이미지가 아니라 정사각 로고를 쓴다. */
export const ORGANIZATION_LOGO_URL = 'https://easydoc.kr/icons/icon-320.png'

export type JsonLdNode = Readonly<Record<string, unknown>>

export interface BuildLandingHtmlInput {
  /** `dist/index.html`. Vite가 번들 태그를 꽂은 셸이다. */
  readonly template: string
  /** `renderToStaticMarkup(<LandingPage/>)` 결과. */
  readonly markup: string
  /** `SERVICE_DEFINITION`. description 계열 메타 세 개를 모두 이 문장으로 채운다. */
  readonly description: string
  readonly canonicalUrl: string
  readonly jsonLd: readonly JsonLdNode[]
}

const ROOT_PLACEHOLDER = /<div id="root">\s*<\/div>/
const META_TAG = /<meta\b[^>]*>/g
const CANONICAL_LINK = /[ \t]*<link\b[^>]*rel="canonical"[^>]*>\n?/g
const ROBOTS_META = /[ \t]*<meta\b[^>]*name="robots"[^>]*>\n?/g
const CONTENT_ATTRIBUTE = /content="[^"]*"/
const HEAD_CLOSE = /([ \t]*)<\/head>/

/**
 * 랜딩에 싣는 구조화 데이터. `Organization`·`WebSite` 두 타입만 넣는다.
 *
 * 본문에는 사업자명·로고가 없다 — 근거는 화면이 아니라 푸터와 같은 출처
 * (`COMPANY_INFO`)를 쓴다는 점이다. 뒷받침할 본문이 없는 `SoftwareApplication`·
 * `FAQPage`는 넣지 않는다.
 */
export function buildLandingJsonLd({
  organizationName,
}: {
  readonly organizationName: string
}): readonly JsonLdNode[] {
  return [
    {
      '@context': 'https://schema.org',
      '@type': 'Organization',
      name: organizationName,
      url: CANONICAL_URL,
      logo: ORGANIZATION_LOGO_URL,
    },
    {
      '@context': 'https://schema.org',
      '@type': 'WebSite',
      name: SERVICE_NAME,
      url: CANONICAL_URL,
      inLanguage: 'ko',
    },
  ]
}

function escapeAttribute(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * `name`/`property`가 `selector`인 메타 태그의 `content`를 바꾼다.
 *
 * 셸의 메타는 prettier가 여러 줄로 쪼개 두므로 태그 하나를 통째로 잡아 그 안에서만
 * 치환한다. 대상이 없으면 조용히 넘기지 않고 예외를 던진다 — 메타가 사라지면
 * `identity.ts` 한 출처 약속이 깨지는데 결과 HTML만 보면 알 수 없다.
 */
function setMetaContent(html: string, selector: string, content: string): string {
  let replaced = false
  const next = html.replace(META_TAG, (tag) => {
    if (replaced || !tag.includes(selector)) {
      return tag
    }
    if (!CONTENT_ATTRIBUTE.test(tag)) {
      throw new Error(`메타 태그 ${selector}에 content 속성이 없다`)
    }
    replaced = true
    // 치환 문자열이 아니라 함수로 넘긴다 — content의 `$&`·`` $` `` 가 해석되면 안 된다.
    return tag.replace(CONTENT_ATTRIBUTE, () => `content="${escapeAttribute(content)}"`)
  })
  if (!replaced) {
    throw new Error(`셸 템플릿에 ${selector} 메타 태그가 없어 description을 채울 수 없다`)
  }
  return next
}

function countOf(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1
}

export function buildLandingHtml({
  template,
  markup,
  description,
  canonicalUrl,
  jsonLd,
}: BuildLandingHtmlInput): string {
  if (!markup.includes('<h1')) {
    throw new Error('프리렌더 마크업에 h1이 없다 — 랜딩이 비어 있다')
  }
  if (description.trim() === '') {
    throw new Error('랜딩 description이 비어 있다')
  }
  if (!ROOT_PLACEHOLDER.test(template)) {
    throw new Error('셸 템플릿에서 빈 #root 요소를 찾지 못했다')
  }
  const headClose = HEAD_CLOSE.exec(template)
  if (headClose === null) {
    throw new Error('셸 템플릿에 </head>가 없다')
  }
  const indent = headClose[1] ?? ''
  const childIndent = `${indent}  `

  // 마크업에 `$&` 같은 치환 패턴이 있어도 그대로 들어가도록 함수로 넘긴다.
  let html = template.replace(ROOT_PLACEHOLDER, () => `<div id="root">${markup}</div>`)
  html = html.replace(CANONICAL_LINK, '').replace(ROBOTS_META, '')
  html = setMetaContent(html, 'name="description"', description)
  html = setMetaContent(html, 'property="og:description"', description)
  html = setMetaContent(html, 'name="twitter:description"', description)

  // `</script>`가 본문에 섞여 script를 조기 종료하지 못하게 `<`만 이스케이프한다.
  const jsonLdPayload = JSON.stringify(jsonLd).replace(/</g, '\\u003c')
  const headAdditions = [
    `${childIndent}<link rel="canonical" href="${escapeAttribute(canonicalUrl)}" />`,
    `${childIndent}<meta name="robots" content="index,follow" />`,
    `${childIndent}<script type="application/ld+json">${jsonLdPayload}</script>`,
    `${indent}</head>`,
  ].join('\n')
  html = html.replace(HEAD_CLOSE, () => headAdditions)

  if (!html.includes('<h1')) {
    throw new Error('결과 HTML에 h1이 없다')
  }
  // 비교는 이스케이프한 형태로 한다 — 메타 content와 React 본문 모두 `&`를
  // `&amp;`로 쓰므로, 원문 그대로 찾으면 `&`가 든 문장에서 빌드가 헛되게 실패한다.
  if (!html.includes(escapeAttribute(description))) {
    throw new Error('결과 HTML에 서비스 정의 문장(description)이 없다')
  }
  if (html.includes('noindex')) {
    throw new Error('결과 HTML에 noindex가 남아 있다 — 랜딩이 색인되지 않는다')
  }
  const canonicalCount = countOf(html, 'rel="canonical"')
  if (canonicalCount !== 1) {
    throw new Error(`결과 HTML의 canonical이 1개가 아니다: ${String(canonicalCount)}개`)
  }
  return html
}

const LASTMOD_FORMAT = /^\d{4}-\d{2}-\d{2}$/

/** 색인 대상은 `/` 하나다. 라우트가 늘어나면 이 함수가 목록을 받도록 넓힌다. */
export function buildSitemap(lastmod: string): string {
  if (!LASTMOD_FORMAT.test(lastmod)) {
    throw new Error(`sitemap lastmod가 YYYY-MM-DD 형식이 아니다: ${lastmod}`)
  }
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    '  <url>',
    `    <loc>${CANONICAL_URL}</loc>`,
    `    <lastmod>${lastmod}</lastmod>`,
    '  </url>',
    '</urlset>',
    '',
  ].join('\n')
}

const SEOUL_OFFSET_MS = 9 * 60 * 60 * 1000

/**
 * 서울 기준 `YYYY-MM-DD`. 한국 표준시는 일광 절약 시간이 없어 고정 UTC+9이므로
 * ICU 없이도 오프셋 덧셈으로 구할 수 있다(빌드 이미지의 ICU 구성에 의존하지 않는다).
 */
export function seoulDateString(now: Date): string {
  return new Date(now.getTime() + SEOUL_OFFSET_MS).toISOString().slice(0, 10)
}

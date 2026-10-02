/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

/**
 * SPA 셸 템플릿의 색인 규칙을 소스 파일에서 직접 고정한다.
 *
 * 프리렌더 스크립트는 셸의 `noindex` 메타를 지우고 canonical을 넣는 식으로 동작하므로
 * (`buildLandingHtml.ts`), 셸이 조용히 반대로 바뀌면 `/`와 그 외 경로의 색인 규칙이
 * 한꺼번에 무너진다. 빌드 산출물이 아니라 템플릿을 읽어 그 전제를 지킨다.
 */
const shell = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../index.html'),
  'utf8',
)

function countOf(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1
}

describe('frontend/index.html (SPA 셸)', () => {
  it('noindex 메타가 정확히 하나 있다', () => {
    // 문자열이 바뀌면 프리렌더 쪽 제거 규칙과 어긋난다.
    expect(countOf(shell, '<meta name="robots" content="noindex" />')).toBe(1)
    expect(countOf(shell, 'noindex')).toBe(1)
  })

  it('canonical은 두지 않는다', () => {
    // noindex 페이지가 `/`를 정본으로 가리키는 모순을 만들지 않는다.
    expect(countOf(shell, 'rel="canonical"')).toBe(0)
  })

  it('OG·Twitter 이미지는 1200×630 공유 카드를 가리킨다', () => {
    expect(shell).toContain(
      '<meta property="og:image" content="https://easydoc.kr/og/landing.png" />',
    )
    expect(shell).toContain(
      '<meta name="twitter:image" content="https://easydoc.kr/og/landing.png" />',
    )
    expect(countOf(shell, '/og/landing.png')).toBe(2)
  })
})

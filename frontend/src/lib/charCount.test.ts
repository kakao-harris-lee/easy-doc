import { describe, expect, it } from 'vitest'

import { countChars } from './charCount'

describe('countChars', () => {
  it('일반 문자는 코드 유닛 수와 같다', () => {
    expect(countChars('안녕하세요')).toBe(5)
  })

  it('surrogate pair(이모지)는 코드 포인트 1로 센다', () => {
    // '😀'는 UTF-16으로 2 코드 유닛(surrogate pair)이지만 코드 포인트는 1이다.
    expect('😀'.length).toBe(2)
    expect(countChars('😀')).toBe(1)
  })

  it('surrogate pair 문자가 반복돼도 code point 기준으로 센다', () => {
    const text = '😀'.repeat(20000)
    expect(text.length).toBe(40000)
    expect(countChars(text)).toBe(20000)
  })

  it('빈 문자열은 0이다', () => {
    expect(countChars('')).toBe(0)
  })
})

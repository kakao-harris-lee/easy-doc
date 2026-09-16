import { describe, expect, it } from 'vitest'

import { ONE_TIME_CODE_LENGTH, sanitizeOneTimeCode } from './oneTimeCode'

describe('sanitizeOneTimeCode', () => {
  it('숫자가 아닌 문자를 지운다', () => {
    expect(sanitizeOneTimeCode('12-34ab56')).toBe('123456')
  })

  it(`${ONE_TIME_CODE_LENGTH}자리를 넘으면 자른다`, () => {
    expect(sanitizeOneTimeCode('1234567890')).toBe('123456')
  })

  it('빈 문자열은 그대로 빈 문자열이다', () => {
    expect(sanitizeOneTimeCode('')).toBe('')
  })
})

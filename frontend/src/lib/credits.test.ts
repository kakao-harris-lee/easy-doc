import { describe, expect, it } from 'vitest'

import { creditsForCharCount, formatCredits, isCreditAmount } from './credits'

describe('fractional credit policy', () => {
  it.each([
    [0, 0],
    [1, 0.1],
    [100, 0.1],
    [101, 0.2],
    [1000, 1],
    [1001, 1.1],
  ])('%i characters costs %s credits', (characters, expected) => {
    expect(creditsForCharCount(characters)).toBe(expected)
  })

  it('formats tenth credits without binary floating point noise', () => {
    expect(formatCredits(0.1 + 0.2)).toBe('0.3')
    expect(formatCredits(1)).toBe('1')
    expect(formatCredits(1.1)).toBe('1.1')
  })

  it('accepts signed nonzero tenths for admin adjustments only', () => {
    expect(isCreditAmount(0.1)).toBe(true)
    expect(isCreditAmount(-1.1)).toBe(true)
    expect(isCreditAmount(0)).toBe(false)
    expect(isCreditAmount(0.01)).toBe(false)
    expect(isCreditAmount(Number.POSITIVE_INFINITY)).toBe(false)
  })
})

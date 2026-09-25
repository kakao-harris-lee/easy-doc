import { describe, expect, it } from 'vitest'

import {
  creditsForCharCount,
  creditsForReadingLevel,
  formatCredits,
  isCreditAmount,
} from './credits'

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

  it('rounds the extra-easy 1.2 multiplier up to the next tenth of a credit', () => {
    expect(creditsForReadingLevel(0.1, 'grade_5_6')).toBe(0.1)
    expect(creditsForReadingLevel(0.1, 'grade_3_4')).toBe(0.2)
    expect(creditsForReadingLevel(1.1, 'grade_3_4')).toBe(1.4)
  })

  it('accepts signed nonzero tenths for admin adjustments only', () => {
    expect(isCreditAmount(0.1)).toBe(true)
    expect(isCreditAmount(-1.1)).toBe(true)
    expect(isCreditAmount(0)).toBe(false)
    expect(isCreditAmount(0.01)).toBe(false)
    expect(isCreditAmount(Number.POSITIVE_INFINITY)).toBe(false)
  })
})

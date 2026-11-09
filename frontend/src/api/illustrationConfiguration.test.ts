import { describe, expect, it } from 'vitest'
import {
  illustrationConfigurationRevision,
  illustrationTextLength,
} from './illustrationConfiguration'

describe('illustration configuration revision contract', () => {
  it('hashes Korean text, UTF-16 lengths and literal delimiters without normalization', async () => {
    expect(
      await illustrationConfigurationRevision({
        scenes: ['신청'],
        preserved_facts: [],
        alt_text: '신청 그림',
      }),
    ).toBe('b589ba27789929126b57dc60573869b04b181fd8c36cde5a8f6e5cb88892345e')
    expect(
      await illustrationConfigurationRevision({
        scenes: ['😀|', '한글'],
        preserved_facts: ['1:2'],
        alt_text: '한글 😀;|',
      }),
    ).toBe('740605d45eb3e461643a7da08073236a9f4b25880230d0137f2dbf9d607161da')
  })

  it('counts Unicode code points without changing the UTF-16 canonical hash rule', () => {
    expect(illustrationTextLength('한글😀')).toBe(3)
    expect(illustrationTextLength('😀'.repeat(300))).toBe(300)
    expect(illustrationTextLength('😀'.repeat(301))).toBe(301)
  })
})

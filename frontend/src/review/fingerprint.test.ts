import { describe, expect, it } from 'vitest'

import { computeEasyTextFingerprint } from './fingerprint'

const HEX_64 = /^[0-9a-f]{64}$/

describe('computeEasyTextFingerprint', () => {
  it('64자 소문자 16진수를 낸다', async () => {
    const fingerprint = await computeEasyTextFingerprint('신청은 3월 2일부터 가능합니다.')
    expect(fingerprint).toMatch(HEX_64)
  })

  it('알려진 SHA-256 값과 같다 ("abc")', async () => {
    // https://csrc.nist.gov 예제 벡터.
    expect(await computeEasyTextFingerprint('abc')).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })

  it('빈 문자열도 계약 형식(64자)을 낸다', async () => {
    const fingerprint = await computeEasyTextFingerprint('')
    expect(fingerprint).toMatch(HEX_64)
    expect(fingerprint).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
  })

  it('같은 입력은 같은 지문을 낸다(결정적)', async () => {
    const text = '단위 텍스트'
    const first = await computeEasyTextFingerprint(text)
    const second = await computeEasyTextFingerprint(text)
    expect(first).toBe(second)
  })

  it('다른 입력은 다른 지문을 낸다', async () => {
    const first = await computeEasyTextFingerprint('첫 번째 문단')
    const second = await computeEasyTextFingerprint('두 번째 문단')
    expect(first).not.toBe(second)
  })
})

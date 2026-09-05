import { describe, expect, it } from 'vitest'

import type { OAuthProvider } from '../api/types'
import { SUPPORTED_PROVIDERS } from './socialLogin'

describe('SUPPORTED_PROVIDERS', () => {
  it('OAuthProvider 유니온의 모든 값을 포함한다', () => {
    // exhaustiveness 검사: `OAuthProvider`에 새 값(e.g. 'naver')이 추가되면 이 객체
    // 리터럴이 키 누락으로 `satisfies` 타입 검사에 걸려 `npm run check`가 실패한다 —
    // `SUPPORTED_PROVIDERS`만 고치고 이 목록은 그대로 두는 실수를 막는다. 반대로
    // `SUPPORTED_PROVIDERS`에만 값을 추가하면 아래 런타임 비교가 실패한다.
    const everyProvider = {
      google: true,
      kakao: true,
      naver: true,
    } satisfies Record<OAuthProvider, true>

    expect(new Set(SUPPORTED_PROVIDERS)).toEqual(new Set(Object.keys(everyProvider)))
    expect(SUPPORTED_PROVIDERS).toHaveLength(Object.keys(everyProvider).length)
  })
})

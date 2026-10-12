import { describe, expect, it } from 'vitest'

import { sanitizeReturnPath, safeReturnPath } from './paths'

describe('로그인 복귀 경로', () => {
  it('resume 식별자는 보존하고 레거시 인증값은 제거한다', () => {
    expect(
      safeReturnPath({
        pathname: '/billing/callback',
        search: '?resume=resume-1&authKey=secret&customerKey=customer&billingKey=billing',
        hash: '#result',
      }),
    ).toBe('/billing/callback?resume=resume-1#result')
  })

  it('외부 복귀 경로는 홈으로 되돌리고 내부 쿼리도 인증값을 제거한다', () => {
    expect(sanitizeReturnPath('https://attacker.example/billing/callback?resume=stolen')).toBe('/')
    expect(sanitizeReturnPath('/billing/callback?resume=resume-2&authKey=secret')).toBe(
      '/billing/callback?resume=resume-2',
    )
  })
})

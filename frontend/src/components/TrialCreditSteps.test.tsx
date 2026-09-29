import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { TrialCreditSteps } from './TrialCreditSteps'

describe('TrialCreditSteps', () => {
  it('무료 체험 시작 순서 영역과 네 단계를 보여준다', () => {
    render(<TrialCreditSteps />)

    const guide = screen.getByRole('region', { name: '무료 체험 시작 순서' })
    expect(guide).toHaveTextContent('가입')
    expect(guide).toHaveTextContent('로그인')
    expect(guide).toHaveTextContent('휴대폰 인증 요청')
    expect(guide).toHaveTextContent('체험 5크레딧 발급')
  })

  it('이메일 인증 안내를 제공자 이름 없이 구조로 설명한다', () => {
    render(<TrialCreditSteps />)

    // 계약(contracts/easy-doc-v1.yaml, 소셜 로그인 account_linking·providers.x-note 의
    // email_verified 주석)에 따라 제공자가 이메일을 확인해 주지 않는 소셜 가입은 가입 직후
    // 인증 코드가 발급되고 휴대폰 인증 앞에서 막힌다. 안내는 이메일 가입자만이 아니라 이
    // 경우를 포함해야 한다.
    expect(screen.getByText(/소셜 제공자가 이메일을 확인해 주지 않은 경우/)).toBeInTheDocument()
    expect(screen.getByText(/휴대폰 인증 전에/)).toBeInTheDocument()
    expect(screen.getByText(/인증 코드/)).toBeInTheDocument()
  })
})

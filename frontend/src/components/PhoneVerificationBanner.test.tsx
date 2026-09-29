import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { AuthContext } from '../auth/context'
import type { AuthContextValue } from '../auth/context'
import { ACCOUNT_SETTINGS_PATH, EMAIL_VERIFICATION_PATH } from '../routes/paths'
import { authContextValue, userResponse } from '../test/factories'
import { PhoneVerificationBanner } from './PhoneVerificationBanner'

function renderBanner(auth: Partial<AuthContextValue>) {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={authContextValue(auth)}>
        <PhoneVerificationBanner onNavigate={vi.fn()} />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

describe('PhoneVerificationBanner', () => {
  it('로그아웃 상태에서는 그리지 않는다', () => {
    const { container } = renderBanner({ user: null })

    expect(container).toBeEmptyDOMElement()
  })

  it('휴대폰 인증을 마친 사용자에게는 그리지 않는다', () => {
    const { container } = renderBanner({ user: userResponse({ phone_verified: true }) })

    expect(container).toBeEmptyDOMElement()
  })

  it('이메일 미인증 사용자는 이메일 인증 링크와 안내 문장을 본다', () => {
    renderBanner({ user: userResponse({ phone_verified: false, email_verified: false }) })

    const link = screen.getByRole('link', { name: '이메일 인증하기' })
    expect(link).toHaveAttribute('href', EMAIL_VERIFICATION_PATH)
    expect(screen.getByText('이메일 인증을 먼저 마쳐 주세요.')).not.toHaveClass('sr-only')
  })

  it('이메일 인증 사용자는 계정 설정으로 가는 휴대폰 인증 링크와 안내 문장을 본다', () => {
    renderBanner({ user: userResponse({ phone_verified: false, email_verified: true }) })

    const link = screen.getByRole('link', { name: '휴대폰 인증하기' })
    expect(link).toHaveAttribute('href', ACCOUNT_SETTINGS_PATH)
    expect(screen.getByText('인증을 마치면 샘플 변환용 5크레딧을 한 번 드립니다.')).toHaveClass(
      'sr-only',
      'sm:not-sr-only',
    )
  })

  it('제목이 영역의 이름이 되고 단계 목록은 없다', () => {
    renderBanner({ user: userResponse({ phone_verified: false }) })

    expect(
      screen.getByRole('region', { name: '휴대폰 인증하고 체험 5크레딧 받기' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    expect(screen.queryByText('휴대폰 인증 필요')).not.toBeInTheDocument()
  })
})

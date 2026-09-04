import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { confirmEmailVerification, requestEmailVerification } from '../api/auth'
import { ApiError } from '../api/client'
import { AuthContext } from '../auth/context'
import type { AuthContextValue } from '../auth/context'
import { authContextValue, userResponse } from '../test/factories'
import { EmailVerificationPage } from './EmailVerificationPage'

vi.mock('../api/auth', () => ({
  requestEmailVerification: vi.fn(),
  confirmEmailVerification: vi.fn(),
}))

/** 화면 한 벌. 홈은 이동 확인용 표식만 그린다. */
function page(auth: Partial<AuthContextValue> = {}) {
  return (
    <AuthContext.Provider value={authContextValue(auth)}>
      <MemoryRouter initialEntries={['/verify-email']}>
        <Routes>
          <Route path="/verify-email" element={<EmailVerificationPage />} />
          <Route path="/" element={<h1>홈 화면</h1>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>
  )
}

function renderPage(auth: Partial<AuthContextValue> = {}) {
  return render(page(auth))
}

beforeEach(() => {
  vi.mocked(confirmEmailVerification).mockReset()
  vi.mocked(requestEmailVerification).mockReset()
})

describe('이메일 인증 화면', () => {
  it('이미 인증된 사용자가 들어오면 곧장 홈으로 보낸다', () => {
    renderPage({ user: userResponse({ email_verified: true }) })

    expect(screen.getByRole('heading', { name: '홈 화면' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '이메일 인증' })).not.toBeInTheDocument()
  })

  it('코드를 확인하면 사용자 정보를 새로고침하고 홈으로 이동한다', async () => {
    const user = userEvent.setup()
    const refreshMe = vi.fn().mockResolvedValue(undefined)
    vi.mocked(confirmEmailVerification).mockResolvedValue(undefined)
    renderPage({ user: userResponse({ email_verified: false }), refreshMe })

    await user.type(screen.getByLabelText('인증 코드'), '123456')
    await user.click(screen.getByRole('button', { name: '확인' }))

    expect(vi.mocked(confirmEmailVerification)).toHaveBeenCalledWith('123456')
    expect(await screen.findByRole('heading', { name: '홈 화면' })).toBeInTheDocument()
    expect(refreshMe).toHaveBeenCalledTimes(1)
  })

  it('코드가 오답·만료면(400) 고정 문구를 화면에 남긴다', async () => {
    const user = userEvent.setup()
    vi.mocked(confirmEmailVerification).mockRejectedValue(
      new ApiError(400, '인증 코드가 올바르지 않거나 만료되었습니다'),
    )
    renderPage({ user: userResponse({ email_verified: false }) })

    await user.type(screen.getByLabelText('인증 코드'), '000000')
    await user.click(screen.getByRole('button', { name: '확인' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '인증 코드가 올바르지 않거나 만료되었습니다',
    )
    expect(screen.queryByRole('heading', { name: '홈 화면' })).not.toBeInTheDocument()
  })

  it('확인 중 이미 인증된 상태(409)이면 새로고침 후 홈으로 이동한다', async () => {
    const user = userEvent.setup()
    const refreshMe = vi.fn().mockResolvedValue(undefined)
    vi.mocked(confirmEmailVerification).mockRejectedValue(
      new ApiError(409, '이미 인증된 이메일입니다'),
    )
    renderPage({ user: userResponse({ email_verified: false }), refreshMe })

    await user.type(screen.getByLabelText('인증 코드'), '123456')
    await user.click(screen.getByRole('button', { name: '확인' }))

    expect(await screen.findByRole('heading', { name: '홈 화면' })).toBeInTheDocument()
    expect(refreshMe).toHaveBeenCalledTimes(1)
  })

  it('코드 다시 보내기가 성공하면 60초 대기로 바뀐다', async () => {
    const user = userEvent.setup()
    vi.mocked(requestEmailVerification).mockResolvedValue(undefined)
    renderPage({ user: userResponse({ email_verified: false }) })

    await user.click(screen.getByRole('button', { name: '코드 다시 보내기' }))

    expect(await screen.findByText('60초 후 다시 보낼 수 있어요.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '코드 다시 보내기' })).not.toBeInTheDocument()
  })

  it('다시 보내기가 쿨다운(429)이면 서버가 준 Retry-After만큼 대기로 바뀐다', async () => {
    const user = userEvent.setup()
    vi.mocked(requestEmailVerification).mockRejectedValue(
      new ApiError(429, '잠시 후 다시 시도해주세요', 45),
    )
    renderPage({ user: userResponse({ email_verified: false }) })

    await user.click(screen.getByRole('button', { name: '코드 다시 보내기' }))

    expect(await screen.findByText('45초 후 다시 보낼 수 있어요.')).toBeInTheDocument()
  })
})

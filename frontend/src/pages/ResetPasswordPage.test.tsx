import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { passwordResetRequest } from '../api/auth'
import { ApiError } from '../api/client'
import { AuthContext } from '../auth/context'
import type { AuthContextValue } from '../auth/context'
import { authContextValue } from '../test/factories'
import { ResetPasswordPage } from './ResetPasswordPage'

vi.mock('../api/auth', () => ({
  passwordResetRequest: vi.fn(),
}))

/** 화면 한 벌. 홈은 이동 확인용 표식만 그린다. */
function page(auth: Partial<AuthContextValue> = {}) {
  return (
    <AuthContext.Provider value={authContextValue({ status: 'anonymous', user: null, ...auth })}>
      <MemoryRouter initialEntries={['/reset-password']}>
        <Routes>
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/" element={<h1>홈 화면</h1>} />
          <Route path="/login" element={<h1>로그인 화면</h1>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>
  )
}

function renderPage(auth: Partial<AuthContextValue> = {}) {
  return render(page(auth))
}

beforeEach(() => {
  vi.mocked(passwordResetRequest).mockReset()
})

describe('비밀번호 재설정 화면', () => {
  it('이미 로그인한 사용자가 들어오면 곧장 홈으로 보낸다', () => {
    renderPage({ status: 'authenticated' })

    expect(screen.getByRole('heading', { name: '홈 화면' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '비밀번호 재설정' })).not.toBeInTheDocument()
  })

  it('이메일 형식이 잘못되면 요청을 보내지 않고 오류를 보여준다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('이메일'), 'not-an-email')
    await user.click(screen.getByRole('button', { name: '재설정 코드 받기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('이메일 형식이 올바르지 않습니다')
    expect(vi.mocked(passwordResetRequest)).not.toHaveBeenCalled()
  })

  it('요청이 성공하면 항상 같은 안내를 보여주고 확인 단계로 넘어간다', async () => {
    const user = userEvent.setup()
    vi.mocked(passwordResetRequest).mockResolvedValue(undefined)
    renderPage()

    await user.type(screen.getByLabelText('이메일'), 'user@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 코드 받기' }))

    expect(vi.mocked(passwordResetRequest)).toHaveBeenCalledWith('user@example.test')
    expect(await screen.findByRole('status')).toHaveTextContent(
      '코드를 보냈습니다. 메일함을 확인하세요.',
    )
    expect(screen.getByLabelText('인증 코드')).toBeInTheDocument()
    // 이메일은 다음 단계로 그대로 이어진다.
    expect(screen.getByLabelText('이메일')).toHaveValue('user@example.test')
  })

  it('요청 자체가 실패하면(네트워크 등) 오류 문구를 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(passwordResetRequest).mockRejectedValue(
      new ApiError(0, '서버에 연결하지 못했습니다.'),
    )
    renderPage()

    await user.type(screen.getByLabelText('이메일'), 'user@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 코드 받기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결하지 못했습니다.')
    expect(screen.queryByLabelText('인증 코드')).not.toBeInTheDocument()
  })

  it('확인 단계에서 정답 코드와 새 비밀번호를 보내면 로그인 상태로 만들고 홈으로 이동한다', async () => {
    const user = userEvent.setup()
    vi.mocked(passwordResetRequest).mockResolvedValue(undefined)
    const completePasswordReset = vi.fn().mockResolvedValue(undefined)
    renderPage({ completePasswordReset })

    await user.type(screen.getByLabelText('이메일'), 'user@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 코드 받기' }))
    await screen.findByLabelText('인증 코드')

    await user.type(screen.getByLabelText('인증 코드'), '123456')
    await user.type(screen.getByLabelText('새 비밀번호'), 'brand-new-password')
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'brand-new-password')
    await user.click(screen.getByRole('button', { name: '비밀번호 재설정' }))

    expect(completePasswordReset).toHaveBeenCalledWith(
      'user@example.test',
      '123456',
      'brand-new-password',
    )
    expect(await screen.findByRole('heading', { name: '홈 화면' })).toBeInTheDocument()
  })

  it('새 비밀번호와 확인이 다르면 요청을 보내지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(passwordResetRequest).mockResolvedValue(undefined)
    const completePasswordReset = vi.fn()
    renderPage({ completePasswordReset })

    await user.type(screen.getByLabelText('이메일'), 'user@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 코드 받기' }))
    await screen.findByLabelText('인증 코드')

    await user.type(screen.getByLabelText('인증 코드'), '123456')
    await user.type(screen.getByLabelText('새 비밀번호'), 'brand-new-password')
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'different-password')
    await user.click(screen.getByRole('button', { name: '비밀번호 재설정' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('비밀번호가 서로 다릅니다')
    expect(completePasswordReset).not.toHaveBeenCalled()
  })

  it('짧은 새 비밀번호는 서비스를 부르지 않고 안내한다', async () => {
    const user = userEvent.setup()
    vi.mocked(passwordResetRequest).mockResolvedValue(undefined)
    const completePasswordReset = vi.fn()
    renderPage({ completePasswordReset })

    await user.type(screen.getByLabelText('이메일'), 'user@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 코드 받기' }))
    await screen.findByLabelText('인증 코드')

    await user.type(screen.getByLabelText('인증 코드'), '123456')
    await user.type(screen.getByLabelText('새 비밀번호'), 'short')
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'short')
    await user.click(screen.getByRole('button', { name: '비밀번호 재설정' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('비밀번호는 8자 이상이어야 합니다')
    expect(completePasswordReset).not.toHaveBeenCalled()
  })

  it('서버가 401(코드 오답·만료 등)을 주면 고정 문구를 그대로 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(passwordResetRequest).mockResolvedValue(undefined)
    const completePasswordReset = vi
      .fn()
      .mockRejectedValue(new ApiError(401, '재설정 코드가 올바르지 않거나 만료되었습니다'))
    renderPage({ completePasswordReset })

    await user.type(screen.getByLabelText('이메일'), 'user@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 코드 받기' }))
    await screen.findByLabelText('인증 코드')

    await user.type(screen.getByLabelText('인증 코드'), '000000')
    await user.type(screen.getByLabelText('새 비밀번호'), 'brand-new-password')
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'brand-new-password')
    await user.click(screen.getByRole('button', { name: '비밀번호 재설정' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '재설정 코드가 올바르지 않거나 만료되었습니다',
    )
    expect(screen.queryByRole('heading', { name: '홈 화면' })).not.toBeInTheDocument()
  })

  it('코드를 다 입력하기 전에는 확인 버튼이 비활성화된다', async () => {
    const user = userEvent.setup()
    vi.mocked(passwordResetRequest).mockResolvedValue(undefined)
    renderPage()

    await user.type(screen.getByLabelText('이메일'), 'user@example.test')
    await user.click(screen.getByRole('button', { name: '재설정 코드 받기' }))
    await screen.findByLabelText('인증 코드')

    expect(screen.getByRole('button', { name: '비밀번호 재설정' })).toBeDisabled()
    await user.type(screen.getByLabelText('인증 코드'), '123')
    expect(screen.getByRole('button', { name: '비밀번호 재설정' })).toBeDisabled()
  })
})

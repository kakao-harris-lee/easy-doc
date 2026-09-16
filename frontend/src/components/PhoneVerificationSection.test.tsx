import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { confirmPhoneVerification, requestPhoneVerification } from '../api/auth'
import { ApiError } from '../api/client'
import { AuthContext } from '../auth/context'
import type { AuthContextValue } from '../auth/context'
import { authContextValue, userResponse } from '../test/factories'
import { PhoneVerificationSection } from './PhoneVerificationSection'

vi.mock('../api/auth', () => ({
  requestPhoneVerification: vi.fn(),
  confirmPhoneVerification: vi.fn(),
}))

function renderSection(auth: Partial<AuthContextValue> = {}) {
  return render(
    <AuthContext.Provider value={authContextValue(auth)}>
      <PhoneVerificationSection />
    </AuthContext.Provider>,
  )
}

beforeEach(() => {
  vi.mocked(requestPhoneVerification).mockReset()
  vi.mocked(confirmPhoneVerification).mockReset()
})

describe('PhoneVerificationSection', () => {
  it('인증 완료 사용자는 완료 문구만 보여준다', () => {
    renderSection({ user: userResponse({ phone_verified: true }) })

    expect(
      screen.getByText('인증이 완료되었습니다. 결제를 이용할 수 있습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('휴대폰 번호')).not.toBeInTheDocument()
  })

  it('이메일 미인증 사용자는 안내 문구만 보여주고 입력란을 그리지 않는다', () => {
    renderSection({ user: userResponse({ phone_verified: false, email_verified: false }) })

    expect(
      screen.getByText('이메일 인증을 완료한 뒤 휴대폰 번호를 인증할 수 있습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('휴대폰 번호')).not.toBeInTheDocument()
  })

  it('인증번호 받기가 성공하면 코드 입력란이 나타나고 60초 쿨다운이 1초마다 줄어든다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      vi.mocked(requestPhoneVerification).mockResolvedValue(undefined)
      renderSection({ user: userResponse({ phone_verified: false }) })

      fireEvent.change(screen.getByLabelText('휴대폰 번호'), {
        target: { value: '010-1234-5678' },
      })
      fireEvent.click(screen.getByRole('button', { name: '인증번호 받기' }))

      await vi.waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('인증번호를 보냈습니다'),
      )
      expect(screen.getByLabelText('인증번호')).toBeInTheDocument()
      const resendButton = screen.getByRole('button', { name: '인증번호 다시 받기' })
      expect(resendButton).toBeDisabled()
      expect(screen.getByText('60초 후 다시 보낼 수 있어요.')).toBeInTheDocument()

      await act(async () => {
        vi.advanceTimersByTime(1000)
      })

      expect(screen.getByText('59초 후 다시 보낼 수 있어요.')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('429면 오류 문구 없이 서버가 준 대기 시간만큼 쿨다운을 보여준다', async () => {
    vi.mocked(requestPhoneVerification).mockRejectedValue(
      new ApiError(429, '잠시 후 다시 시도해주세요', 42),
    )
    const user = userEvent.setup()
    renderSection({ user: userResponse({ phone_verified: false }) })

    await user.type(screen.getByLabelText('휴대폰 번호'), '010-1234-5678')
    await user.click(screen.getByRole('button', { name: '인증번호 받기' }))

    expect(await screen.findByText('42초 후 다시 보낼 수 있어요.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('확인 성공(granted_credits > 0)은 지급 문구를 보여주고 refreshMe를 부른다', async () => {
    vi.mocked(requestPhoneVerification).mockResolvedValue(undefined)
    vi.mocked(confirmPhoneVerification).mockResolvedValue({
      phone_verified: true,
      granted_credits: 5,
    })
    const refreshMe = vi.fn().mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderSection({ user: userResponse({ phone_verified: false }), refreshMe })

    await user.type(screen.getByLabelText('휴대폰 번호'), '010-1234-5678')
    await user.click(screen.getByRole('button', { name: '인증번호 받기' }))
    await user.type(await screen.findByLabelText('인증번호'), '123456')
    await user.click(screen.getByRole('button', { name: '인증 완료' }))

    expect(
      await screen.findByText('휴대폰 인증이 완료되어 체험용 5크레딧을 드렸습니다.'),
    ).toBeInTheDocument()
    expect(refreshMe).toHaveBeenCalledOnce()
  })

  it('확인 성공(granted_credits = 0)은 이미 지급됐다는 문구를 보여준다', async () => {
    vi.mocked(requestPhoneVerification).mockResolvedValue(undefined)
    vi.mocked(confirmPhoneVerification).mockResolvedValue({
      phone_verified: true,
      granted_credits: 0,
    })
    const user = userEvent.setup()
    renderSection({ user: userResponse({ phone_verified: false }) })

    await user.type(screen.getByLabelText('휴대폰 번호'), '010-1234-5678')
    await user.click(screen.getByRole('button', { name: '인증번호 받기' }))
    await user.type(await screen.findByLabelText('인증번호'), '123456')
    await user.click(screen.getByRole('button', { name: '인증 완료' }))

    expect(
      await screen.findByText(
        '휴대폰 인증이 완료되었습니다. 이 번호의 체험 크레딧은 이미 지급된 적이 있습니다.',
      ),
    ).toBeInTheDocument()
  })

  it('확인 실패(400)는 서버 문구를 alert로 보여준다', async () => {
    vi.mocked(requestPhoneVerification).mockResolvedValue(undefined)
    vi.mocked(confirmPhoneVerification).mockRejectedValue(
      new ApiError(400, '인증번호가 올바르지 않거나 만료되었습니다'),
    )
    const user = userEvent.setup()
    renderSection({ user: userResponse({ phone_verified: false }) })

    await user.type(screen.getByLabelText('휴대폰 번호'), '010-1234-5678')
    await user.click(screen.getByRole('button', { name: '인증번호 받기' }))
    await user.type(await screen.findByLabelText('인증번호'), '123456')
    await user.click(screen.getByRole('button', { name: '인증 완료' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '인증번호가 올바르지 않거나 만료되었습니다',
    )
  })
})

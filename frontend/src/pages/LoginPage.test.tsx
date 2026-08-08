import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe, login } from '../api/auth'
import { ApiError } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import { AppLayout } from '../components/AppLayout'
import { workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import { AppRoutes } from '../routes/AppRoutes'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  signup: vi.fn(),
  fetchMe: vi.fn(),
}))

function renderAt(path: string) {
  return render(
    <AuthProvider>
      {/* 머리말의 작업 공간 메뉴가 이 컨텍스트를 읽는다 — 여기서는 관심사가 아니라
          고정된 값을 꽂는다(요청은 WorkspaceProvider 테스트가 본다). */}
      <WorkspaceContext.Provider value={workspaceContext()}>
        <MemoryRouter initialEntries={[path]}>
          <AppLayout>
            <AppRoutes />
          </AppLayout>
        </MemoryRouter>
      </WorkspaceContext.Provider>
    </AuthProvider>,
  )
}

beforeEach(() => {
  window.localStorage.clear()
  vi.mocked(login).mockReset()
  vi.mocked(fetchMe).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('로그인 화면', () => {
  it('입력이 비어 있으면 서버를 부르지 않고 오류를 알린다', async () => {
    const user = userEvent.setup()
    renderAt('/login')

    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('이메일을 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByText('비밀번호를 입력해 주세요')).toBeInTheDocument()
    expect(vi.mocked(login)).not.toHaveBeenCalled()
  })

  it('비밀번호가 짧으면 길이 기준을 알린다', async () => {
    const user = userEvent.setup()
    renderAt('/login')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'short')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('비밀번호는 8자 이상이어야 합니다')).toBeInTheDocument()
    expect(vi.mocked(login)).not.toHaveBeenCalled()
  })

  it('로그인에 성공하면 토큰을 저장하고 홈으로 이동한다', async () => {
    const user = userEvent.setup()
    vi.mocked(login).mockResolvedValue({
      access_token: 'token-abc',
      token_type: 'bearer',
      expires_in: 3600,
    })
    vi.mocked(fetchMe).mockResolvedValue({ id: 'u1', email: 'user@example.com' })
    renderAt('/login')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password123')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
    expect(vi.mocked(login)).toHaveBeenCalledWith({
      email: 'user@example.com',
      password: 'password123',
    })
    expect(window.localStorage.getItem('easydoc.access_token')).toBe('token-abc')
  })

  it('서버가 거절하면 그 사유를 화면에 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(login).mockRejectedValue(
      new ApiError(401, '이메일 또는 비밀번호가 올바르지 않습니다'),
    )
    renderAt('/login')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password123')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('이메일 또는 비밀번호가 올바르지 않습니다')
    // 실패했으므로 다시 시도할 수 있어야 한다.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled()
    })
  })
})

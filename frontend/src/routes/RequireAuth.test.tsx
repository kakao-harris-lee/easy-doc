import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe, login } from '../api/auth'
import { listDocuments } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import { AppLayout } from '../components/AppLayout'
import { workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import { AppRoutes } from './AppRoutes'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  signup: vi.fn(),
  fetchMe: vi.fn(),
}))

// 홈은 업로드 화면이고, 그 화면은 「다음 할 일」 근거로 문서를 조회한다(§7). 이 테스트의
// 관심사는 아니지만 모킹은 반드시 필요하다 — 두지 않으면 진짜 요청이 나가고, 서버가 살아
// 있으면 이 가짜 토큰에 401을 준다. 그 401은 API 클라이언트가 세션 만료로 처리해 토큰을
// 지우므로, 로그아웃을 누르기도 전에 화면이 로그인으로 넘어간다.
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  listDocuments: vi.fn(),
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
  vi.mocked(listDocuments).mockResolvedValue({ items: [], limit: 20, offset: 0, has_more: false })
})

describe('인증 가드', () => {
  it('토큰이 없으면 보호 화면 대신 로그인 화면을 보여준다', async () => {
    renderAt('/')

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '문서 변환하기' })).not.toBeInTheDocument()
    expect(vi.mocked(fetchMe)).not.toHaveBeenCalled()
  })

  it('토큰이 만료됐으면 확인 후 로그인 화면으로 보낸다', async () => {
    window.localStorage.setItem('easydoc.access_token', 'expired-token')
    vi.mocked(fetchMe).mockRejectedValue(new Error('unauthorized'))

    renderAt('/')

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
  })

  it('토큰이 유효하면 보호 화면을 보여준다', async () => {
    window.localStorage.setItem('easydoc.access_token', 'valid-token')
    vi.mocked(fetchMe).mockResolvedValue({
      id: 'u1',
      email: 'user@example.com',
      email_verified: true,
    })

    renderAt('/')

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
  })

  it('로그아웃하면 토큰을 지우고 로그인 화면으로 돌아간다', async () => {
    const user = userEvent.setup()
    window.localStorage.setItem('easydoc.access_token', 'valid-token')
    vi.mocked(fetchMe).mockResolvedValue({
      id: 'u1',
      email: 'user@example.com',
      email_verified: true,
    })
    renderAt('/')

    // 로그아웃은 계정 메뉴 안에 있다 — 머리말은 이메일도 로그아웃도 펼쳐 두지 않는다
    // (DESIGN.md §5.1, components/AppLayout.tsx).
    await user.click(await screen.findByRole('button', { name: '계정 메뉴' }))
    await user.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(window.localStorage.getItem('easydoc.access_token')).toBeNull()
  })
})

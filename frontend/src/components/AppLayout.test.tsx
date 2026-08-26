import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AuthContext } from '../auth/context'
import type { AuthContextValue } from '../auth/context'
import { setUnsavedChanges } from '../review/unsavedChanges'
import { workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import { AppLayout } from './AppLayout'

const EMAIL = 'gongmuwon@example.test'

/** 지금 주소를 화면에 적는다 — 가드가 이동을 막았는지 렌더 결과로 확인한다. */
function LocationProbe() {
  return <p data-testid="location">{useLocation().pathname}</p>
}

function authValue(overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    status: 'authenticated',
    user: { id: 'u1', email: EMAIL },
    signIn: () => Promise.resolve(),
    signUp: () => Promise.resolve(),
    signOut: () => undefined,
    ...overrides,
  }
}

/**
 * 작업 공간 목록은 비워 둔다 — `WorkspaceMenu`는 그때 아무것도 그리지 않아
 * 머리말에 두 벌 그려지는 메뉴가 이 테스트의 로케이터를 흐리지 않는다.
 */
function renderLayout(auth: Partial<AuthContextValue> = {}, initialPath = '/') {
  return render(
    <AuthContext.Provider value={authValue(auth)}>
      <WorkspaceContext.Provider value={workspaceContext({ workspaces: [], currentId: null })}>
        <MemoryRouter initialEntries={[initialPath]}>
          <AppLayout>
            <LocationProbe />
          </AppLayout>
        </MemoryRouter>
      </WorkspaceContext.Provider>
    </AuthContext.Provider>,
  )
}

afterEach(() => {
  setUnsavedChanges(false)
  vi.restoreAllMocks()
})

describe('계정 메뉴', () => {
  it('이메일은 메뉴를 열기 전에는 보이지 않는다', async () => {
    const user = userEvent.setup()
    renderLayout()

    expect(screen.queryByText(EMAIL)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '계정 메뉴' }))

    expect(screen.getByText(EMAIL)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
  })

  it('열면 첫 행동으로 초점이 가고 Esc로 닫으면 트리거로 돌아온다', async () => {
    const user = userEvent.setup()
    renderLayout()
    const trigger = screen.getByRole('button', { name: '계정 메뉴' })

    await user.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: '로그아웃' })).toHaveFocus()

    await user.keyboard('{Escape}')

    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText(EMAIL)).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })
})

describe('저장하지 않은 수정 가드', () => {
  it('로고를 눌러도 확인을 거절하면 화면을 떠나지 않는다', async () => {
    const user = userEvent.setup()
    setUnsavedChanges(true)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderLayout({}, '/history')

    await user.click(screen.getByRole('link', { name: 'Easy-Read AI 홈' }))

    expect(confirm).toHaveBeenCalled()
    expect(screen.getByTestId('location')).toHaveTextContent('/history')
  })

  it('주요 메뉴 이동도 확인을 거절하면 막힌다', async () => {
    const user = userEvent.setup()
    setUnsavedChanges(true)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderLayout()

    await user.click(screen.getByRole('link', { name: '변환 기록' }))

    expect(confirm).toHaveBeenCalled()
    expect(screen.getByTestId('location')).toHaveTextContent('/')
  })

  it('로그아웃은 확인을 거절하면 실행되지 않고, 수락하면 실행된다', async () => {
    const user = userEvent.setup()
    setUnsavedChanges(true)
    const signOut = vi.fn()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderLayout({ signOut })

    await user.click(screen.getByRole('button', { name: '계정 메뉴' }))
    await user.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(confirm).toHaveBeenCalled()
    expect(signOut).not.toHaveBeenCalled()

    confirm.mockReturnValue(true)
    await user.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(signOut).toHaveBeenCalledTimes(1)
  })
})

describe('머리말 구성', () => {
  it('익명 상태에서는 이동 메뉴와 계정 메뉴를 그리지 않는다', () => {
    renderLayout({ status: 'anonymous', user: null })

    expect(screen.queryByRole('navigation', { name: '주요 메뉴' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '계정 메뉴' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Easy-Read AI 홈' })).toBeInTheDocument()
  })

  it('작업 공간이 계정 메뉴보다 앞에 온다', () => {
    render(
      <AuthContext.Provider value={authValue()}>
        <WorkspaceContext.Provider value={workspaceContext()}>
          <MemoryRouter>
            <AppLayout>
              <LocationProbe />
            </AppLayout>
          </MemoryRouter>
        </WorkspaceContext.Provider>
      </AuthContext.Provider>,
    )

    // 데스크톱 줄의 작업 공간 메뉴가 계정 메뉴 트리거보다 DOM 에서 먼저 나온다(§5.1).
    const workspace = document.querySelector('.workspace-menu')
    const account = screen.getByRole('button', { name: '계정 메뉴' })
    expect(workspace).not.toBeNull()
    expect(workspace?.compareDocumentPosition(account)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })
})

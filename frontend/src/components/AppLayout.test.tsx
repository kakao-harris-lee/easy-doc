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
    user: { id: 'u1', email: EMAIL, email_verified: true },
    signIn: () => Promise.resolve(),
    signUp: () => Promise.resolve(),
    signInWithGoogle: () => Promise.resolve(),
    signOut: () => undefined,
    refreshMe: () => Promise.resolve(),
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

  /*
    이 메뉴는 disclosure다. `aria-haspopup`(="menu"와 동의어)을 붙이면 낭독기에 메뉴
    역할을 예고하는데, 열리는 패널에는 `role="menu"`도 `menuitem`도 없다 — 없는 역할을
    약속하는 셈이다. "메뉴니까 haspopup을 붙이자"는 되돌림을 여기서 막는다.
  */
  it('트리거는 실재하지 않는 메뉴 역할을 약속하지 않는다 (aria-haspopup 없음)', async () => {
    const user = userEvent.setup()
    renderLayout()
    const trigger = screen.getByRole('button', { name: '계정 메뉴' })

    expect(trigger).not.toHaveAttribute('aria-haspopup')

    await user.click(trigger)

    expect(trigger).not.toHaveAttribute('aria-haspopup')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(screen.queryAllByRole('menuitem')).toHaveLength(0)
  })

  it('열린 트리거의 aria-controls는 실재하는 패널을 가리킨다', async () => {
    const user = userEvent.setup()
    renderLayout()
    const trigger = screen.getByRole('button', { name: '계정 메뉴' })

    await user.click(trigger)

    const panelId = trigger.getAttribute('aria-controls')
    expect(panelId).toBeTruthy()
    const panel = document.getElementById(panelId as string)
    expect(panel).not.toBeNull()
    // 가리키는 것이 실제로 그 패널인지까지 본다 — id만 존재하면 통과하는 검사는 약하다.
    expect(panel).toContainElement(screen.getByRole('button', { name: '로그아웃' }))
  })
})

describe('모바일 메뉴 ARIA', () => {
  it('햄버거의 aria-controls가 펼쳐진 nav를 가리킨다', async () => {
    const user = userEvent.setup()
    renderLayout()
    const toggle = screen.getByRole('button', { name: '메뉴 열기' })

    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await user.click(toggle)

    const opened = screen.getByRole('button', { name: '메뉴 닫기' })
    expect(opened).toHaveAttribute('aria-expanded', 'true')
    const navId = opened.getAttribute('aria-controls')
    expect(navId).toBeTruthy()
    expect(screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })).toHaveAttribute(
      'id',
      navId as string,
    )
  })

  // 햄버거도 disclosure다 — 계정 메뉴와 같은 이유로 메뉴 역할을 예고하지 않는다.
  it('햄버거도 aria-haspopup을 붙이지 않는다', () => {
    renderLayout()

    expect(screen.getByRole('button', { name: '메뉴 열기' })).not.toHaveAttribute('aria-haspopup')
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

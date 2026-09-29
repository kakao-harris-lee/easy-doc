import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listActiveAnnouncements } from '../api/announcements'
import { AuthContext } from '../auth/context'
import type { AuthContextValue } from '../auth/context'
import { setUnsavedChanges } from '../review/unsavedChanges'
import { ACCOUNT_SETTINGS_PATH, EMAIL_VERIFICATION_PATH } from '../routes/paths'
import { userResponse, workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import { AppLayout } from './AppLayout'
import { ThemeProvider } from '../theme/ThemeProvider'

const EMAIL = 'gongmuwon@example.test'

// 인증된 사용자로 그릴 때마다 AppLayout이 활성 공지를 조회한다 — 이 파일의 관심사가
// 아니지만 모킹하지 않으면 진짜 요청이 나간다(test/setup.ts).
vi.mock('../api/announcements', () => ({
  listActiveAnnouncements: vi.fn(),
}))

/** 지금 주소를 화면에 적는다 — 가드가 이동을 막았는지 렌더 결과로 확인한다. */
function LocationProbe() {
  return <p data-testid="location">{useLocation().pathname}</p>
}

function authValue(overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    status: 'authenticated',
    user: {
      id: 'u1',
      email: EMAIL,
      email_verified: true,
      phone_verified: true,
      has_password: true,
      identities: [],
      is_admin: false,
    },
    signIn: () => Promise.resolve(),
    signUp: () => Promise.resolve(),
    signInWithSocialProvider: () =>
      Promise.resolve({
        id: 'u1',
        email: EMAIL,
        email_verified: true,
        phone_verified: true,
        has_password: true,
        identities: [],
        is_admin: false,
      }),
    completePasswordReset: () => Promise.resolve(),
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
    <ThemeProvider>
      <AuthContext.Provider value={authValue(auth)}>
        <WorkspaceContext.Provider value={workspaceContext({ workspaces: [], currentId: null })}>
          <MemoryRouter initialEntries={[initialPath]}>
            <AppLayout>
              <LocationProbe />
            </AppLayout>
          </MemoryRouter>
        </WorkspaceContext.Provider>
      </AuthContext.Provider>
    </ThemeProvider>,
  )
}

function renderWithWorkspaces() {
  return render(
    <ThemeProvider>
      <AuthContext.Provider value={authValue()}>
        <WorkspaceContext.Provider value={workspaceContext()}>
          <MemoryRouter>
            <AppLayout>
              <LocationProbe />
            </AppLayout>
          </MemoryRouter>
        </WorkspaceContext.Provider>
      </AuthContext.Provider>
    </ThemeProvider>,
  )
}

beforeEach(() => {
  window.sessionStorage.clear()
  vi.mocked(listActiveAnnouncements).mockReset().mockResolvedValue({ items: [] })
})

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

describe('계정 메뉴 — 관리 링크 (어드민 최소, 계약 2.25.0)', () => {
  it('관리자가 아니면 「관리」 링크를 보여주지 않는다', async () => {
    const user = userEvent.setup()
    renderLayout({
      user: {
        id: 'u1',
        email: EMAIL,
        email_verified: true,
        phone_verified: true,
        has_password: true,
        identities: [],
        is_admin: false,
      },
    })

    await user.click(screen.getByRole('button', { name: '계정 메뉴' }))

    expect(screen.queryByRole('link', { name: '관리' })).not.toBeInTheDocument()
  })

  it('관리자면 「관리」 링크가 /admin으로 간다', async () => {
    const user = userEvent.setup()
    renderLayout({
      user: {
        id: 'u1',
        email: EMAIL,
        email_verified: true,
        phone_verified: true,
        has_password: true,
        identities: [],
        is_admin: true,
      },
    })

    await user.click(screen.getByRole('button', { name: '계정 메뉴' }))

    const link = screen.getByRole('link', { name: '관리' })
    expect(link).toHaveAttribute('href', '/admin')
  })

  it('저장하지 않은 수정이 있으면 다른 이동 링크와 같이 확인을 거쳐야 한다', async () => {
    const user = userEvent.setup()
    setUnsavedChanges(true)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderLayout({
      user: {
        id: 'u1',
        email: EMAIL,
        email_verified: true,
        phone_verified: true,
        has_password: true,
        identities: [],
        is_admin: true,
      },
    })

    await user.click(screen.getByRole('button', { name: '계정 메뉴' }))
    await user.click(screen.getByRole('link', { name: '관리' }))

    expect(confirm).toHaveBeenCalled()
    expect(screen.getByTestId('location')).toHaveTextContent('/')
    // 거절했으면 메뉴도 열린 채 그대로다.
    expect(screen.getByRole('link', { name: '관리' })).toBeInTheDocument()

    confirm.mockReturnValue(true)
    await user.click(screen.getByRole('link', { name: '관리' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/admin')
    // 확인을 통과하면 메뉴를 닫는다 — 그대로 두면 이 컴포넌트는 라우트가 바뀐 뒤에도
    // AppLayout과 함께 살아남아 열린 채로 남는다.
    expect(screen.queryByRole('link', { name: '관리' })).not.toBeInTheDocument()
  })
})

describe('모바일 메뉴 — 관리 링크 (어드민 최소, 계약 2.25.0)', () => {
  it('관리자가 아니면 모바일 메뉴에도 「관리」 링크가 없다', async () => {
    const user = userEvent.setup()
    renderLayout({
      user: {
        id: 'u1',
        email: EMAIL,
        email_verified: true,
        phone_verified: true,
        has_password: true,
        identities: [],
        is_admin: false,
      },
    })

    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))

    expect(screen.queryByRole('link', { name: '관리' })).not.toBeInTheDocument()
  })

  it('관리자면 모바일 메뉴에 「관리」 링크가 /admin으로 간다', async () => {
    const user = userEvent.setup()
    renderLayout({
      user: {
        id: 'u1',
        email: EMAIL,
        email_verified: true,
        phone_verified: true,
        has_password: true,
        identities: [],
        is_admin: true,
      },
    })

    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))

    const link = screen.getByRole('link', { name: '관리' })
    expect(link).toHaveAttribute('href', '/admin')
  })
})

describe('모바일 메뉴 ARIA', () => {
  it.each([
    ['새 변환', '/'],
    ['변환 기록', '/history'],
    ['사용량', '/usage'],
    ['이용 가이드', '/guide'],
    ['계정 설정', '/account'],
  ])('%s 선택 후 메뉴를 닫는다 (현재 페이지 포함)', async (name, path) => {
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))
    const menu = screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })

    await user.click(within(menu).getByRole('link', { name }))

    expect(screen.getByTestId('location').textContent).toBe(path)
    expect(menu).not.toBeInTheDocument()
    const toggle = screen.getByRole('button', { name: '메뉴 열기' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).not.toHaveAttribute('aria-controls')
  })

  it('이동을 취소하면 메뉴를 유지하고 승인하면 닫는다', async () => {
    const user = userEvent.setup()
    setUnsavedChanges(true)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderLayout()
    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))
    const menu = screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })

    await user.click(within(menu).getByRole('link', { name: '변환 기록' }))

    expect(screen.getByTestId('location').textContent).toBe('/')
    expect(menu).toBeInTheDocument()

    confirm.mockReturnValue(true)
    await user.click(within(menu).getByRole('link', { name: '변환 기록' }))

    expect(screen.getByTestId('location').textContent).toBe('/history')
    expect(menu).not.toBeInTheDocument()
  })

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

    await user.click(screen.getByRole('link', { name: 'EASY-DOC AI 홈' }))

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
  it('익명 상태에서는 업무 메뉴와 계정 메뉴를 그리지 않는다', () => {
    renderLayout({ status: 'anonymous', user: null })

    expect(screen.queryByRole('navigation', { name: '주요 메뉴' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '계정 메뉴' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'EASY-DOC AI 홈' })).toBeInTheDocument()
  })

  it('익명 상태에서는 이용 가이드·로그인 링크와 테마 스위치만 보여 주고 가입 버튼은 없다', () => {
    renderLayout({ status: 'anonymous', user: null })

    const start = screen.getByRole('navigation', { name: '시작 메뉴' })
    expect(within(start).getByRole('link', { name: '이용 가이드' })).toHaveAttribute(
      'href',
      '/guide',
    )
    expect(within(start).getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login')
    expect(screen.queryByRole('link', { name: '가입하기' })).not.toBeInTheDocument()
    expect(screen.getByRole('switch', { name: '다크 모드' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it.each(['/login', '/signup'])('%s 화면에서는 앱 바의 로그인 링크를 감춘다', (path) => {
    renderLayout({ status: 'anonymous', user: null }, path)

    expect(screen.queryByRole('link', { name: '로그인' })).not.toBeInTheDocument()
    const start = screen.getByRole('navigation', { name: '시작 메뉴' })
    expect(within(start).getByRole('link', { name: '이용 가이드' })).toBeInTheDocument()
  })

  it('익명 상태에도 푸터가 그려진다 (전자상거래법의 사업자 정보 초기 화면 표시 의무)', () => {
    renderLayout({ status: 'anonymous', user: null })

    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '개인정보처리방침' })).toBeInTheDocument()
  })

  it('작업 공간이 계정 메뉴보다 앞에 온다', () => {
    render(
      <ThemeProvider>
        <AuthContext.Provider value={authValue()}>
          <WorkspaceContext.Provider value={workspaceContext()}>
            <MemoryRouter>
              <AppLayout>
                <LocationProbe />
              </AppLayout>
            </MemoryRouter>
          </WorkspaceContext.Provider>
        </AuthContext.Provider>
      </ThemeProvider>,
    )

    // 데스크톱 줄의 작업 공간 메뉴가 계정 메뉴 트리거보다 DOM 에서 먼저 나온다(§5.1).
    const workspace = document.querySelector('.workspace-menu')
    const account = screen.getByRole('button', { name: '계정 메뉴' })
    expect(workspace).not.toBeNull()
    expect(workspace?.compareDocumentPosition(account)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  it('시트를 열면 앱 바 칩은 빠지고 시트 안의 행이 대신한다', async () => {
    const user = userEvent.setup()
    const { container } = renderWithWorkspaces()

    expect(container.querySelectorAll('.workspace-menu')).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))

    const sheet = screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })
    expect(sheet.querySelectorAll('.workspace-menu')).toHaveLength(1)
    expect(container.querySelectorAll('.workspace-menu')).toHaveLength(2)
  })

  it('로그인 후 앱 바에는 테마 스위치가 데스크톱 자리 하나뿐이고 시트가 열리면 시트에도 하나 있다', async () => {
    const user = userEvent.setup()
    renderLayout()

    expect(screen.getAllByRole('switch', { name: '다크 모드' })).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))

    const sheet = screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })
    expect(within(sheet).getByRole('switch', { name: '다크 모드' })).toBeInTheDocument()
    expect(screen.queryByText('테마')).not.toBeInTheDocument()
  })
})

describe('모바일 메뉴 시트', () => {
  it('앱 바 아래를 덮는 전체 화면 시트이고 본문 스크롤을 잠근다', async () => {
    const user = userEvent.setup()
    renderLayout()

    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))

    const sheet = screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })
    expect(sheet).toHaveClass('fixed', 'inset-x-0', 'bottom-0', 'overflow-y-auto', 'lg:hidden')
    expect(document.body.style.overflow).toBe('hidden')

    await user.click(screen.getByRole('button', { name: '메뉴 닫기' }))

    expect(document.body.style.overflow).not.toBe('hidden')
  })

  it('Esc로 닫으면 메뉴 버튼으로 초점이 돌아온다', async () => {
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('navigation', { name: '주요 메뉴 (모바일)' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toHaveFocus()
  })

  it('계정 영역에는 이메일·계정 설정·로그아웃만 있고 소셜 연결과 비밀번호 만들기는 없다', async () => {
    const user = userEvent.setup()
    renderLayout({
      user: {
        id: 'u1',
        email: EMAIL,
        email_verified: true,
        phone_verified: true,
        has_password: false,
        identities: [],
        is_admin: false,
      },
    })

    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))

    const sheet = screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })
    expect(within(sheet).getByText(EMAIL)).toBeInTheDocument()
    expect(within(sheet).getByRole('link', { name: '계정 설정' })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    expect(within(sheet).queryByRole('button', { name: /연결/ })).not.toBeInTheDocument()
    expect(within(sheet).queryByRole('button', { name: '비밀번호 만들기' })).not.toBeInTheDocument()
  })
})

describe('모바일 메뉴 시트 — 접근성과 뷰포트', () => {
  it('열면 초점이 시트 안으로 가고 뒤의 본문·푸터는 inert가 된다', async () => {
    const user = userEvent.setup()
    renderLayout()
    const main = screen.getByRole('main')
    expect(main.closest('[inert]')).toBeNull()

    await user.click(screen.getByRole('button', { name: '메뉴 열기' }))

    const sheet = screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })
    expect(sheet.contains(document.activeElement)).toBe(true)
    expect(main.closest('[inert]')).not.toBeNull()
    expect(screen.getByRole('contentinfo').closest('[inert]')).not.toBeNull()
    expect(screen.getByRole('button', { name: '메뉴 닫기' }).closest('[inert]')).toBeNull()

    await user.keyboard('{Escape}')

    expect(main.closest('[inert]')).toBeNull()
  })

  it('lg 이상으로 넓어지면 시트를 닫고 스크롤 잠금을 푼다', async () => {
    const user = userEvent.setup()
    let listener: (() => void) | null = null
    const media = {
      matches: false,
      addEventListener: (_type: string, next: () => void) => {
        listener = next
      },
      removeEventListener: () => {
        listener = null
      },
    }
    const original = window.matchMedia
    window.matchMedia = vi.fn(() => media) as unknown as typeof window.matchMedia
    try {
      renderLayout()
      await user.click(screen.getByRole('button', { name: '메뉴 열기' }))
      expect(document.body.style.overflow).toBe('hidden')

      media.matches = true
      await act(async () => listener?.())

      expect(
        screen.queryByRole('navigation', { name: '주요 메뉴 (모바일)' }),
      ).not.toBeInTheDocument()
      expect(document.body.style.overflow).not.toBe('hidden')
    } finally {
      window.matchMedia = original
    }
  })
})

describe('머리말 로고', () => {
  it('로고 링크는 좁은 화면에서도 44px 너비를 지킨다', () => {
    renderLayout()

    expect(screen.getByRole('link', { name: 'EASY-DOC AI 홈' })).toHaveClass('min-w-11')
  })
})

describe('계정 메뉴 — 구성', () => {
  it('소셜 연결과 비밀번호 만들기는 그리지 않는다', async () => {
    const user = userEvent.setup()
    renderLayout({
      user: {
        id: 'u1',
        email: EMAIL,
        email_verified: true,
        phone_verified: true,
        has_password: false,
        identities: [],
        is_admin: false,
      },
    })

    await user.click(screen.getByRole('button', { name: '계정 메뉴' }))

    expect(screen.getByRole('link', { name: '계정 설정' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /연결/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '비밀번호 만들기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '이메일 인증' })).not.toBeInTheDocument()
  })
})

describe('휴대폰 인증 안내', () => {
  it('미인증 상태는 인증 시 받을 5크레딧과 다음 행동을 보여준다', async () => {
    renderLayout({ user: userResponse({ phone_verified: false }) })

    const banner = screen.getByRole('region', { name: '휴대폰 인증하고 체험 5크레딧 받기' })
    expect(banner).toHaveTextContent('샘플 변환용 5크레딧')
    expect(screen.getByRole('link', { name: '휴대폰 인증하기' })).toHaveAttribute(
      'href',
      ACCOUNT_SETTINGS_PATH,
    )
  })

  it('인증 완료 상태는 안내를 숨긴다', async () => {
    renderLayout()

    expect(
      screen.queryByRole('region', { name: '휴대폰 인증하고 체험 5크레딧 받기' }),
    ).not.toBeInTheDocument()
  })

  it('이메일 미인증 사용자는 이메일 인증을 먼저 안내한다', () => {
    renderLayout({ user: userResponse({ email_verified: false, phone_verified: false }) })

    expect(screen.getByRole('link', { name: '이메일 인증하기' })).toHaveAttribute(
      'href',
      EMAIL_VERIFICATION_PATH,
    )
  })
})

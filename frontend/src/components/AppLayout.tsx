import { useState, type MouseEvent, type ReactNode } from 'react'
import { FilePlus2, History, LogOut, Menu, X } from 'lucide-react'
import { NavLink } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { confirmDiscardUnsaved } from '../review/unsavedChanges'
import { HISTORY_PATH, HOME_PATH } from '../routes/paths'
import { Logo } from './Logo'
import { WorkspaceMenu } from './WorkspaceMenu'
import { Button } from './ui/Button'

/**
 * 앱 껍데기 — 머리말(서비스명·이동 메뉴·로그아웃)과 본문 랜드마크.
 *
 * header/nav/main을 시맨틱 요소로 두는 것은 낭독기 사용자가 본문으로 바로 건너뛰기
 * 위한 최소 조건이다(KWCAG). 건너뛰기 링크도 같은 이유로 맨 앞에 둔다.
 *
 * 이동 메뉴와 로그아웃은 검수 화면을 떠나는 통로다 — 저장하지 않은 수정이 있으면
 * 먼저 물어본다(review/unsavedChanges.ts).
 */
export function AppLayout({ children }: { children: ReactNode }) {
  const { status, user, signOut } = useAuth()
  const [mobileOpen, setMobileOpen] = useState(false)

  function guard(event: MouseEvent): void {
    if (!confirmDiscardUnsaved()) {
      event.preventDefault()
    }
  }

  return (
    <>
      <a className="skip-link" href="#main">
        본문으로 건너뛰기
      </a>
      <header className="sticky top-0 z-40 border-b border-border bg-card/95 backdrop-blur">
        <div className="mx-auto flex min-h-16 w-full max-w-7xl flex-wrap items-center gap-3 px-4 py-2 sm:px-6">
          <NavLink
            className="shrink-0 rounded-md"
            to={HOME_PATH}
            end
            onClick={guard}
            aria-label="Easy-Read AI 홈"
          >
            <Logo />
          </NavLink>
          {status === 'authenticated' && (
            <>
              <nav aria-label="주요 메뉴" className="ml-4 hidden items-center gap-1 lg:flex">
                <NavLink
                  to={HOME_PATH}
                  end
                  onClick={guard}
                  className={({ isActive }) =>
                    `flex items-center gap-2 rounded-[10px] px-3 py-2 text-[15px] font-semibold ${
                      isActive
                        ? 'bg-accent text-accent-foreground'
                        : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
                    }`
                  }
                >
                  <FilePlus2 className="size-4" aria-hidden="true" />새 변환
                </NavLink>
                <NavLink
                  to={HISTORY_PATH}
                  onClick={guard}
                  className={({ isActive }) =>
                    `flex items-center gap-2 rounded-[10px] px-3 py-2 text-[15px] font-semibold ${
                      isActive
                        ? 'bg-accent text-accent-foreground'
                        : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
                    }`
                  }
                >
                  <History className="size-4" aria-hidden="true" />
                  변환 기록
                </NavLink>
              </nav>
              <div className="ml-auto hidden lg:block">
                <WorkspaceMenu />
              </div>
              <div className="ml-auto hidden items-center gap-2 xl:flex">
                {user !== null && (
                  <span className="max-w-48 truncate text-sm text-muted-foreground">
                    {user.email}
                  </span>
                )}
                <Button
                  variant="ghost"
                  size="sm"
                  type="button"
                  onClick={() => {
                    if (confirmDiscardUnsaved()) {
                      signOut()
                    }
                  }}
                >
                  <LogOut className="size-4" aria-hidden="true" />
                  로그아웃
                </Button>
              </div>
              <button
                type="button"
                aria-label={mobileOpen ? '메뉴 닫기' : '메뉴 열기'}
                aria-expanded={mobileOpen}
                onClick={() => setMobileOpen((open) => !open)}
                className="ml-auto flex size-10 items-center justify-center rounded-[10px] border border-border text-foreground hover:bg-secondary lg:hidden"
              >
                {mobileOpen ? <X className="size-5" /> : <Menu className="size-5" />}
              </button>
            </>
          )}
        </div>
        {status === 'authenticated' && (
          <div className="border-t border-border bg-card px-4 py-2 sm:px-6 lg:hidden">
            <div className="mx-auto max-w-7xl">
              <WorkspaceMenu />
            </div>
          </div>
        )}
        {status === 'authenticated' && mobileOpen && (
          <nav
            aria-label="주요 메뉴 (모바일)"
            className="border-t border-border bg-card px-4 py-3 lg:hidden"
          >
            <div className="mx-auto flex max-w-7xl flex-col gap-1">
              <NavLink
                className="rounded-[10px] px-3 py-2 font-semibold hover:bg-secondary"
                to={HOME_PATH}
                end
                onClick={guard}
              >
                새 변환
              </NavLink>
              <NavLink
                className="rounded-[10px] px-3 py-2 font-semibold hover:bg-secondary"
                to={HISTORY_PATH}
                onClick={guard}
              >
                변환 기록
              </NavLink>
              <Button
                variant="ghost"
                className="justify-start"
                onClick={() => {
                  if (confirmDiscardUnsaved()) signOut()
                }}
              >
                <LogOut className="size-4" />
                로그아웃
              </Button>
            </div>
          </nav>
        )}
      </header>
      <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-5 sm:px-6">
        {children}
      </main>
    </>
  )
}

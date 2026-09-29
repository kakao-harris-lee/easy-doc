import {
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
} from 'react'
import {
  BarChart3,
  HelpCircle,
  FilePlus2,
  History,
  LogOut,
  Menu,
  Settings,
  ShieldCheck,
  UserRound,
  X,
} from 'lucide-react'
import { Link, NavLink, useLocation } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { cn } from '../lib/utils'
import { confirmDiscardUnsaved } from '../review/unsavedChanges'
import {
  ACCOUNT_SETTINGS_PATH,
  ADMIN_PATH,
  GUIDE_PATH,
  HISTORY_PATH,
  HOME_PATH,
  LOGIN_PATH,
  SIGNUP_PATH,
  USAGE_PATH,
} from '../routes/paths'
import { AnnouncementBanner } from './AnnouncementBanner'
import { Footer } from './Footer'
import { PhoneVerificationBanner } from './PhoneVerificationBanner'
import { Logo, SERVICE_NAME } from './Logo'
import { ThemeSwitch } from './ThemeSwitch'
import { WorkspaceMenu } from './WorkspaceMenu'
import { CONTAINER } from './layoutStyles'
import { Button } from './ui/Button'

/** 본문 컨테이너 규격. 최대 너비 1200px, 좌우 여백은 16/24/32px이다. */
export { CONTAINER } from './layoutStyles'

/**
 * 주요 메뉴 링크의 모양.
 *
 * 활성 표시에 옅은 배경과 굵은 글씨를 함께 쓴다. 색만으로 상태를 알리지 않는다.
 *
 * 높이는 44px 이상으로 둔다(§10 터치 대상 최소치).
 */
function navLinkClass({ isActive }: { isActive: boolean }): string {
  return cn(
    'flex min-h-11 items-center gap-2 rounded-[10px] px-3 text-[15px]',
    isActive
      ? 'bg-accent font-bold text-accent-foreground'
      : 'font-medium text-muted-foreground hover:bg-secondary hover:text-foreground',
  )
}

function mobileLinkClass(state: { isActive: boolean }): string {
  return cn(navLinkClass(state), 'min-h-[52px]')
}

/**
 * 계정 메뉴 — 로그인 이메일과 로그아웃.
 *
 * 이메일을 머리말에 상시 노출하지 않고 이 메뉴 안으로 넣는다. 이메일은 자기
 * 계정을 확인할 때만 필요한 값인데, 늘 펼쳐 두면 매 화면에서 읽히는 시각적 소음이 된다.
 *
 * Fluent UI `Menu` 대신 직접 만든다. 저장소의 Fluent 사용처는 테마 제공자 하나뿐이고
 * 나머지 시각 언어는 Tailwind 토큰이다 — Fluent 메뉴는 자기 토큰으로 포털에 그려서
 * 이 앱의 색·모서리 규칙이 닿지 않는다. 필요한 것은 펼침 버튼 하나와 그 안의 행동
 * 하나뿐이라(disclosure), 메뉴 역할이 요구하는 화살표 이동 규약까지 빌릴 이유가 없다.
 *
 * 대신 초점 규약은 직접 지킨다: 펼치면 첫 행동으로 초점을 옮기고, Esc로 접으면 트리거로
 * 되돌리며, 초점이 밖으로 나가거나 바깥을 누르면 접는다.
 *
 * disclosure를 골랐으므로 속성도 disclosure만 쓴다 — `aria-expanded` + `aria-controls`가
 * 전부이고 `aria-haspopup`은 쓰지 않는다. `aria-haspopup="true"`는 `"menu"`와 동의어라
 * 낭독기에 "메뉴가 열린다"고 알리는데, 여기서 열리는 패널에는 `role="menu"`도
 * `menuitem`도 없다 — 약속한 역할이 실재하지 않으면 그 예고가 거짓말이 된다.
 */
function AccountMenu({
  email,
  isAdmin,
  onSignOut,
}: {
  email: string
  isAdmin: boolean
  onSignOut: () => void
}) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const firstItemRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (open) {
      firstItemRef.current?.focus()
    }
  }, [open])

  // 바깥을 누르면 접는다. click이 아니라 pointerdown으로 듣는다 — 바깥의 다른 버튼을
  // 누른 경우 그 버튼의 click이 먼저 처리되기 전에 메뉴가 사라지는 편이 자연스럽다.
  useEffect(() => {
    if (!open) {
      return
    }
    function handlePointerDown(event: PointerEvent): void {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', handlePointerDown)
    return () => document.removeEventListener('pointerdown', handlePointerDown)
  }, [open])

  /** Esc로 접고 초점을 트리거로 되돌린다. 초점이 있을 수 있는 두 요소에 각각 건다. */
  function handleEscape(event: KeyboardEvent<HTMLElement>): void {
    if (event.key !== 'Escape') {
      return
    }
    event.stopPropagation()
    setOpen(false)
    triggerRef.current?.focus()
  }

  return (
    <div
      ref={containerRef}
      className="relative"
      onBlur={(event) => {
        // 초점이 메뉴 밖으로 나가면 접는다(Tab으로 지나간 경우). 안쪽으로 옮겨가는
        // 중이면 relatedTarget이 여전히 이 컨테이너 안이다.
        if (!event.currentTarget.contains(event.relatedTarget)) {
          setOpen(false)
        }
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        aria-label="계정 메뉴"
        aria-expanded={open}
        // 패널은 접혔을 때 DOM에 없다. `aria-controls`도 그때만 건다 — 없는 id를 가리키는
        // 참조는 낭독기가 따라갈 대상이 없어 깨진 관계로 남는다.
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((value) => !value)}
        onKeyDown={handleEscape}
        className="flex size-11 items-center justify-center rounded-full border border-border text-foreground hover:bg-secondary"
      >
        <UserRound className="size-5" aria-hidden="true" />
      </button>
      {open && (
        <div
          id={panelId}
          className="absolute right-0 top-[calc(100%+0.5rem)] z-50 w-64 rounded-[12px] border border-border bg-card p-3 shadow-lg"
        >
          <p className="text-xs font-semibold text-muted-foreground">로그인 계정</p>
          {/* 긴 주소는 잘라 보이되 title로 전체를 남긴다. */}
          <p className="mt-1 truncate text-sm font-medium text-foreground" title={email}>
            {email}
          </p>
          <Button
            ref={firstItemRef}
            variant="ghost"
            type="button"
            className="mt-2 min-h-11 w-full justify-start"
            onClick={onSignOut}
            onKeyDown={handleEscape}
          >
            <LogOut className="size-4" aria-hidden="true" />
            로그아웃
          </Button>
          {/* 저장하지 않은 수정이 있으면 확인한 뒤 메뉴를 닫는다. */}
          <Link
            to={ACCOUNT_SETTINGS_PATH}
            className="mt-2 flex min-h-11 w-full items-center gap-2 rounded-md px-3 font-medium text-foreground hover:bg-secondary"
            onClick={(event) => {
              if (!confirmDiscardUnsaved()) {
                event.preventDefault()
                return
              }
              setOpen(false)
            }}
            onKeyDown={handleEscape}
          >
            <Settings className="size-4" aria-hidden="true" />
            계정 설정
          </Link>
          {/* 관리 링크는 표시값으로만 노출하고, 실제 접근은 서버가 다시 판정한다. */}
          {isAdmin && (
            <Link
              to={ADMIN_PATH}
              className="mt-2 flex min-h-11 w-full items-center gap-2 rounded-md px-3 font-semibold text-foreground hover:bg-secondary"
              onClick={(event) => {
                if (!confirmDiscardUnsaved()) {
                  event.preventDefault()
                  return
                }
                setOpen(false)
              }}
              onKeyDown={handleEscape}
            >
              <ShieldCheck className="size-4" aria-hidden="true" />
              관리
            </Link>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * 앱 껍데기 — 머리말(서비스명·이동 메뉴·작업 공간·계정)과 본문 랜드마크.
 *
 * header/nav/main을 시맨틱 요소로 두는 것은 낭독기 사용자가 본문으로 바로 건너뛰기
 * 위한 최소 조건이다(KWCAG). 건너뛰기 링크도 같은 이유로 맨 앞에 둔다.
 *
 * 데스크톱 한 줄의 순서는 `로고 · 새 변환 · 변환 기록 · (공백) · 작업 공간 · 계정`이다.
 * 작업 공간이 계정보다 앞에 온다(§5.1) — 목록과 새 변환의 범위를 정하는 현재 맥락이라
 * 계정 정보보다 자주 확인한다. 오른쪽 묶음에만 `ml-auto`를 걸어 공백을 한 번만 만든다.
 *
 * 목적지가 둘뿐이라 데스크톱 영구 사이드바를 두지 않는다(§4).
 *
 * 이동 메뉴와 로그아웃은 검수 화면을 떠나는 통로다 — 저장하지 않은 수정이 있으면
 * 먼저 물어본다(review/unsavedChanges.ts).
 */
export function AppLayout({ children }: { children: ReactNode }) {
  const { status, user, signOut } = useAuth()
  const { pathname } = useLocation()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [openedAt, setOpenedAt] = useState(pathname)
  const mobileNavId = useId()
  const menuButtonRef = useRef<HTMLButtonElement>(null)

  if (openedAt !== pathname) {
    setOpenedAt(pathname)
    setMobileOpen(false)
  }

  useEffect(() => {
    if (!mobileOpen) {
      return
    }
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    function handleKeyDown(event: globalThis.KeyboardEvent): void {
      if (event.key === 'Escape') {
        setMobileOpen(false)
        menuButtonRef.current?.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [mobileOpen])

  function guard(event: MouseEvent): void {
    if (!confirmDiscardUnsaved()) {
      event.preventDefault()
      return
    }
    setMobileOpen(false)
  }

  function guardedSignOut(): void {
    if (confirmDiscardUnsaved()) {
      setMobileOpen(false)
      signOut()
    }
  }

  const showLoginLink = pathname !== LOGIN_PATH && pathname !== SIGNUP_PATH

  return (
    <div className="flex min-h-dvh flex-col">
      <a className="skip-link" href="#main">
        본문으로 건너뛰기
      </a>
      <header className="sticky top-0 z-40 border-b border-border bg-card/95 backdrop-blur">
        <div className={cn(CONTAINER, 'flex min-h-14 items-center gap-2 lg:min-h-16 lg:gap-3')}>
          <NavLink
            className="inline-flex min-h-11 shrink-0 items-center rounded-md"
            to={HOME_PATH}
            end
            onClick={guard}
            aria-label={`${SERVICE_NAME} 홈`}
          >
            <Logo compact />
          </NavLink>
          {status === 'anonymous' && (
            <>
              <nav aria-label="시작 메뉴" className="ml-auto flex items-center gap-1">
                <NavLink
                  to={GUIDE_PATH}
                  aria-label="이용 가이드"
                  className={(state) =>
                    cn(navLinkClass(state), 'max-sm:size-11 max-sm:justify-center max-sm:px-0')
                  }
                >
                  <HelpCircle className="size-5 sm:size-4" aria-hidden="true" />
                  <span className="hidden sm:inline">이용 가이드</span>
                </NavLink>
                {showLoginLink && (
                  <NavLink to={LOGIN_PATH} className={navLinkClass}>
                    로그인
                  </NavLink>
                )}
              </nav>
              <ThemeSwitch />
            </>
          )}
          {status === 'authenticated' && (
            <>
              <nav aria-label="주요 메뉴" className="ml-4 hidden items-center gap-1 lg:flex">
                <NavLink to={HOME_PATH} end onClick={guard} className={navLinkClass}>
                  <FilePlus2 className="size-4" aria-hidden="true" />새 변환
                </NavLink>
                <NavLink to={HISTORY_PATH} onClick={guard} className={navLinkClass}>
                  <History className="size-4" aria-hidden="true" />
                  변환 기록
                </NavLink>
                <NavLink to={USAGE_PATH} onClick={guard} className={navLinkClass}>
                  <BarChart3 className="size-4" aria-hidden="true" />
                  사용량
                </NavLink>
                <NavLink to={GUIDE_PATH} onClick={guard} className={navLinkClass}>
                  <HelpCircle className="size-4" aria-hidden="true" />
                  이용 가이드
                </NavLink>
              </nav>
              <div className="ml-auto flex min-w-0 items-center gap-2 lg:gap-3">
                <div className="hidden min-w-0 lg:block">
                  <WorkspaceMenu />
                </div>
                {!mobileOpen && (
                  <div className="min-w-0 lg:hidden">
                    <WorkspaceMenu variant="compact" align="right" />
                  </div>
                )}
                <ThemeSwitch className="hidden lg:inline-flex" />
                {user !== null && (
                  <div className="hidden lg:block">
                    <AccountMenu
                      email={user.email}
                      isAdmin={user.is_admin}
                      onSignOut={guardedSignOut}
                    />
                  </div>
                )}
                <button
                  ref={menuButtonRef}
                  type="button"
                  aria-label={mobileOpen ? '메뉴 닫기' : '메뉴 열기'}
                  aria-expanded={mobileOpen}
                  aria-controls={mobileOpen ? mobileNavId : undefined}
                  onClick={() => setMobileOpen((open) => !open)}
                  className="flex size-11 shrink-0 items-center justify-center rounded-[10px] border border-border text-foreground hover:bg-secondary lg:hidden"
                >
                  {mobileOpen ? <X className="size-5" /> : <Menu className="size-5" />}
                </button>
              </div>
            </>
          )}
        </div>
      </header>
      {status === 'authenticated' && mobileOpen && (
        // 헤더의 backdrop-blur가 fixed 자손의 기준 상자를 헤더로 바꾸므로 헤더 밖에 둔다.
        <nav
          id={mobileNavId}
          aria-label="주요 메뉴 (모바일)"
          className="fixed inset-x-0 bottom-0 top-[57px] z-40 overflow-y-auto overscroll-contain bg-card lg:hidden"
        >
          <div className={cn(CONTAINER, 'flex min-h-full flex-col gap-1 py-4')}>
            <WorkspaceMenu variant="row" />
            <NavLink to={HOME_PATH} end onClick={guard} className={mobileLinkClass}>
              <FilePlus2 className="size-5" aria-hidden="true" />새 변환
            </NavLink>
            <NavLink to={HISTORY_PATH} onClick={guard} className={mobileLinkClass}>
              <History className="size-5" aria-hidden="true" />
              변환 기록
            </NavLink>
            <NavLink to={USAGE_PATH} onClick={guard} className={mobileLinkClass}>
              <BarChart3 className="size-5" aria-hidden="true" />
              사용량
            </NavLink>
            <NavLink to={GUIDE_PATH} onClick={guard} className={mobileLinkClass}>
              <HelpCircle className="size-5" aria-hidden="true" />
              이용 가이드
            </NavLink>
            <div className="flex min-h-[52px] items-center px-3">
              <ThemeSwitch />
            </div>
            <div className="mt-auto flex flex-col gap-1 border-t border-border pt-3">
              {user !== null && (
                <p className="truncate px-3 text-sm text-muted-foreground" title={user.email}>
                  <span className="font-semibold">로그인 계정</span> {user.email}
                </p>
              )}
              {user !== null && user.is_admin && (
                <NavLink to={ADMIN_PATH} onClick={guard} className={mobileLinkClass}>
                  <ShieldCheck className="size-5" aria-hidden="true" />
                  관리
                </NavLink>
              )}
              {user !== null && (
                <NavLink to={ACCOUNT_SETTINGS_PATH} onClick={guard} className={mobileLinkClass}>
                  <Settings className="size-5" aria-hidden="true" />
                  계정 설정
                </NavLink>
              )}
              <Button
                variant="ghost"
                className="min-h-[52px] justify-start px-3 text-[15px]"
                onClick={guardedSignOut}
                type="button"
              >
                <LogOut className="size-5" aria-hidden="true" />
                로그아웃
              </Button>
            </div>
          </div>
        </nav>
      )}
      {status === 'authenticated' && <AnnouncementBanner />}
      {status === 'authenticated' && <PhoneVerificationBanner onNavigate={guard} />}
      <main id="main" className={cn(CONTAINER, 'flex-1 py-6')}>
        {children}
      </main>
      {/*
        로그인 여부와 무관하게 모든 화면에 나온다(전자상거래법의 사업자 정보 초기
        화면 표시 의무) — 위 머리말 메뉴들과 달리 `status === 'authenticated'`로
        가리지 않는다.
      */}
      <Footer />
    </div>
  )
}

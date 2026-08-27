import {
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
} from 'react'
import { FilePlus2, History, LogOut, Menu, UserRound, X } from 'lucide-react'
import { NavLink } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { cn } from '../lib/utils'
import { confirmDiscardUnsaved } from '../review/unsavedChanges'
import { HISTORY_PATH, HOME_PATH } from '../routes/paths'
import { Logo } from './Logo'
import { WorkspaceMenu } from './WorkspaceMenu'
import { Button } from './ui/Button'

/**
 * 본문 컨테이너 규격 (DESIGN.md §5.2, §10).
 *
 * 최대 너비 1200px, 좌우 여백은 모바일 16 / 태블릿 24 / 데스크톱 32px다. 중단점은
 * §10의 구간(768~1279 / 1280 이상)과 같은 뜻으로 `md`·`xl`을 쓴다 — Tailwind 기본
 * `sm`(640)은 §10의 어느 경계와도 맞지 않아 태블릿 여백이 한 구간 일찍 커진다.
 *
 * 검수 화면만 1360px까지 넓힐 수 있다는 예외(§5.2)가 아직 남아 있어 한 곳에 모아 둔다 —
 * 그때 넓히는 것은 이 상수와 그것을 쓰는 `main` 한 곳이다.
 */
const CONTAINER = 'mx-auto w-full max-w-[1200px] px-4 md:px-6 xl:px-8'

/**
 * 주요 메뉴 링크의 모양.
 *
 * 활성 표시에 옅은 배경과 굵은 글씨를 함께 쓴다(§5.1). 예전에는 양쪽 다
 * `font-semibold`라 굵기 대비가 실제로는 없었고 색만 달랐다 — 색만으로 상태를 알리면
 * 색각 이상 사용자에게는 표시가 사라진다.
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

/**
 * 계정 메뉴 — 로그인 이메일과 로그아웃.
 *
 * 이메일을 머리말에 상시 노출하지 않고 이 메뉴 안으로 넣는다(§5.1). 이메일은 자기
 * 계정을 확인할 때만 필요한 값인데, 늘 펼쳐 두면 매 화면에서 읽히는 시각적 소음이 된다.
 *
 * Fluent UI `Menu` 대신 직접 만든다. 저장소의 Fluent 사용처는 테마 제공자 하나뿐이고
 * 나머지 시각 언어는 Tailwind 토큰이다 — Fluent 메뉴는 자기 토큰으로 포털에 그려서
 * 이 앱의 색·모서리 규칙이 닿지 않는다. 필요한 것은 펼침 버튼 하나와 그 안의 행동
 * 하나뿐이라(disclosure), 메뉴 역할이 요구하는 화살표 이동 규약까지 빌릴 이유가 없다.
 *
 * 대신 초점 규약은 직접 지킨다: 펼치면 첫 행동으로 초점을 옮기고, Esc로 접으면 트리거로
 * 되돌리며, 초점이 밖으로 나가거나 바깥을 누르면 접는다(§11).
 *
 * disclosure를 골랐으므로 속성도 disclosure만 쓴다 — `aria-expanded` + `aria-controls`가
 * 전부이고 `aria-haspopup`은 쓰지 않는다. `aria-haspopup="true"`는 `"menu"`와 동의어라
 * 낭독기에 "메뉴가 열린다"고 알리는데, 여기서 열리는 패널에는 `role="menu"`도
 * `menuitem`도 없다 — 약속한 역할이 실재하지 않으면 그 예고가 곧 거짓말이 된다.
 * 패널에 `role="menu"`를 붙이는 반대 방향은 더 나쁘다: 계정 이메일 `<p>`가 `menuitem`이
 * 아니라 곧바로 규격 위반이고, 화살표·Home/End 이동 규약까지 딸려 온다.
 */
function AccountMenu({ email, onSignOut }: { email: string; onSignOut: () => void }) {
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
  const [mobileOpen, setMobileOpen] = useState(false)
  // 햄버거도 계정 메뉴와 같은 disclosure다 — 펼쳐지는 nav를 `aria-controls`로 가리켜야
  // 낭독기가 "무엇이 펼쳐졌는지"를 안다. 접혔을 때 nav가 DOM에 없으므로 참조도 그때만 건다.
  const mobileNavId = useId()

  function guard(event: MouseEvent): void {
    if (!confirmDiscardUnsaved()) {
      event.preventDefault()
    }
  }

  function guardedSignOut(): void {
    if (confirmDiscardUnsaved()) {
      signOut()
    }
  }

  return (
    <>
      <a className="skip-link" href="#main">
        본문으로 건너뛰기
      </a>
      <header className="sticky top-0 z-40 border-b border-border bg-card/95 backdrop-blur">
        <div className={cn(CONTAINER, 'flex min-h-16 items-center gap-3 py-2')}>
          <NavLink
            // 로고 자체는 36px 이지만 머리말에서 실제로 누르는 대상이므로 44px 을
            // 확보한다(§10). 머리말은 min-h-16 이라 세로 배치는 그대로다.
            className="inline-flex min-h-11 shrink-0 items-center rounded-md"
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
                <NavLink to={HOME_PATH} end onClick={guard} className={navLinkClass}>
                  <FilePlus2 className="size-4" aria-hidden="true" />새 변환
                </NavLink>
                <NavLink to={HISTORY_PATH} onClick={guard} className={navLinkClass}>
                  <History className="size-4" aria-hidden="true" />
                  변환 기록
                </NavLink>
              </nav>
              <div className="ml-auto flex min-w-0 items-center gap-3">
                <div className="hidden min-w-0 lg:block">
                  <WorkspaceMenu />
                </div>
                {user !== null && (
                  <div className="hidden lg:block">
                    <AccountMenu email={user.email} onSignOut={guardedSignOut} />
                  </div>
                )}
                <button
                  type="button"
                  aria-label={mobileOpen ? '메뉴 닫기' : '메뉴 열기'}
                  aria-expanded={mobileOpen}
                  aria-controls={mobileOpen ? mobileNavId : undefined}
                  onClick={() => setMobileOpen((open) => !open)}
                  className="flex size-11 items-center justify-center rounded-[10px] border border-border text-foreground hover:bg-secondary lg:hidden"
                >
                  {mobileOpen ? <X className="size-5" /> : <Menu className="size-5" />}
                </button>
              </div>
            </>
          )}
        </div>
        {/*
          모바일의 작업 공간 — 햄버거 안이 아니라 앱 바 바로 아래 전체 너비 행이다.
          §10이 "모바일에서 작업 공간이 메뉴 안에 감춰지지 않게" 하라고 못박았고,
          §6.7의 "메뉴 상단의 전체 너비 선택 행"이 바로 이 자리다.

          그래서 `WorkspaceMenu`는 DOM에 두 벌 그려진다(데스크톱 자리 + 이 행). Tailwind
          `hidden`은 `display:none`이라 뷰포트마다 정확히 하나만 보이고 숨은 쪽은 접근성
          트리에서도 빠지므로, 낭독기에는 하나만 들린다. 조건부 렌더로 바꾸면 미디어 쿼리
          훅과 리사이즈 처리가 딸려 오는데 얻는 것은 없다(e2e 로케이터도 이 성질에 맞춰
          `filter({ visible: true })`로 좁혀 둔 상태다 — e2e/support/app.ts).
        */}
        {status === 'authenticated' && (
          <div className="border-t border-border bg-card lg:hidden">
            <div className={cn(CONTAINER, 'py-2')}>
              <WorkspaceMenu />
            </div>
          </div>
        )}
        {status === 'authenticated' && mobileOpen && (
          <nav
            id={mobileNavId}
            aria-label="주요 메뉴 (모바일)"
            className="border-t border-border bg-card lg:hidden"
          >
            <div className={cn(CONTAINER, 'flex flex-col gap-1 py-3')}>
              <NavLink to={HOME_PATH} end onClick={guard} className={navLinkClass}>
                새 변환
              </NavLink>
              <NavLink to={HISTORY_PATH} onClick={guard} className={navLinkClass}>
                변환 기록
              </NavLink>
              {/* 좁은 화면에서는 이 메뉴가 계정 메뉴를 겸한다 — 이메일도 여기서만 보인다. */}
              {user !== null && (
                <p className="mt-2 truncate px-3 text-sm text-muted-foreground" title={user.email}>
                  <span className="font-semibold">로그인 계정</span> {user.email}
                </p>
              )}
              <Button
                variant="ghost"
                className="min-h-11 justify-start"
                onClick={guardedSignOut}
                type="button"
              >
                <LogOut className="size-4" aria-hidden="true" />
                로그아웃
              </Button>
            </div>
          </nav>
        )}
      </header>
      <main id="main" className={cn(CONTAINER, 'flex-1 py-6')}>
        {children}
      </main>
    </>
  )
}

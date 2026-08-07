import type { MouseEvent, ReactNode } from 'react'
import { NavLink } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { confirmDiscardUnsaved } from '../review/unsavedChanges'
import { HISTORY_PATH, HOME_PATH } from '../routes/paths'

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
      <header className="app-header">
        <h1>Easy-Read AI</h1>
        {status === 'authenticated' && (
          <>
            <nav aria-label="주요 메뉴" className="app-nav">
              <NavLink to={HOME_PATH} end onClick={guard}>
                문서 변환
              </NavLink>
              <NavLink to={HISTORY_PATH} onClick={guard}>
                변환 기록
              </NavLink>
            </nav>
            <div className="app-header__account">
              {user !== null && <span className="app-header__email">{user.email}</span>}
              <button
                type="button"
                onClick={() => {
                  if (confirmDiscardUnsaved()) {
                    signOut()
                  }
                }}
              >
                로그아웃
              </button>
            </div>
          </>
        )}
      </header>
      <main id="main" className="app-main">
        {children}
      </main>
    </>
  )
}

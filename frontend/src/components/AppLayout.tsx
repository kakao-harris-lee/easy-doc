import type { ReactNode } from 'react'

import { useAuth } from '../auth/context'

/**
 * 앱 껍데기 — 머리말(서비스명·로그아웃)과 본문 랜드마크.
 *
 * header/main을 시맨틱 요소로 두는 것은 낭독기 사용자가 본문으로 바로 건너뛰기
 * 위한 최소 조건이다(KWCAG). 건너뛰기 링크도 같은 이유로 맨 앞에 둔다.
 */
export function AppLayout({ children }: { children: ReactNode }) {
  const { status, user, signOut } = useAuth()

  return (
    <>
      <a className="skip-link" href="#main">
        본문으로 건너뛰기
      </a>
      <header className="app-header">
        <h1>Easy-Read AI</h1>
        {status === 'authenticated' && (
          <div className="app-header__account">
            {user !== null && <span className="app-header__email">{user.email}</span>}
            <button type="button" onClick={signOut}>
              로그아웃
            </button>
          </div>
        )}
      </header>
      <main id="main" className="app-main">
        {children}
      </main>
    </>
  )
}

import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'

import { useAuth } from '../auth/context'
import { LOGIN_PATH, type FromLocationState } from './paths'

/**
 * 로그인한 사용자에게만 자식 화면을 보여준다.
 *
 * 상태가 `loading`인 동안 로그인 화면으로 보내면, 새로고침할 때마다 로그인 화면이
 * 깜빡였다가 되돌아온다 — 확인이 끝날 때까지 판단을 미룬다.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'loading') {
    return (
      <p className="route-status" role="status">
        로그인 상태를 확인하는 중입니다…
      </p>
    )
  }

  if (status === 'anonymous') {
    const state: FromLocationState = { from: location.pathname }
    return <Navigate to={LOGIN_PATH} replace state={state} />
  }

  return <>{children}</>
}

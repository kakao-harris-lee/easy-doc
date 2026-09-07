import { Navigate } from 'react-router-dom'
import type { ReactNode } from 'react'

import { useAuth } from '../auth/context'
import { HOME_PATH, type HomeNoticeState } from './paths'

/** `AdminGuard`가 403에 싣는 문구와 같다(계획 §2 결정 2). */
export const ADMIN_REQUIRED_MESSAGE = '관리자 권한이 필요합니다'

/**
 * 관리자에게만 자식 화면을 보여준다. `RequireAuth` 안에서(인증 확인 뒤) 쓴다.
 *
 * `user.is_admin`은 화면이 「관리」 링크를 보여줄지 판단하는 표시값일 뿐이다 — 실제
 * 접근은 서버(`AdminGuard`)가 매 요청 DB를 다시 읽어 판정한다(계획
 * `docs/plans/2026-09-07-admin-minimum.md` §2 결정 1·2). 이 가드는 그 사실을 미리 알아
 * 헛된 API 호출과 403 화면 깜빡임을 막을 뿐, 서버 판정을 대신하지 않는다.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth()

  if (user === null || !user.is_admin) {
    const state: HomeNoticeState = { notice: ADMIN_REQUIRED_MESSAGE, noticeTone: 'warning' }
    return <Navigate to={HOME_PATH} replace state={state} />
  }

  return <>{children}</>
}

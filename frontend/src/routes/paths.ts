/** 라우트 경로 상수와 가드가 주고받는 상태 타입. */

export const HOME_PATH = '/'
export const LOGIN_PATH = '/login'
export const SIGNUP_PATH = '/signup'

/** 가드가 로그인 화면으로 넘길 때 싣는 원래 목적지. */
export interface FromLocationState {
  from?: string
}

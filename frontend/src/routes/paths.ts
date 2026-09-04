/** 라우트 경로 상수와 가드가 주고받는 상태 타입. */

export const HOME_PATH = '/'
export const LOGIN_PATH = '/login'
export const SIGNUP_PATH = '/signup'
export const HISTORY_PATH = '/history'

/** 이메일 인증 화면. 가입(이메일·비밀번호) 직후 이리로 보낸다. */
export const EMAIL_VERIFICATION_PATH = '/verify-email'

/**
 * 구글 로그인 콜백 라우트. `GoogleLoginButton`이 만드는 `redirect_uri`와 같은 값이어야
 * 한다 — 제공자가 오늘 google 하나뿐이라 경로를 고정한다(계약 `x-social-login`).
 */
export const OAUTH_GOOGLE_CALLBACK_PATH = '/auth/google/callback'

/** 변환 화면 라우트 패턴 (`useParams`의 키와 같은 이름을 쓴다). */
export const CONVERSION_PATH = '/conversions/:conversionId'

/** 변환 화면 주소를 만든다. */
export function conversionPath(conversionId: string): string {
  return `/conversions/${conversionId}`
}

/** 가드가 로그인 화면으로 넘길 때 싣는 원래 목적지. */
export interface FromLocationState {
  from?: string
}

/**
 * 업로드 화면이 변환 화면으로 넘기는 상태.
 *
 * 붙여넣기로 올린 경우 방금 화면에 있던 원문을 그대로 들고 넘어간다. **이것은 첫 화면을
 * 곧바로 그리기 위한 값일 뿐 원문의 출처가 아니다** — 새로고침하거나 변환 기록에서 다시
 * 들어오면 사라지므로, 변환 화면은 `GET /documents/{id}/source`로 원문을 따로 가져오고
 * 서버 응답을 최종 진실로 삼는다(`src/review/sourceText.ts`).
 */
export interface SourceTextState {
  sourceText?: string
}

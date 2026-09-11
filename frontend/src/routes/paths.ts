/** 라우트 경로 상수와 가드가 주고받는 상태 타입. */

export const HOME_PATH = '/'
export const LOGIN_PATH = '/login'
export const SIGNUP_PATH = '/signup'
export const HISTORY_PATH = '/history'

/**
 * 이용약관·개인정보처리방침 화면(P0-13, 계획
 * `docs/plans/2026-09-09-legal-footer-and-support.md` §5.2). 로그인 없이 열린다 —
 * `AppRoutes`에서 `RequireAuth`로 감싸지 않는다.
 */
export const TERMS_PATH = '/terms'
export const PRIVACY_PATH = '/privacy'

/** 워크스페이스 사용량 화면 (U2, 계약 2.20.0). */
export const USAGE_PATH = '/usage'

/**
 * 관리자 화면 (어드민 최소, 계약 2.25.0). 관리자가 아니면 `RequireAdmin`이 `HOME_PATH`로
 * 돌려보낸다 — `docs/plans/2026-09-07-admin-minimum.md` §2 결정 6.
 */
export const ADMIN_PATH = '/admin'

/**
 * 계정 설정 화면 (회원 탈퇴, 계약 2.27.0). 계정 메뉴의 「계정 설정」 링크가 이리로
 * 보낸다 — `docs/plans/2026-09-09-account-deletion.md`.
 */
export const ACCOUNT_SETTINGS_PATH = '/account'

/** 이메일 인증 화면. 가입(이메일·비밀번호) 직후 이리로 보낸다. */
export const EMAIL_VERIFICATION_PATH = '/verify-email'

/**
 * 비밀번호 재설정 화면(2.19.0 신설, backlog §1.4 다음 조각). 로그인 화면의 「비밀번호를
 * 잊으셨나요?」 링크가 이리로 보낸다 — 인증 없이 접근한다.
 */
export const RESET_PASSWORD_PATH = '/reset-password'

/**
 * 소셜 로그인 콜백 라우트 패턴. `useParams`의 `provider` 키와 이름을 맞춘다(계약
 * `x-social-login.flow`, 2.13.0부터 카카오도 지원 — `auth/socialLogin.ts`의
 * `oauthCallbackPath`가 실제 provider로 이 패턴을 채운 값을 만든다).
 */
export const OAUTH_CALLBACK_PATH = '/auth/:provider/callback'

/**
 * 명시적 계정 연결 콜백 라우트 패턴(2.10.0 신설, backlog §1.4). 로그인 콜백과 주소를
 * 나누는 이유는 그 화면이 인증 전(Bearer 없음)이고 이 화면은 인증 후(Bearer 필요)라
 * 요구하는 것과 실패 갈래가 다르기 때문이다 — `RequireAuth`로 감싸 미로그인 진입을
 * 걸러낸다.
 */
export const OAUTH_LINK_CALLBACK_PATH = '/auth/:provider/link/callback'

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
 * 홈으로 돌아갈 때 한 번만 보여줄 안내. `OAuthLinkCallbackPage`가 연결 성공 뒤 이
 * 상태를 싣고 홈으로 이동한다 — 라우터 state라 새로고침하면 사라진다(진짜 "한 번"이다).
 *
 * `noticeTone`은 배너 색을 정한다 — 생략하면 `'success'`(기존 기본값과 같다).
 * `RequireAdmin`이 관리자가 아닌 방문을 돌려보낼 때는 `'warning'`을 쓴다(성공이
 * 아니라 거절이므로 초록 체크 배지를 쓰면 안 된다).
 */
export interface HomeNoticeState {
  notice?: string
  noticeTone?: 'success' | 'warning'
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

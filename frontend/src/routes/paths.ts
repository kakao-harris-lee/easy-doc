export const HOME_PATH = '/'
export const LOGIN_PATH = '/login'
export const SIGNUP_PATH = '/signup'
export const HISTORY_PATH = '/history'

export const TERMS_PATH = '/terms'
export const PRIVACY_PATH = '/privacy'

export const GUIDE_PATH = '/guide'

export const USAGE_PATH = '/usage'

export const ADMIN_PATH = '/admin'

export const ACCOUNT_SETTINGS_PATH = '/account'

export const EMAIL_VERIFICATION_PATH = '/verify-email'

export const RESET_PASSWORD_PATH = '/reset-password'

export const OAUTH_CALLBACK_PATH = '/auth/:provider/callback'

// 로그인 콜백과 달리 계정 연결 콜백은 인증된 세션에서만 처리한다.
export const OAUTH_LINK_CALLBACK_PATH = '/auth/:provider/link/callback'

export const CONVERSION_PATH = '/conversions/:conversionId'

export function conversionPath(conversionId: string): string {
  return `/conversions/${conversionId}`
}

export interface FromLocationState {
  from?: string
}

export interface HomeNoticeState {
  notice?: string
  noticeTone?: 'success' | 'warning'
}

// sourceText는 최초 렌더 최적화용이며 서버 응답이 정본이다.
export interface SourceTextState {
  sourceText?: string
}

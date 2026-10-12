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

/**
 * 로그인 전 복귀할 내부 경로를 만든다.
 *
 * 결제 콜백의 새 흐름은 `resume` 식별자를 쿼리에 남겨야 하지만, 기존 토스
 * 콜백의 `authKey`·`customerKey`는 로그인 화면이나 히스토리로 전달하면 안 된다.
 * 외부 URL도 상태 값에 들어오지 않도록 origin을 현재 앱으로 고정한다.
 */
export function safeReturnPath(location: Pick<Location, 'pathname' | 'search' | 'hash'>): string {
  const params = new URLSearchParams(location.search)
  params.delete('authKey')
  params.delete('customerKey')
  params.delete('billingKey')
  const search = params.toString()
  return `${location.pathname}${search === '' ? '' : `?${search}`}${location.hash}`
}

/** 상태로 건너온 복귀 경로도 내부 경로와 안전한 결제 쿼리만 허용한다. */
export function sanitizeReturnPath(value: string | undefined): string {
  if (value === undefined || value === '') return HOME_PATH
  try {
    const url = new URL(value, window.location.origin)
    if (url.origin !== window.location.origin || !url.pathname.startsWith('/')) {
      return HOME_PATH
    }
    return safeReturnPath(url)
  } catch {
    return HOME_PATH
  }
}

export interface HomeNoticeState {
  notice?: string
  noticeTone?: 'success' | 'warning'
}

// sourceText는 최초 렌더 최적화용이며 서버 응답이 정본이다.
export interface SourceTextState {
  sourceText?: string
}

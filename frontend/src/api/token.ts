/**
 * 액세스 토큰 보관소.
 *
 * **저장 위치: localStorage — XSS 트레이드오프를 알고 받아들인 선택이다.**
 * localStorage는 스크립트로 읽히므로, 앱에 XSS가 생기면 토큰이 그대로 새어 나간다.
 * 더 안전한 대안은 HttpOnly + Secure 쿠키지만, 그러려면 백엔드가 쿠키 세션과 CSRF
 * 방어를 함께 갖춰야 한다(지금 백엔드는 Authorization: Bearer 단일 방식).
 * 파일럿 단계에서는 이 위험을 수용하고, **P0에서 쿠키 세션으로 재검토**한다.
 * 위험을 줄이는 현재 조치: 토큰 수명 제한(백엔드 expires_in), 401 시 즉시 폐기.
 */

const TOKEN_KEY = 'easydoc.access_token'

/** 저장된 토큰을 읽는다. 없거나 저장소를 쓸 수 없으면 null. */
export function readToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY)
  } catch {
    // 사생활 보호 모드 등에서 localStorage 접근이 막힐 수 있다 — 비로그인으로 취급한다.
    return null
  }
}

/** 토큰을 저장한다. */
export function writeToken(token: string): void {
  try {
    window.localStorage.setItem(TOKEN_KEY, token)
  } catch {
    // 저장에 실패해도 현재 탭의 요청은 진행할 수 있어야 하므로 오류로 올리지 않는다.
  }
}

/** 토큰을 지운다 (로그아웃, 401 응답). */
export function clearToken(): void {
  try {
    window.localStorage.removeItem(TOKEN_KEY)
  } catch {
    // 위와 같은 이유.
  }
}

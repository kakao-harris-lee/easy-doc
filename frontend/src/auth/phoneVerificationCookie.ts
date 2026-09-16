/**
 * 휴대폰 인증 안내 상태를 브라우저에 남기는 1차 기능 쿠키.
 *
 * 이 값은 배너 표시를 빠르게 이어 가기 위한 UX 상태일 뿐이다. 결제 허용이나 크레딧
 * 지급처럼 권한이 필요한 판단은 언제나 서버의 `UserResponse.phone_verified`와 각 API가
 * 다시 결정한다. 자바스크립트가 쓰는 쿠키이므로 HttpOnly일 수 없다는 점도 같은 이유로
 * 안전하다 — 민감정보나 휴대폰 번호는 싣지 않고 `true`/`false`만 기록한다.
 */
export const PHONE_VERIFICATION_COOKIE_NAME = 'easydoc_phone_verified'

const COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365

export function writePhoneVerificationCookie(verified: boolean): void {
  try {
    const secure = window.location.protocol === 'https:' ? '; Secure' : ''
    document.cookie = `${PHONE_VERIFICATION_COOKIE_NAME}=${verified}; Path=/; Max-Age=${COOKIE_MAX_AGE_SECONDS}; SameSite=Lax${secure}`
  } catch {
    // 쿠키가 차단돼도 서버 사용자 상태로 화면과 권한 판단을 계속할 수 있다.
  }
}

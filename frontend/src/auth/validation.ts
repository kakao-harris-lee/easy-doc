/**
 * 가입·로그인 입력 검증.
 *
 * 최종 판단은 백엔드(app/services/auth.py)가 한다. 여기 규칙은 왕복 한 번을 아끼기
 * 위한 사전 안내이므로, 백엔드보다 **엄격하지 않게** 유지한다 — 여기서만 막히는
 * 입력이 생기면 사용자는 이유를 알 수 없다.
 */

/** 백엔드 MIN_PASSWORD_LENGTH와 같은 값. */
export const MIN_PASSWORD_LENGTH = 8

/** 백엔드 _EMAIL_PATTERN과 같은 규칙. */
const EMAIL_PATTERN = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/

/** 백엔드 MAX_EMAIL_LENGTH와 같은 값. */
const MAX_EMAIL_LENGTH = 255

/** 이메일 오류 문구를 돌려준다. 문제가 없으면 null. */
export function validateEmail(email: string): string | null {
  const normalized = email.trim()
  if (normalized === '') {
    return '이메일을 입력해 주세요'
  }
  if (normalized.length > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.test(normalized)) {
    return '이메일 형식이 올바르지 않습니다'
  }
  return null
}

/** 비밀번호 오류 문구를 돌려준다. 문제가 없으면 null. */
export function validatePassword(password: string): string | null {
  if (password === '') {
    return '비밀번호를 입력해 주세요'
  }
  if (password.length < MIN_PASSWORD_LENGTH) {
    return `비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상이어야 합니다`
  }
  return null
}

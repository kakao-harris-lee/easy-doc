/**
 * 일회용 인증 코드(이메일·휴대폰) 길이. 계약
 * `ConfirmEmailVerificationRequest.code`·`ConfirmPhoneVerificationRequest.code`
 * 모두 6자리 고정이다.
 */
export const ONE_TIME_CODE_LENGTH = 6

/** 사용자가 입력한 코드에서 숫자만 남기고 정해진 길이로 자른다. */
export function sanitizeOneTimeCode(raw: string): string {
  return raw.replace(/\D/g, '').slice(0, ONE_TIME_CODE_LENGTH)
}

import { useId, useState } from 'react'
import type { FormEvent } from 'react'

import { ApiError } from '../api/client'
import { validateEmail, validatePassword } from '../auth/validation'

interface CredentialsFormProps {
  /** 제출 버튼 문구 ("로그인" / "가입하기"). */
  submitLabel: string
  /** 비밀번호 입력 아래에 보여줄 안내(가입 화면의 길이 안내 등). */
  passwordHint?: string
  /** 브라우저 비밀번호 관리자에게 용도를 알린다. */
  passwordAutoComplete: 'current-password' | 'new-password'
  onSubmit: (email: string, password: string) => Promise<void>
}

/**
 * 이메일·비밀번호 입력 폼 (로그인·가입 공용).
 *
 * 두 화면이 같은 접근성 배선(label 연결, 오류 알림, 포커스 대상)을 갖도록 한 곳에
 * 둔다. `noValidate`를 쓰는 이유는 브라우저 기본 검증 풍선이 뜨면 우리 한국어 오류
 * 문구가 화면에 남지 않아 낭독기 사용자가 이유를 듣지 못하기 때문이다.
 */
export function CredentialsForm({
  submitLabel,
  passwordHint,
  passwordAutoComplete,
  onSubmit,
}: CredentialsFormProps) {
  const emailId = useId()
  const passwordId = useId()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [emailError, setEmailError] = useState<string | null>(null)
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextEmailError = validateEmail(email)
    const nextPasswordError = validatePassword(password)
    setEmailError(nextEmailError)
    setPasswordError(nextPasswordError)
    setFormError(null)
    if (nextEmailError !== null || nextPasswordError !== null) {
      return
    }
    setSubmitting(true)
    try {
      await onSubmit(email.trim(), password)
    } catch (error) {
      // ApiError의 메시지는 백엔드가 사용자에게 보이려고 만든 문구다(입력값 미포함).
      setFormError(
        error instanceof ApiError
          ? error.message
          : '요청을 처리하지 못했습니다. 다시 시도해 주세요.',
      )
      setSubmitting(false)
    }
  }

  return (
    <form className="credentials-form" onSubmit={handleSubmit} noValidate>
      {formError !== null && (
        <p className="form-error" role="alert">
          {formError}
        </p>
      )}

      <div className="field">
        <label htmlFor={emailId}>이메일</label>
        <input
          id={emailId}
          type="email"
          value={email}
          autoComplete="email"
          aria-invalid={emailError !== null}
          aria-describedby={emailError !== null ? `${emailId}-error` : undefined}
          onChange={(event) => setEmail(event.target.value)}
        />
        {emailError !== null && (
          <p className="field-error" id={`${emailId}-error`} role="alert">
            {emailError}
          </p>
        )}
      </div>

      <div className="field">
        <label htmlFor={passwordId}>비밀번호</label>
        <input
          id={passwordId}
          type="password"
          value={password}
          autoComplete={passwordAutoComplete}
          aria-invalid={passwordError !== null}
          aria-describedby={
            passwordError !== null
              ? `${passwordId}-error`
              : passwordHint !== undefined
                ? `${passwordId}-hint`
                : undefined
          }
          onChange={(event) => setPassword(event.target.value)}
        />
        {passwordHint !== undefined && passwordError === null && (
          <p className="field-hint" id={`${passwordId}-hint`}>
            {passwordHint}
          </p>
        )}
        {passwordError !== null && (
          <p className="field-error" id={`${passwordId}-error`} role="alert">
            {passwordError}
          </p>
        )}
      </div>

      <button type="submit" disabled={submitting}>
        {submitting ? '처리 중…' : submitLabel}
      </button>
    </form>
  )
}

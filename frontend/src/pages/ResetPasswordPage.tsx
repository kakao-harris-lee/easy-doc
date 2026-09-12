import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'

import { passwordResetRequest } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/context'
import { MIN_PASSWORD_LENGTH, validateEmail, validatePassword } from '../auth/validation'
import { Button } from '../components/ui/Button'
import { HOME_PATH, LOGIN_PATH } from '../routes/paths'

/** 코드 재요청 사이의 대기 시간(초). 서버는 요청마다 항상 202를 주므로(존재 은닉) 이
 * 대기 시간은 순수히 클라이언트가 정한 값이다 — `EmailVerificationPage`의 재발송
 * 쿨다운(60초, 서버가 실제로 거절하는 값)과 겉모양은 같지만 뜻은 다르다. */
const RESEND_COOLDOWN_SECONDS = 60

/** 인증 코드 길이. 계약 `PasswordResetConfirmRequest.code`(6자리 고정). */
const CODE_LENGTH = 6

const GENERIC_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 다시 시도해 주세요.'

type Step = 'request' | 'confirm'

/**
 * 비밀번호 재설정 화면(`/reset-password`, 2.19.0 신설, backlog §1.4 다음 조각).
 *
 * 인증 없이 접근한다 — 비밀번호를 잊은 사용자는 로그인할 수 없다. 2단계로 나뉜다.
 *
 * 1. 이메일만 받아 재설정 코드를 요청한다. 서버는 이메일 존재 여부와 무관하게 항상
 *    202를 준다(존재 은닉, `PasswordResetService.request` KDoc) — 그래서 이 화면도
 *    "코드를 보냈다"는 문구만 보여줄 뿐 성공·실패를 구분해 알리지 않는다.
 * 2. 이메일(이어받음)·코드·새 비밀번호·확인을 받아 재설정을 확인한다. 성공하면
 *    `login`과 같은 방식으로 토큰을 저장하고 홈으로 이동한다.
 *
 * 이미 로그인한 사용자가 들어오면 홈으로 보낸다 — 로그인 상태에서 비밀번호를 바꾸는
 * 자리는 계정 메뉴의 「비밀번호 만들기」(`SetPasswordForm`)이지 이 화면이 아니다.
 */
export function ResetPasswordPage() {
  const { status, completePasswordReset } = useAuth()
  const navigate = useNavigate()
  const emailId = useId()
  const codeId = useId()
  const passwordId = useId()
  const confirmId = useId()

  const [step, setStep] = useState<Step>('request')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  const [requesting, setRequesting] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [cooldown, setCooldown] = useState(0)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        clearInterval(timerRef.current)
      }
    }
  }, [])

  if (status === 'authenticated') {
    return <Navigate to={HOME_PATH} replace />
  }

  function startCooldown(seconds: number): void {
    if (timerRef.current !== null) {
      clearInterval(timerRef.current)
    }
    setCooldown(seconds)
    timerRef.current = setInterval(() => {
      setCooldown((current) => {
        if (current <= 1) {
          if (timerRef.current !== null) {
            clearInterval(timerRef.current)
            timerRef.current = null
          }
          return 0
        }
        return current - 1
      })
    }, 1000)
  }

  async function requestCode(nextEmail: string): Promise<void> {
    setError(null)
    setRequesting(true)
    try {
      await passwordResetRequest(nextEmail)
      setEmail(nextEmail)
      setStep('confirm')
      setNotice('코드를 보냈습니다. 메일함을 확인하세요.')
      startCooldown(RESEND_COOLDOWN_SECONDS)
    } catch (caught) {
      // 이전 성공 안내가 새 오류 위에 남아 있으면 안 된다 — 재요청이 실패했는데
      // "코드를 보냈습니다"가 그대로 보이는 상태를 막는다.
      setNotice(null)
      setError(caught instanceof ApiError ? caught.message : GENERIC_ERROR_MESSAGE)
    } finally {
      setRequesting(false)
    }
  }

  async function handleRequestSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    const emailError = validateEmail(email)
    if (emailError !== null) {
      setError(emailError)
      return
    }
    await requestCode(email.trim())
  }

  async function handleResend(): Promise<void> {
    if (cooldown > 0 || requesting) {
      return
    }
    await requestCode(email)
  }

  async function handleConfirmSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setNotice(null)
    const passwordError = validatePassword(newPassword)
    if (passwordError !== null) {
      setError(passwordError)
      return
    }
    if (newPassword !== confirmPassword) {
      setError('비밀번호가 서로 다릅니다')
      return
    }
    setError(null)
    setConfirming(true)
    try {
      await completePasswordReset(email, code, newPassword)
      navigate(HOME_PATH, { replace: true })
    } catch (caught) {
      // 401(오답·만료·시도 소진·모르는 이메일)은 계약이 사유를 구분하지 않는 고정
      // 문구를 준다 — 그대로 보여준다.
      setError(caught instanceof ApiError ? caught.message : GENERIC_ERROR_MESSAGE)
      setConfirming(false)
    }
  }

  return (
    <section
      className="mx-auto w-full max-w-[440px] rounded-[16px] border border-border bg-card p-6 shadow-card sm:p-8"
      aria-labelledby="reset-password-heading"
    >
      <h1
        id="reset-password-heading"
        className="text-[28px] font-extrabold leading-9 tracking-tight text-foreground"
      >
        비밀번호 재설정
      </h1>
      <p className="mt-2 text-sm leading-[22px] text-muted-foreground">
        {step === 'request'
          ? '가입한 이메일로 재설정 코드를 보내드립니다.'
          : `${email}(으)로 보낸 6자리 코드와 새 비밀번호를 입력해 주세요.`}
      </p>

      {notice !== null && (
        <p className="form-success mt-4" role="status">
          {notice}
        </p>
      )}
      {error !== null && (
        <p className="form-error mt-4" role="alert">
          {error}
        </p>
      )}

      {step === 'request' ? (
        <form
          className="mt-6 flex flex-col gap-4"
          onSubmit={(event) => void handleRequestSubmit(event)}
          noValidate
        >
          <div className="field">
            <label htmlFor={emailId}>이메일</label>
            <input
              className="h-11 w-full rounded-[10px] border border-input bg-card px-3.5 text-base text-foreground"
              id={emailId}
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>
          <Button type="submit" className="h-11" loading={requesting} fullWidth>
            {requesting ? '보내는 중…' : '재설정 코드 받기'}
          </Button>
        </form>
      ) : (
        <form
          className="mt-6 flex flex-col gap-4"
          onSubmit={(event) => void handleConfirmSubmit(event)}
          noValidate
        >
          <div className="field">
            <label htmlFor={emailId}>이메일</label>
            <input
              className="h-11 w-full rounded-[10px] border border-input bg-card px-3.5 text-base text-foreground"
              id={emailId}
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor={codeId}>인증 코드</label>
            <input
              className="h-11 w-full rounded-[10px] border border-input bg-card px-3.5 text-base tracking-[0.3em] text-foreground"
              id={codeId}
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={CODE_LENGTH}
              value={code}
              onChange={(event) =>
                setCode(event.target.value.replace(/\D/g, '').slice(0, CODE_LENGTH))
              }
            />
          </div>
          <div className="field">
            <label htmlFor={passwordId}>새 비밀번호</label>
            <input
              className="h-11 w-full rounded-[10px] border border-input bg-card px-3.5 text-base text-foreground"
              id={passwordId}
              type="password"
              autoComplete="new-password"
              value={newPassword}
              aria-describedby={`${passwordId}-hint`}
              onChange={(event) => setNewPassword(event.target.value)}
            />
            <p className="field-hint" id={`${passwordId}-hint`}>
              {MIN_PASSWORD_LENGTH}자 이상 입력해 주세요.
            </p>
          </div>
          <div className="field">
            <label htmlFor={confirmId}>새 비밀번호 확인</label>
            <input
              className="h-11 w-full rounded-[10px] border border-input bg-card px-3.5 text-base text-foreground"
              id={confirmId}
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
            />
          </div>
          <Button
            type="submit"
            className="h-11"
            loading={confirming}
            disabled={code.length !== CODE_LENGTH}
            fullWidth
          >
            {confirming ? '확인하는 중…' : '비밀번호 재설정'}
          </Button>
          <p className="text-center text-sm text-muted-foreground">
            {cooldown > 0 ? (
              <span>{cooldown}초 후 다시 보낼 수 있어요.</span>
            ) : (
              <button
                type="button"
                className="font-semibold text-primary underline underline-offset-4 disabled:cursor-not-allowed disabled:opacity-50"
                onClick={() => void handleResend()}
                disabled={requesting}
              >
                코드 다시 보내기
              </button>
            )}
          </p>
        </form>
      )}

      <p className="mt-5 text-center text-sm text-muted-foreground">
        <Link to={LOGIN_PATH}>로그인으로 돌아가기</Link>
      </p>
    </section>
  )
}

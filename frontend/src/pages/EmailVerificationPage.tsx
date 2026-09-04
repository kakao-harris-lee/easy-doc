import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'

import { confirmEmailVerification, requestEmailVerification } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/context'
import { Button } from '../components/ui/Button'
import { HOME_PATH } from '../routes/paths'

/** 코드 재발송 뒤 로컬로 시작하는 대기 시간(초). 계약 재발송 쿨다운(60초)과 같다. */
const RESEND_COOLDOWN_SECONDS = 60

/** 인증 코드 길이. 계약 `ConfirmEmailVerificationRequest.code`(6자리 고정). */
const CODE_LENGTH = 6

/** 서버 문구를 못 읽었을 때(네트워크 등)만 쓰는 문구. */
const GENERIC_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 다시 시도해 주세요.'

/**
 * 이메일 인증 화면(`/verify-email`).
 *
 * 가입(이메일·비밀번호) 직후 이 화면으로 온다. 코드는 가입 시점에 서버가 이미 보낸
 * 상태이므로 이 화면은 "다시 보내기"만 시작한다 — 마운트하자마자 재요청을 부르지 않는다.
 *
 * 이미 인증된 사용자가 들어오면 곧장 홈으로 보낸다(§요구사항 3). `RequireAuth`가 이
 * 화면을 감싸므로 미로그인 사용자는 여기 오기 전에 로그인 화면으로 걸러진다.
 */
export function EmailVerificationPage() {
  const { user, refreshMe } = useAuth()
  const navigate = useNavigate()
  const codeId = useId()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [resending, setResending] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const codeInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        clearInterval(timerRef.current)
      }
    }
  }, [])

  // 이 화면에 왔다는 것은 곧 할 일이 코드 입력이라는 뜻이다 — 네이티브 `autoFocus`
  // 대신 ref로 직접 옮긴다(UploadPage의 파일 카드 초점 이동과 같은 이유:
  // jsx-a11y/no-autofocus, 그리고 마운트 시점을 이 화면이 스스로 통제한다).
  useEffect(() => {
    codeInputRef.current?.focus()
  }, [])

  /** 재발송 대기 시간을 시작하고 1초마다 줄인다. */
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

  // 이미 인증된 계정(다른 탭에서 먼저 인증했거나, 소셜 로그인처럼 처음부터 인증된
  // 계정이 주소를 직접 친 경우)은 여기 머물 이유가 없다.
  if (user?.email_verified === true) {
    return <Navigate to={HOME_PATH} replace />
  }

  async function handleConfirm(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setError(null)
    setConfirming(true)
    try {
      await confirmEmailVerification(code)
      await refreshMe()
      navigate(HOME_PATH, { replace: true })
    } catch (caught) {
      // 409(이미 인증됨)는 오류로 보여주지 않는다 — 다른 경로로 이미 끝난 일이므로
      // 곧장 홈으로 보낸다. refreshMe로 email_verified를 먼저 맞춰 둔다.
      if (caught instanceof ApiError && caught.status === 409) {
        await refreshMe()
        navigate(HOME_PATH, { replace: true })
        return
      }
      // 400(오답·만료·무효)은 계약이 사유를 구분하지 않는 고정 문구를 준다 — 그대로 보여준다.
      setError(caught instanceof ApiError ? caught.message : GENERIC_ERROR_MESSAGE)
      setConfirming(false)
    }
  }

  async function handleResend(): Promise<void> {
    setError(null)
    setResending(true)
    try {
      await requestEmailVerification()
      startCooldown(RESEND_COOLDOWN_SECONDS)
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 429) {
        // 서버가 계산한 남은 시간을 그대로 쓴다 — 로컬 60초 고정값보다 정확하다.
        startCooldown(caught.retryAfterSeconds ?? RESEND_COOLDOWN_SECONDS)
      } else if (caught instanceof ApiError && caught.status === 409) {
        await refreshMe()
        navigate(HOME_PATH, { replace: true })
        return
      } else {
        setError(caught instanceof ApiError ? caught.message : GENERIC_ERROR_MESSAGE)
      }
    } finally {
      setResending(false)
    }
  }

  return (
    <section
      className="mx-auto w-full max-w-[440px] rounded-[16px] border border-border bg-card p-6 shadow-[0_8px_28px_rgba(35,31,70,0.06)] sm:p-8"
      aria-labelledby="verify-email-heading"
    >
      <h1
        id="verify-email-heading"
        className="text-[28px] font-extrabold leading-9 tracking-tight text-foreground"
      >
        이메일 인증
      </h1>
      <p className="mt-2 text-sm leading-[22px] text-muted-foreground">
        {user?.email}(으)로 보낸 6자리 인증 코드를 입력해 주세요.
      </p>

      {error !== null && (
        <p className="form-error mt-4" role="alert">
          {error}
        </p>
      )}

      <form
        className="mt-6 flex flex-col gap-4"
        onSubmit={(event) => void handleConfirm(event)}
        noValidate
      >
        <div className="field">
          <label htmlFor={codeId}>인증 코드</label>
          <input
            className="h-11 w-full rounded-[10px] border border-input bg-card px-3.5 text-base tracking-[0.3em] text-foreground"
            id={codeId}
            ref={codeInputRef}
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={CODE_LENGTH}
            value={code}
            aria-invalid={error !== null}
            onChange={(event) =>
              setCode(event.target.value.replace(/\D/g, '').slice(0, CODE_LENGTH))
            }
          />
        </div>
        <Button
          type="submit"
          className="h-11"
          loading={confirming}
          disabled={code.length !== CODE_LENGTH}
          fullWidth
        >
          {confirming ? '확인하는 중…' : '확인'}
        </Button>
      </form>

      <p className="mt-5 text-center text-sm text-muted-foreground">
        {cooldown > 0 ? (
          <span>{cooldown}초 후 다시 보낼 수 있어요.</span>
        ) : (
          <button
            type="button"
            className="font-semibold text-primary underline underline-offset-4 disabled:cursor-not-allowed disabled:opacity-50"
            onClick={() => void handleResend()}
            disabled={resending}
          >
            코드 다시 보내기
          </button>
        )}
      </p>
    </section>
  )
}

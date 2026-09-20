import { useId, useState } from 'react'

import { confirmPhoneVerification, requestPhoneVerification } from '../api/auth'
import { ApiError } from '../api/client'
import { ONE_TIME_CODE_LENGTH, sanitizeOneTimeCode } from '../auth/oneTimeCode'
import { DEFAULT_RESEND_COOLDOWN_SECONDS, useResendCooldown } from '../auth/useResendCooldown'
import { useAuth } from '../auth/context'
import { formatCredits } from '../lib/credits'
import { Button } from './ui/Button'

const SUBMIT_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 계정 설정 화면의 휴대폰 번호 인증 절. `AccountSettingsPage`에서 분리했다
 * (`docs/plans/2026-09-16-phone-verification-cleanup.md` §3).
 *
 * 재발송에 `EmailVerificationPage`와 같은 쿨다운 훅을 쓴다 — 이번 정리에서 유일한 동작
 * 변화다. 429여도 오류 문구는 보여주지 않고(이메일 화면과 같은 규칙) 대기 시간만
 * 갱신한다.
 */
export function PhoneVerificationSection() {
  const { user, refreshMe } = useAuth()

  const [phoneNumber, setPhoneNumber] = useState('')
  const [phoneCode, setPhoneCode] = useState('')
  const [phoneCodeSent, setPhoneCodeSent] = useState(false)
  const [phoneBusy, setPhoneBusy] = useState(false)
  const [phoneMessage, setPhoneMessage] = useState<string | null>(null)
  const [phoneError, setPhoneError] = useState<string | null>(null)
  const { cooldown, startCooldown } = useResendCooldown()

  const phoneNumberId = useId()
  const phoneCodeId = useId()

  async function sendPhoneCode(): Promise<void> {
    if (phoneBusy) return
    setPhoneBusy(true)
    setPhoneError(null)
    setPhoneMessage(null)
    try {
      await requestPhoneVerification(phoneNumber)
      setPhoneCodeSent(true)
      setPhoneMessage('인증번호를 보냈습니다. 5분 안에 입력해 주세요.')
      startCooldown(DEFAULT_RESEND_COOLDOWN_SECONDS)
    } catch (caught: unknown) {
      if (caught instanceof ApiError && caught.status === 429) {
        // 서버가 계산한 남은 시간을 그대로 쓴다 — 로컬 60초 고정값보다 정확하다. 이메일
        // 화면과 같은 규칙으로 오류 문구는 보여주지 않는다.
        startCooldown(caught.retryAfterSeconds ?? DEFAULT_RESEND_COOLDOWN_SECONDS)
      } else {
        setPhoneError(caught instanceof ApiError ? caught.message : SUBMIT_ERROR_MESSAGE)
      }
    } finally {
      setPhoneBusy(false)
    }
  }

  async function verifyPhoneCode(): Promise<void> {
    if (phoneBusy) return
    setPhoneBusy(true)
    setPhoneError(null)
    try {
      const result = await confirmPhoneVerification(phoneCode)
      await refreshMe()
      setPhoneMessage(
        result.granted_credits > 0
          ? `휴대폰 인증이 완료되어 체험용 ${formatCredits(result.granted_credits)}크레딧을 드렸습니다.`
          : '휴대폰 인증이 완료되었습니다. 이 번호의 체험 크레딧은 이미 지급된 적이 있습니다.',
      )
      setPhoneCode('')
    } catch (caught: unknown) {
      setPhoneError(caught instanceof ApiError ? caught.message : SUBMIT_ERROR_MESSAGE)
    } finally {
      setPhoneBusy(false)
    }
  }

  if (user === null) {
    return null
  }

  return (
    <section
      className="rounded-[12px] border border-border bg-card p-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]"
      aria-labelledby="phone-verification-heading"
    >
      <h2 id="phone-verification-heading" className="text-[15px] font-semibold text-foreground">
        3. 휴대폰 번호 인증
      </h2>
      {user.phone_verified ? (
        <p className="mt-2 text-sm text-foreground">
          인증이 완료되었습니다. 결제를 이용할 수 있습니다.
        </p>
      ) : !user.email_verified ? (
        <p className="mt-2 text-sm text-muted-foreground">
          이메일 인증을 완료한 뒤 휴대폰 번호를 인증할 수 있습니다.
        </p>
      ) : (
        <div className="mt-4 flex max-w-md flex-col gap-4">
          <p className="text-sm leading-[22px] text-muted-foreground">
            가입과 로그인 다음 단계입니다. 인증번호를 요청하고 확인해 주세요.
          </p>
          <div className="field">
            <label htmlFor={phoneNumberId}>휴대폰 번호</label>
            <input
              id={phoneNumberId}
              type="tel"
              inputMode="tel"
              autoComplete="tel-national"
              placeholder="010-1234-5678"
              value={phoneNumber}
              disabled={phoneBusy}
              onChange={(event) => setPhoneNumber(event.target.value)}
            />
            <p className="field-hint">국내 010 번호만 지원합니다.</p>
          </div>
          <Button
            type="button"
            disabled={phoneBusy || phoneNumber.trim() === '' || cooldown > 0}
            onClick={() => void sendPhoneCode()}
          >
            {phoneBusy ? '처리 중…' : phoneCodeSent ? '인증번호 다시 받기' : '인증번호 받기'}
          </Button>
          {cooldown > 0 && (
            <p className="text-xs text-muted-foreground">{cooldown}초 후 다시 보낼 수 있어요.</p>
          )}
          {phoneCodeSent && (
            <div className="field">
              <label htmlFor={phoneCodeId}>인증번호</label>
              <input
                id={phoneCodeId}
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={ONE_TIME_CODE_LENGTH}
                value={phoneCode}
                disabled={phoneBusy}
                onChange={(event) => setPhoneCode(sanitizeOneTimeCode(event.target.value))}
              />
              <Button
                type="button"
                className="mt-3"
                disabled={phoneBusy || phoneCode.length !== ONE_TIME_CODE_LENGTH}
                onClick={() => void verifyPhoneCode()}
              >
                인증 완료
              </Button>
            </div>
          )}
        </div>
      )}
      {phoneMessage !== null && (
        <p className="mt-3 text-sm text-foreground" role="status">
          {phoneMessage}
        </p>
      )}
      {phoneError !== null && (
        <p className="form-error mt-3" role="alert">
          {phoneError}
        </p>
      )}
      {!user.phone_verified && user.email_verified && (
        <p className="mt-3 text-xs text-muted-foreground">
          휴대폰 번호를 최초로 인증하면 샘플 변환용 체험 5크레딧을 한 번 지급합니다.
        </p>
      )}
    </section>
  )
}

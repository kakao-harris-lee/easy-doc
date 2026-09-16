import { useCallback, useEffect, useRef, useState } from 'react'

/** 재발송 뒤 로컬로 시작하는 기본 대기 시간(초). 계약 재발송 쿨다운(60초)과 같다. */
export const DEFAULT_RESEND_COOLDOWN_SECONDS = 60

interface UseResendCooldownResult {
  /** 남은 대기 시간(초). 0이면 다시 보낼 수 있다는 뜻이다. */
  cooldown: number
  /** 대기 시간을 시작하고 1초마다 줄인다. 이미 도는 타이머가 있으면 새 값으로 교체한다. */
  startCooldown: (seconds: number) => void
}

/**
 * 인증 코드 재발송 대기 시간을 세는 훅.
 *
 * `EmailVerificationPage`와 `PhoneVerificationSection`이 같은 카운트다운 로직(1초마다
 * 감소, 재시작 시 기존 타이머 교체, 언마운트 시 정리)을 공유한다.
 */
export function useResendCooldown(): UseResendCooldownResult {
  const [cooldown, setCooldown] = useState(0)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        clearInterval(timerRef.current)
      }
    }
  }, [])

  const startCooldown = useCallback((seconds: number) => {
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
  }, [])

  return { cooldown, startCooldown }
}

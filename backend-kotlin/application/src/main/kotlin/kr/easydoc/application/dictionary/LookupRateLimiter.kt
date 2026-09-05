package kr.easydoc.application.dictionary

import java.util.UUID

/**
 * 사전 조회 남용 한도 (P0-5 조각 4, 계획 §3.4).
 *
 * 사용자별 분당 호출 수를 제한한다 — 목적은 사전 전량 긁기를 늦추는 것이다. 계획이
 * 명시적으로 **인스턴스별(process-local) 카운터**를 요구하므로(§3.4 "프로세스 내
 * 카운터라 인스턴스별 한도이며 계약에 그렇게 적는다"), `EmailVerificationService`의
 * 쿨다운([kr.easydoc.application.auth.VerificationCodeStore], DB 백엔드)과 달리 이
 * 포트에는 저장소 대신 어댑터가 프로세스 메모리에 상태를 든다.
 *
 * 초과 시 [kr.easydoc.core.exceptions.RateLimitedException] 을 던진다 —
 * `GlobalExceptionHandler` 가 그대로 429 + `Retry-After` 로 옮긴다.
 */
fun interface LookupRateLimiter {
    fun checkAndRecord(userId: UUID)
}

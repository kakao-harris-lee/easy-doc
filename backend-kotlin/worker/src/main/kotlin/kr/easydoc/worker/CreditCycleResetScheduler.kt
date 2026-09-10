package kr.easydoc.worker

import kr.easydoc.application.credit.ResetCreditCycles
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 주기가 끝난 크레딧 계정을 매일 초기화한다. 기본 시각은 매일 03:30 이다 —
 * `RetentionPurgeScheduler`(03:00)와 겹치지 않게 살짝 늦춘다(같은 시각에 두 배치가
 * DB 부하를 함께 주지 않도록).
 *
 * 크레딧을 「구독 주기에 포함된 이용량」으로 바꾼 사용자 결정(2026-09-10)의 구현 —
 * `RetentionPurgeScheduler`와 다른 스케줄러로 둔 이유: 이 배치는 보존 파기(개인정보·PII
 * 정리)가 아니라 크레딧 원장(청구 근거) 갱신이라 도메인이 다르다. 실패 격리는
 * [ResetCreditCycles] 자신의 예외 경계로 충분하다 — 이 스케줄러가 부르는 유스케이스는
 * 하나뿐이라 `RetentionPurgeScheduler.runStep` 같은 단계별 격리가 필요 없다.
 */
@Component
class CreditCycleResetScheduler(private val resetCreditCycles: ResetCreditCycles) {
    private val log = LoggerFactory.getLogger(CreditCycleResetScheduler::class.java)

    @Scheduled(cron = "\${easydoc.credits.cycle-reset.cron:0 30 3 * * *}")
    @Suppress("TooGenericExceptionCaught")
    fun run() {
        try {
            resetCreditCycles.run()
        } catch (failure: RuntimeException) {
            // 메시지만 남긴다 — `RetentionPurgeScheduler.runStep` 과 같은 판단(스택트레이스에
            // 무엇이 실릴지 이 메서드가 보장할 수 없다).
            log.error("크레딧 주기 초기화 실패: message={}", failure.message)
        }
    }
}

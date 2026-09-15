package kr.easydoc.worker

import kr.easydoc.application.credit.ResetCreditCycles
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 주기가 끝난 크레딧 계정을 매일 03:30에 초기화한다.
 * 보존 파기 작업과 실행 시각을 분리해 동시에 DB 부하를 만들지 않는다.
 */
@Component
@Profile(WORKER_PROFILE)
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

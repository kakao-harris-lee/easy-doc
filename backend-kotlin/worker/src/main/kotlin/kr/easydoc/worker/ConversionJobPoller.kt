package kr.easydoc.worker

import kr.easydoc.application.conversion.ConversionJobOutcome
import kr.easydoc.application.conversion.ProcessConversionJob
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** `conversion_jobs` 를 주기적으로 집어 한 건씩 처리한다. */
@Component
class ConversionJobPoller(private val jobs: ProcessConversionJob) {
    private val log = LoggerFactory.getLogger(ConversionJobPoller::class.java)

    @Scheduled(fixedDelayString = "\${easydoc.worker.poll-interval-ms:500}")
    fun poll() {
        val outcome = jobs.processNext()
        if (outcome != ConversionJobOutcome.IDLE) {
            log.info("변환 작업 처리: outcome={}", outcome)
        }
    }
}

package kr.easydoc.worker

import kr.easydoc.application.actionguide.ActionGuideJobOutcome
import kr.easydoc.application.actionguide.ProcessActionGuideJob
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Worker가 켜져 있으면 intake 설정에 따라 행동 안내 작업을 처리하거나 안전하게 비운다. */
@Component
@Profile(WORKER_PROFILE)
@ConditionalOnBean(ProcessActionGuideJob::class)
@ConditionalOnProperty(prefix = "easydoc.action-guide", name = ["worker-enabled"], havingValue = "true")
class ActionGuideJobPoller(
    private val jobs: ProcessActionGuideJob,
    @param:Value("\${easydoc.action-guide.enabled:false}") private val intakeEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(ActionGuideJobPoller::class.java)

    @Scheduled(fixedDelayString = "\${easydoc.action-guide.poll-interval-ms:500}")
    fun poll() {
        val outcome = if (intakeEnabled) jobs.processNext() else jobs.drainNext()
        when (outcome) {
            ActionGuideJobOutcome.IDLE, ActionGuideJobOutcome.COMPLETED -> Unit
            else -> log.info("행동 안내 작업 처리 결과: {}", outcome)
        }
    }
}

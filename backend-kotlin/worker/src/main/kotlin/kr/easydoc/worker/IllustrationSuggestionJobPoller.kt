package kr.easydoc.worker

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobOutcome
import kr.easydoc.application.illustration.suggestion.ProcessIllustrationSuggestionJob
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Worker 가 켜져 있으면 intake 설정에 따라 그림 제안 작업을 처리하거나 안전하게 비운다. */
@Component
@Profile(WORKER_PROFILE)
@ConditionalOnProperty(prefix = "easydoc.illustration-suggestions", name = ["worker-enabled"], havingValue = "true")
class IllustrationSuggestionJobPoller(
    private val jobs: ProcessIllustrationSuggestionJob,
    @param:Value("\${easydoc.illustration-suggestions.enabled:false}") private val intakeEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(IllustrationSuggestionJobPoller::class.java)

    @Scheduled(fixedDelayString = "\${easydoc.illustration-suggestions.poll-interval-ms:500}")
    fun poll() {
        val outcome = if (intakeEnabled) jobs.processNext() else jobs.drainNext()
        when (outcome) {
            IllustrationSuggestionJobOutcome.IDLE, IllustrationSuggestionJobOutcome.COMPLETED -> Unit
            else -> log.info("그림 제안 작업 처리 결과: {}", outcome)
        }
    }
}

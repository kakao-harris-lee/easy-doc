package kr.easydoc.worker

import kr.easydoc.application.illustration.generation.IllustrationGenerationJobOutcome
import kr.easydoc.application.illustration.generation.ProcessIllustrationGenerationJob
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@Profile(WORKER_PROFILE)
@ConditionalOnProperty(prefix = "easydoc.illustration-generation", name = ["worker-enabled"], havingValue = "true")
class IllustrationGenerationJobPoller(
    private val jobs: ProcessIllustrationGenerationJob,
    @param:Value("\${easydoc.illustration-generation.enabled:false}") private val intakeEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(IllustrationGenerationJobPoller::class.java)

    @Scheduled(fixedDelayString = "\${easydoc.illustration-generation.poll-interval-ms:500}")
    fun poll() {
        val outcome = if (intakeEnabled) jobs.processNext() else jobs.drainNext()
        when (outcome) {
            IllustrationGenerationJobOutcome.IDLE, IllustrationGenerationJobOutcome.COMPLETED -> Unit
            else -> log.info("그림 생성 작업 처리 결과: {}", outcome)
        }
    }
}

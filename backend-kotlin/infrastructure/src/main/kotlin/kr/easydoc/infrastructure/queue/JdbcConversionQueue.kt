package kr.easydoc.infrastructure.queue

import kr.easydoc.application.document.ConversionQueue
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** `conversion_jobs` 테이블에 작업을 등록한다. 스키마는 `V5__conversion_jobs.sql`. */
class JdbcConversionQueue(private val jdbc: JdbcClient) : ConversionQueue {
    override fun enqueue(conversionId: UUID) {
        jdbc
            .sql(
                """
                INSERT INTO conversion_jobs (conversion_id, state, attempts, next_attempt_at)
                VALUES (:conversionId, :state, 0, now())
                ON CONFLICT (conversion_id) DO NOTHING
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("state", READY_STATE)
            .update()
    }

    companion object {
        /** 갓 등록된 작업의 상태. `V5` 의 `ck_conversion_jobs_state_valid` 목록 안이다. */
        const val READY_STATE: String = "ready"
    }
}

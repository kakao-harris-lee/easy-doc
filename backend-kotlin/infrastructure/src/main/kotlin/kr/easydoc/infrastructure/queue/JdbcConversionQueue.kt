package kr.easydoc.infrastructure.queue

import kr.easydoc.application.document.ConversionQueue
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * `conversion_jobs` 테이블에 작업을 등록한다. 스키마는 `V5__conversion_jobs.sql`.
 *
 * ## 등록이 저장과 같은 트랜잭션이다
 *
 * 호출자(`DocumentService.store`)의 트랜잭션 안에서 돈다. 문서·변환·작업 세 행이 함께
 * 확정되므로 **문서만 있고 작업이 없는 상태가 구조적으로 생기지 않는다**(계획 §4.4).
 * 원본이 `commit → enqueue` 순서를 지켰던 이유(워커가 다른 저장소를 보므로 커밋 전에 넣으면
 * 아직 없는 행을 읽으러 간다)가 큐를 같은 DB 로 옮기면서 사라졌다 — 같은 트랜잭션 안의
 * 작업 행은 커밋 전에는 다른 세션에 보이지 않는다.
 *
 * ## 등록은 멱등하다
 *
 * `ON CONFLICT (conversion_id) DO NOTHING`. 작업 식별자가 변환 식별자이므로 같은 변환을 두
 * 번 등록해도 작업은 하나다 — 계약이 이미 그렇게 적었다. 재시도가 같은 작업을 두 번 넣지
 * 않는 것이 요점이고, 그것이 **중복 LLM 호출**(§5 Phase 7 즉시 중단 기준)을 막는 첫 겹이다.
 *
 * ## 상태·시각을 명시적으로 적는다
 *
 * `state`·`attempts`·`next_attempt_at` 에 DEFAULT 를 두지 않고 INSERT 가 적는다. `V3` 가
 * 봉투 두 값에서 얻은 교훈과 같다 — 쓰는 쪽이 값을 적지 않아도 되는 구조는, 그 값이
 * 데이터에 대해 거짓일 때 조용하다. `next_attempt_at` 은 **DB 시계**(`now()`)로 찍는다:
 * 앱 시계는 프로세스마다 어긋나고, 그 어긋남이 곧 "아직 집을 때가 아니다"의 오판이 된다.
 */
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
        /**
         * 갓 등록된 작업의 상태. `V5` 의 `ck_conversion_jobs_state_valid` 목록 안이다.
         *
         * 나머지 상태(`leased`·`done`·`failed`)를 여기 열거하지 않는다 — 그것들을 쓰는
         * 코드는 Phase 5 의 worker 이고, 쓰지 않는 상수를 미리 두면 「어디서 쓰이는가」가
         * 흐려진다(`WorkspaceMessages.kt` 의 같은 판단).
         */
        const val READY_STATE: String = "ready"
    }
}

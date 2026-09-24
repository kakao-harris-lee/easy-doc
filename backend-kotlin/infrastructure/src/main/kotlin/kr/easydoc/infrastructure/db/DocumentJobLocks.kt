package kr.easydoc.infrastructure.db

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * **여러 문서를 한 문장으로 지우기 전에 그 배치의 활성 작업을 모두 잠근다.**
 *
 * 문서 삭제 정산은 V35 의 `settle_document_jobs_before_delete` 가 하지만 그것은 **행 단위**
 * trigger 라 `OLD.id` 의 작업만 미리 잠근다. 탈퇴(`JdbcAccountDeletionRepository.deleteUser`)나
 * 보존 만료 파기처럼 한 문장이 문서 여럿을 지우면, 앞 문서를 정산하며 `workspace_credit_accounts`
 * 를 쥔 채 뒤 문서의 작업 행을 기다리게 된다. 그 작업을 정산 중인 worker 는 「작업 행 → 계정」
 * 순서라 사이클이 닫히고, PostgreSQL 이 둘 중 하나를 죽인다 — 파기 배치 전체가 되돌려지거나
 * 이미 돈을 쓴 정산이 사라진다.
 *
 * 그래서 삭제 **전에** 배치 전체의 활성 작업 행을 worker 와 같은 방향(작업 행 → 계정)으로,
 * 표 이름 순서와 `id` 순서로 잠근다. trigger 의 문서별 잠금은 그대로 둔다 — 이미 이 트랜잭션이
 * 쥔 행을 다시 잠그는 것이라 기다리지 않고, 문서 한 건만 지우는 경로는 그 잠금만으로 안전하다.
 */
object DocumentJobLocks {
    /**
     * 잠글 작업 표. 순서는 `settle_document_jobs_before_delete` 와 같은 표 이름 순이고, 표
     * 안에서는 `id` 순이다 — 두 파기 배치가 서로 맞물려도 같은 방향으로 줄을 선다.
     */
    private val JOB_TABLES = listOf("action_guide_jobs", "illustration_suggestion_jobs")

    fun lockActiveJobs(
        jdbc: JdbcClient,
        documentIds: Collection<UUID>,
    ) {
        if (documentIds.isEmpty()) return
        val ids = documentIds.toList()
        JOB_TABLES.forEach { table ->
            jdbc
                .sql(lockSql(table))
                .param("documentIds", ids)
                .query { rs, _ -> rs.getObject("id", UUID::class.java) }
                .list()
        }
    }

    /** 표 이름은 [JOB_TABLES] 의 상수라 문자열로 끼워 넣는다 — 문서 id 만 매개변수다. */
    private fun lockSql(table: String): String =
        """
        SELECT id
        FROM $table
        WHERE document_id IN (:documentIds)
          AND status IN ('queued', 'running')
        ORDER BY id
        FOR UPDATE
        """.trimIndent()
}

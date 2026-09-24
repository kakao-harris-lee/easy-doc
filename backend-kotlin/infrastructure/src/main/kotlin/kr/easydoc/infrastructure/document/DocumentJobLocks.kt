package kr.easydoc.infrastructure.document

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 문서 여러 건을 한 문장으로 지우기 **직전에**, 그 문서들이 걸린 활성 작업 행을 한 번에 잠근다.
 *
 * 문서 삭제 trigger(V29 `settle_action_guide_jobs_for_document`)는 **행마다** 돈다 — 문서 A 의
 * 작업 행을 잠그고 이용량 계정을 갱신한 **뒤에야** 문서 B 의 작업 행을 잠그러 간다. 그래서
 * `DELETE ... WHERE id IN (A, B)` 한 문장 안에서 순서가 갈린다: B 의 작업을 정산 중인 worker 가
 * 그 행을 쥔 채 계정을 기다리고 있으면, 삭제는 A 때문에 이미 계정을 든 채 B 의 행을 기다린다.
 * 두 트랜잭션이 서로를 기다려 교착하고, 정산이 죽으면 **이미 돈을 쓴 호출의 결과가 사라진다.**
 *
 * 배치의 활성 작업 행을 **계정보다 먼저, 한 번에** 잠그면 그 창이 닫힌다 — 삭제가 계정에 닿는
 * 시점에는 배치의 모든 작업 행을 이미 들고 있으므로 trigger 가 중간에 새로 기다릴 일이 없다.
 * `ORDER BY id` 는 배치끼리도 같은 순서로 잡게 해 파기 배치 두 개가 엇갈리지 않게 한다.
 *
 * 잠그는 표는 행동 안내와 그림 제안 **둘 다**이고 순서는 V35 의 합친 trigger
 * (`settle_document_jobs_before_delete`)와 같은 표 이름 순이다 — 한 문서에 두 가족이 동시에
 * 활성일 수 있어 한쪽만 잠그면 나머지 가족을 정산 중인 worker 와 같은 교착이 남는다. 「어떤 작업
 * 표를 미리 잠가야 하는가」를 한곳에 모으는 것이 이 클래스의 존재 이유다.
 *
 * 문서 한 건만 지우는 경로(`DocumentService.delete`)는 trigger 가 그 한 행을 스스로 먼저
 * 잠그므로 여기를 거칠 필요가 없다 — 대신 그 경로는 계정 갱신을 삭제 뒤로 둔다.
 */
class DocumentJobLocks(private val jdbc: JdbcClient) {
    /**
     * [documentIds] 에 걸린 `queued`/`running` 작업 행을 두 가족 모두, 표 이름 순서와 id 순서로
     * 잠근다. 비어 있으면 아무것도 하지 않는다.
     */
    fun lockActiveJobs(documentIds: List<UUID>) {
        if (documentIds.isEmpty()) return
        JOB_TABLES.forEach { table -> lockActiveJobsIn(table, documentIds) }
    }

    private fun lockActiveJobsIn(
        table: String,
        documentIds: List<UUID>,
    ) {
        val statement =
            documentIds.foldIndexed(jdbc.sql(lockActiveJobsSql(table, documentIds.size))) { index, spec, id ->
                spec.param(idParam(index), id)
            }
        statement.query { rs, _ -> rs.getObject(1, UUID::class.java) }.list()
    }

    private companion object {
        /**
         * 잠글 작업 표와 그 순서. V35 의 `settle_document_jobs_before_delete` 가 도는 순서와 같게
         * 둔다 — 두 파기·탈퇴 배치가 엇갈리지 않으려면 방향이 하나여야 한다.
         */
        val JOB_TABLES = listOf("action_guide_jobs", "illustration_suggestion_jobs")

        fun idParam(index: Int): String = "id$index"

        /**
         * `= ANY(:ids)` 가 아니라 자리표시자를 펼친다 — `NamedParameterJdbcTemplate` 은 배열·컬렉션
         * 인자를 `?, ?` 로 펼쳐 버려 `ANY` 의 괄호와 맞지 않는다(같은 파일의 다른 질의도 같은 이유).
         * 표 이름은 [JOB_TABLES] 의 상수라 그대로 끼워 넣는다 — 매개변수는 문서 id 뿐이다.
         */
        fun lockActiveJobsSql(
            table: String,
            size: Int,
        ): String {
            val placeholders = (0 until size).joinToString { ":${idParam(it)}" }
            return """
                SELECT id
                FROM $table
                WHERE document_id IN ($placeholders)
                  AND status IN ('queued', 'running')
                ORDER BY id
                FOR UPDATE
                """.trimIndent()
        }
    }
}

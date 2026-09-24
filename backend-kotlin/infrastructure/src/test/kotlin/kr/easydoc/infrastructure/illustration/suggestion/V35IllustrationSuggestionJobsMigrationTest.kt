package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** R7 ER-17 작업 큐가 DB 단독으로 지켜야 하는 동시성·과금 경계를 확인한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V35IllustrationSuggestionJobsMigrationTest {
    private val database: DatabaseHandle by lazy {
        PostgresTestSupport.createEmptyDatabase("v35_illustration_suggestion_jobs").also { handle ->
            Flyway
                .configure()
                .dataSource(handle.jdbcUrl, handle.username, handle.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }

    @Test
    @DisplayName("V35는 제안 작업·결과 표와 job 단위 호출·이용량 유일성 제약을 만든다")
    fun `그림 제안 스키마를 만든다`() {
        val indexes =
            database.queryFirstColumn(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN (
                      'illustration_suggestion_jobs', 'illustration_suggestion_results',
                      'credit_transactions', 'llm_calls'
                  )
                ORDER BY indexname
                """.trimIndent(),
            )

        assertThat(indexes).contains(
            "uq_illustration_suggestion_jobs_active_document",
            "uq_illustration_suggestion_jobs_active_owner",
            "uq_illustration_suggestion_jobs_running_slot",
            "uq_illustration_suggestion_results_job",
            "uq_credit_transactions_illustration_suggestion_reserve",
            "uq_credit_transactions_illustration_suggestion_terminal",
            "uq_llm_calls_illustration_suggestion_job",
        )
    }

    @Test
    @DisplayName("원장 CHECK 가 새 사유·목적을 받고, purpose 열이 그 값을 담을 만큼 넓다")
    fun `원장 어휘가 새 값을 받는다`() {
        assertThat(constraint("ck_credit_transactions_reason_valid")).contains("illustration_suggestion")
        assertThat(constraint("ck_llm_calls_purpose_valid")).contains("illustration_suggestion")
        // CHECK 만 넓히고 열 폭을 두면 값이 제약을 통과하고도 길이에서 거절된다.
        assertThat(columnLength("llm_calls", "purpose"))
            .isGreaterThanOrEqualTo("illustration_suggestion".length)
    }

    @Test
    @DisplayName("예약 0인 작업의 정산 값은 not_charged 하나뿐이다 — 무과금과 반환을 뭉개지 않는다")
    fun `무과금 작업의 정산 값이 고정이다`() {
        val settlement = constraint("ck_illustration_suggestion_jobs_terminal_settlement")

        assertThat(settlement).contains("not_charged")
        assertThat(constraint("ck_illustration_suggestion_jobs_settlement_valid"))
            .contains("reserved", "consumed", "released", "not_charged")
    }

    @Test
    @DisplayName("문서 삭제 정산 trigger 는 두 작업 가족을 함께 도는 **하나**다 — 가족마다 두면 교착한다")
    fun `문서 삭제 trigger 가 하나로 합쳐져 있다`() {
        val triggers =
            database.queryFirstColumn(
                """
                SELECT tgname FROM pg_trigger
                WHERE tgrelid = 'documents'::regclass AND NOT tgisinternal
                ORDER BY tgname
                """.trimIndent(),
            )

        assertThat(triggers)
            .describedAs("문서 삭제 정산 trigger 가 없다 — 예약이 문서와 함께 사라진다")
            .contains("trg_documents_settle_jobs")
        assertThat(triggers)
            .withFailMessage {
                "가족별 정산 trigger 가 남아 있다: $triggers\n" +
                    "  PostgreSQL 은 BEFORE DELETE trigger 를 이름 순서로 돈다 — 앞선 trigger 가 이용량\n" +
                    "  계정을 잡은 뒤에야 다음 가족의 작업을 잠그므로 그 가족을 정산 중인 worker 와 교착한다."
            }.doesNotContain(
                "trg_documents_settle_action_guide_jobs",
                "trg_documents_settle_illustration_suggestion_jobs",
            )
    }

    @Test
    @DisplayName("합친 trigger 가 두 가족의 정산 함수를 모두 부른다 — 한쪽만 부르면 예약이 남는다")
    fun `합친 trigger 가 두 정산을 모두 부른다`() {
        val body =
            database
                .queryFirstColumn(
                    "SELECT prosrc FROM pg_proc WHERE proname = 'settle_document_jobs_before_delete'",
                ).single()

        assertThat(body).contains("settle_action_guide_jobs_for_document")
        assertThat(body).contains("settle_illustration_suggestion_jobs_for_document")
        // 계정을 건드리기 전에 두 가족의 행을 먼저 잠근다.
        assertThat(body.indexOf("FROM action_guide_jobs")).isLessThan(body.indexOf("settle_action_guide_jobs_for_"))
        assertThat(body.indexOf("FROM illustration_suggestion_jobs"))
            .isLessThan(body.indexOf("settle_action_guide_jobs_for_"))
    }

    private fun constraint(name: String): String =
        database
            .queryFirstColumn(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '$name'",
            ).single()

    private fun columnLength(
        table: String,
        column: String,
    ): Int =
        database
            .queryFirstColumn(
                """
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '$table' AND column_name = '$column'
                """.trimIndent(),
            ).single()
            .toInt()
}

package kr.easydoc.infrastructure.actionguide

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** R2 작업 큐가 DB 단독으로 지켜야 하는 동시성·과금 경계를 확인한다. */
class V29ActionGuideJobsMigrationTest {
    @Test
    @DisplayName("V29는 행동 안내 작업과 job 단위 호출·크레딧 유일성 제약을 만든다")
    fun `행동 안내 작업 스키마를 만든다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v29_action_guide_jobs")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val indexes =
            database.queryFirstColumn(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('action_guide_jobs', 'credit_transactions', 'llm_calls')
                ORDER BY indexname
                """.trimIndent(),
            )

        assertThat(indexes).contains(
            "uq_action_guide_jobs_active_document",
            "uq_action_guide_jobs_active_owner",
            "uq_action_guide_jobs_running_slot",
            "uq_credit_transactions_action_guide_reserve",
            "uq_credit_transactions_action_guide_terminal",
            "uq_llm_calls_action_guide_job",
        )

        val reasonConstraint = constraint(database, "ck_credit_transactions_reason_valid")
        val purposeConstraint = constraint(database, "ck_llm_calls_purpose_valid")
        val outcomeConstraint = constraint(database, "ck_llm_calls_outcome_valid")

        assertThat(reasonConstraint).contains("action_guide")
        assertThat(purposeConstraint).contains("action_guide")
        assertThat(outcomeConstraint).contains("in_progress", "outcome_unknown")
    }

    private fun constraint(
        database: kr.easydoc.infrastructure.DatabaseHandle,
        name: String,
    ): String =
        database
            .queryFirstColumn(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = '$name'
                """.trimIndent(),
            ).single()
}

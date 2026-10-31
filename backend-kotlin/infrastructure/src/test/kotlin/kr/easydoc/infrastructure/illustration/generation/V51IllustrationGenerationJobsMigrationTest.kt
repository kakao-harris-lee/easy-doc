package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V51IllustrationGenerationJobsMigrationTest {
    private val database: DatabaseHandle by lazy {
        PostgresTestSupport.createEmptyDatabase("v51_illustration_generation_jobs").also { handle ->
            Flyway
                .configure()
                .dataSource(handle.jdbcUrl, handle.username, handle.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }

    @Test
    fun `V51은 생성 작업과 자산 원장의 필수 제약을 만든다`() {
        val indexes =
            database.queryFirstColumn(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN (
                      'illustration_generation_jobs', 'generated_illustration_assets',
                      'credit_transactions', 'llm_calls'
                  )
                ORDER BY indexname
                """.trimIndent(),
            )
        assertThat(indexes).contains(
            "uq_illustration_generation_jobs_active_document",
            "uq_illustration_generation_jobs_active_owner",
            "uq_illustration_generation_jobs_running_slot",
            "uq_generated_illustration_assets_job",
            "uq_credit_transactions_illustration_generation_reserve",
            "uq_credit_transactions_illustration_generation_terminal",
            "uq_llm_calls_illustration_generation_job",
        )
        assertThat(constraint("ck_illustration_generation_jobs_provider_attempts")).contains("0", "1")
        assertThat(constraint("ck_generated_illustration_assets_png_size")).contains("8388608")
        assertThat(constraint("ck_generated_illustration_assets_dimensions")).contains("1024")
        assertThat(constraint("ck_credit_transactions_reason_valid")).contains("illustration_generation")
        assertThat(constraint("ck_llm_calls_purpose_valid")).contains("illustration_generation")
    }

    @Test
    fun `문서 삭제 trigger는 생성 정산과 입력 파기를 기존 두 가족과 함께 수행한다`() {
        val body =
            database
                .queryFirstColumn(
                    "SELECT prosrc FROM pg_proc WHERE proname = 'settle_document_jobs_before_delete'",
                ).single()
        assertThat(body).contains(
            "settle_action_guide_jobs_for_document",
            "settle_illustration_generation_jobs_for_document",
            "settle_illustration_suggestion_jobs_for_document",
        )
        val settlement =
            database
                .queryFirstColumn(
                    "SELECT prosrc FROM pg_proc WHERE proname = 'settle_illustration_generation_jobs_for_document'",
                ).single()
        assertThat(settlement).contains("input_payload_encrypted = NULL", "outcome_unknown")
    }

    private fun constraint(name: String): String =
        database
            .queryFirstColumn(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '$name'",
            ).single()
}

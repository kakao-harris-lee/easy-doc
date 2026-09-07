package kr.easydoc.infrastructure.admin

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** `workspaces` × `users` 검색 — 실제 PostgreSQL 에서만 잴 수 있는 것들. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAdminWorkspaceQueryRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcAdminWorkspaceQueryRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("admin_workspace_query_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(dataSource())
        repository = JdbcAdminWorkspaceQueryRepository(jdbc)
    }

    @Test
    @DisplayName("q 없이 검색하면 전체를 낸다")
    fun `q 없으면 전체다`() {
        val (_, ws1) = newOwnedWorkspace("복지 안내", "owner-a")
        val (_, ws2) = newOwnedWorkspace("교육 안내", "owner-b")

        val result = repository.search(null, page = 1, size = 100)

        assertThat(result.items.map { it.workspaceId }).contains(ws1, ws2)
        assertThat(result.total).isGreaterThanOrEqualTo(2)
    }

    @Test
    @DisplayName("q는 이름 부분 일치로 좁힌다")
    fun `이름으로 검색한다`() {
        val unique = UUID.randomUUID().toString().take(8)
        val (_, ws1) = newOwnedWorkspace("고유이름-$unique", "owner-name-$unique")
        newOwnedWorkspace("다른 이름", "owner-other-$unique")

        val result = repository.search("고유이름-$unique", page = 1, size = 20)

        assertThat(result.items.map { it.workspaceId }).containsExactly(ws1)
        assertThat(result.total).isEqualTo(1)
    }

    @Test
    @DisplayName("q는 소유자 이메일 부분 일치로도 좁힌다")
    fun `이메일로 검색한다`() {
        val unique = UUID.randomUUID().toString().take(8)
        val (owner, ws1) = newOwnedWorkspace("워크스페이스-$unique", "owner-email-$unique")

        val result = repository.search(ownerEmailOf(owner), page = 1, size = 20)

        assertThat(result.items.map { it.workspaceId }).contains(ws1)
    }

    @Test
    @DisplayName("페이지·크기로 나뉜다")
    fun `페이지가 나뉜다`() {
        val unique = UUID.randomUUID().toString().take(8)
        repeat(3) { i -> newOwnedWorkspace("페이지테스트-$unique-$i", "owner-page-$unique-$i") }

        val page1 = repository.search("페이지테스트-$unique", page = 1, size = 2)
        val page2 = repository.search("페이지테스트-$unique", page = 2, size = 2)

        assertThat(page1.items).hasSize(2)
        assertThat(page2.items).hasSize(1)
        assertThat(page1.total).isEqualTo(3)
        assertThat(page2.total).isEqualTo(3)
    }

    @Test
    @DisplayName("find는 워크스페이스 id로 단건을 읽는다")
    fun `find 단건 조회`() {
        val (owner, workspaceId) = newOwnedWorkspace("상세 조회", "owner-detail")

        val row = repository.find(workspaceId)

        assertThat(row).isNotNull()
        assertThat(row!!.ownerId).isEqualTo(owner)
        assertThat(row.name).isEqualTo("상세 조회")
    }

    @Test
    @DisplayName("find는 없는 워크스페이스면 null이다")
    fun `find 없으면 null이다`() {
        assertThat(repository.find(UUID.randomUUID())).isNull()
    }

    @Test
    @DisplayName("실패 호출(provider_error, V18)만 있는 문서는 월간 문서·크레딧 집계에 잡히지 않는다")
    fun `실패만 있는 문서는 월간 집계에서 빠진다`() {
        val (owner, workspaceId) = newOwnedWorkspace("실패만-${UUID.randomUUID()}", "owner-failure")
        val at = Instant.parse("2026-08-15T02:00:00Z")
        insertLlmCall(workspaceId, owner, documentCharCount = 500, outcome = "provider_error", calledAt = at)

        val usage =
            repository.monthUsage(listOf(workspaceId), at.minusSeconds(3600), at.plusSeconds(3600))
        val summary = usage[workspaceId]

        assertThat(summary?.documents ?: 0).isEqualTo(0)
        assertThat(summary?.credits ?: 0).isEqualTo(0)
        assertThat(summary?.estimatedCostUsd).isNull()
    }

    @Test
    @DisplayName("완료 호출은 월간 문서·크레딧 집계에 그대로 잡힌다 — 위 테스트와의 대조")
    fun `완료 호출은 월간 집계에 잡힌다`() {
        val (owner, workspaceId) = newOwnedWorkspace("완료-${UUID.randomUUID()}", "owner-completed")
        val at = Instant.parse("2026-08-15T02:00:00Z")
        insertLlmCall(workspaceId, owner, documentCharCount = 1500, outcome = "completed", calledAt = at)

        val usage =
            repository.monthUsage(listOf(workspaceId), at.minusSeconds(3600), at.plusSeconds(3600))
        val summary = usage[workspaceId]

        assertThat(summary?.documents).isEqualTo(1)
        assertThat(summary?.credits).isEqualTo(2)
    }

    private fun newOwnedWorkspace(
        name: String,
        emailPrefix: String,
    ): Pair<UUID, UUID> {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
            .param("id", ownerId)
            .param("email", "$emailPrefix-$ownerId@example.test")
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
            .param("id", workspaceId)
            .param("userId", ownerId)
            .param("name", name)
            .update()
        return ownerId to workspaceId
    }

    /** `llm_calls`(V18) 행 하나를 심는다 — [outcome]은 `completed`·`provider_error`. */
    @Suppress("LongParameterList")
    private fun insertLlmCall(
        workspaceId: UUID,
        userId: UUID,
        documentCharCount: Int,
        outcome: String,
        calledAt: Instant,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO llm_calls
                    (id, workspace_id, user_id, document_id, purpose, provider, model, input_tokens,
                     output_tokens, char_count, document_char_count, outcome, called_at)
                VALUES
                    (:id, :workspaceId, :userId, :documentId, 'convert', 'anthropic', 'claude-sonnet-5', 0,
                     0, 10, :documentCharCount, :outcome, :calledAt)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspaceId", workspaceId)
            .param("userId", userId)
            .param("documentId", UUID.randomUUID())
            .param("documentCharCount", documentCharCount)
            .param("outcome", outcome)
            .param("calledAt", OffsetDateTime.ofInstant(calledAt, ZoneOffset.UTC))
            .update()
    }

    private fun ownerEmailOf(ownerId: UUID): String =
        jdbc
            .sql("SELECT email FROM users WHERE id = :id")
            .param("id", ownerId)
            .query { rs, _ -> rs.getString("email") }
            .single()

    private fun dataSource(): DataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = database.jdbcUrl
            username = database.username
            password = database.password
        }
}

package kr.easydoc.infrastructure.usage

import kr.easydoc.application.conversion.LlmCallEntry
import kr.easydoc.application.usage.UsageReadRepository
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.document.JdbcLlmCallLedger
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * 워크스페이스·기간별 사용량 집계(U2) — 실물 PostgreSQL. `easydoc.usage.zone` 자정 경계·
 * 다른 사용자 404·빈 기간 0·비용 미상 건수·문서 삭제 후 청구 근거 보존을 실제 DB 로 잰다.
 *
 * **문서 수·문자 수·크레딧은 `llm_calls`(V12) 에서 유도한다 — `documents` 표를 참조하지
 * 않는다**(2026-09-08 리뷰, `JdbcUsageReadRepository` KDoc). 그래서 이 파일의 픽스처는
 * `documents` 행을 심는 것과 별개로, 그 문서를 대상으로 한 `llm_calls` 행을 반드시
 * `document_char_count` 스냅샷과 함께 남긴다 — 그것이 집계가 실제로 읽는 값이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcUsageReadRepositoryTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var ledger: JdbcLlmCallLedger
    private lateinit var repository: UsageReadRepository

    private val seoul = ZoneId.of("Asia/Seoul")

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("usage_read_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val dataSource: DataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        ledger = JdbcLlmCallLedger(jdbc)
        repository = JdbcUsageReadRepository(jdbc)
    }

    @Test
    @DisplayName("문서·호출을 워크스페이스·기간별로 집계한다")
    fun `기본 집계가 문서와 호출을 모두 반영한다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-03-15T02:00:00Z") // 2026-03-15 11:00 KST
        val doc1 = insertDocument(workspaceId, owner, charCount = 1500, createdAt = at)
        val doc2 = insertDocument(workspaceId, owner, charCount = 500, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = doc1,
            documentCharCount = 1500,
            inputTokens = 100,
            outputTokens = 50,
            costUsd = BigDecimal("0.001000"),
            calledAt = at,
        )
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.REPAIR,
            documentId = doc2,
            documentCharCount = 500,
            inputTokens = 20,
            outputTokens = 10,
            costUsd = null,
            calledAt = at,
        )

        val from = zoneMidnight(2026, 3, 1)
        val toExclusive = zoneMidnight(2026, 4, 1)
        val usage = repository.aggregate(owner, workspaceId, from, toExclusive)

        assertThat(usage).isNotNull
        assertThat(usage!!.documents).isEqualTo(2)
        assertThat(usage.characters).isEqualTo(2000)
        // ceil(1500/1000)=2, ceil(500/1000)=1 → 합계 3 (합계 2000을 한 번에 올린 2와 다르다).
        assertThat(usage.credits).isEqualTo(3)
        assertThat(usage.llmCalls).isEqualTo(2)
        assertThat(usage.inputTokens).isEqualTo(120)
        assertThat(usage.outputTokens).isEqualTo(60)
        assertThat(usage.estimatedCostUsd).isEqualByComparingTo(BigDecimal("0.001000"))
        assertThat(usage.costUnknownCalls).isEqualTo(1)
        assertThat(usage.byPurpose).hasSize(2)
        val convert = usage.byPurpose.first { it.purpose == LlmCallPurpose.CONVERT }
        assertThat(convert.llmCalls).isEqualTo(1)
        assertThat(convert.estimatedCostUsd).isEqualByComparingTo(BigDecimal("0.001000"))
        val repair = usage.byPurpose.first { it.purpose == LlmCallPurpose.REPAIR }
        assertThat(repair.estimatedCostUsd).isNull()
    }

    @Test
    @DisplayName("zone 자정 경계 — to 날짜 23:59:59 KST(호출 시각)는 포함, 다음날 00:00:00 KST는 제외")
    fun `자정 경계에서 정확히 나뉜다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val dayStart = Instant.parse("2026-03-10T00:00:00Z")
        val insideDoc = insertDocument(workspaceId, owner, charCount = 100, createdAt = dayStart)
        val outsideDoc = insertDocument(workspaceId, owner, charCount = 200, createdAt = dayStart)

        // 2026-03-10 23:59:59 KST = 2026-03-10T14:59:59Z — to=2026-03-10 안에 포함되어야 한다.
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = insideDoc,
            documentCharCount = 100,
            inputTokens = 1,
            outputTokens = 1,
            costUsd = null,
            calledAt = Instant.parse("2026-03-10T14:59:59Z"),
        )
        // 2026-03-11 00:00:00 KST = 2026-03-10T15:00:00Z — to=2026-03-10 밖(다음날 자정)이다.
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = outsideDoc,
            documentCharCount = 200,
            inputTokens = 1,
            outputTokens = 1,
            costUsd = null,
            calledAt = Instant.parse("2026-03-10T15:00:00Z"),
        )

        val from = zoneMidnight(2026, 3, 10)
        val toExclusive = zoneMidnight(2026, 3, 11)
        val usage = repository.aggregate(owner, workspaceId, from, toExclusive)!!

        assertThat(usage.documents).isEqualTo(1)
        assertThat(usage.characters).isEqualTo(100)
    }

    @Test
    @DisplayName("빈 기간은 0으로 집계된다 — null이 섞이지 않는다")
    fun `빈 기간은 0이다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id

        val usage = repository.aggregate(owner, workspaceId, zoneMidnight(2020, 1, 1), zoneMidnight(2020, 2, 1))!!

        assertThat(usage.documents).isEqualTo(0)
        assertThat(usage.characters).isEqualTo(0)
        assertThat(usage.credits).isEqualTo(0)
        assertThat(usage.llmCalls).isEqualTo(0)
        assertThat(usage.inputTokens).isEqualTo(0)
        assertThat(usage.outputTokens).isEqualTo(0)
        assertThat(usage.estimatedCostUsd).isNull()
        assertThat(usage.costUnknownCalls).isEqualTo(0)
        assertThat(usage.byPurpose).isEmpty()
    }

    @Test
    @DisplayName("다른 사용자의 워크스페이스는 null — 404 판정 근거")
    fun `소유가 아니면 null`() {
        val owner = newOwner()
        val stranger = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id

        val usage = repository.aggregate(stranger, workspaceId, zoneMidnight(2020, 1, 1), zoneMidnight(2020, 2, 1))

        assertThat(usage).isNull()
    }

    @Test
    @DisplayName("존재하지 않는 워크스페이스도 null")
    fun `없는 워크스페이스는 null`() {
        val owner = newOwner()

        val usage = repository.aggregate(owner, UUID.randomUUID(), zoneMidnight(2020, 1, 1), zoneMidnight(2020, 2, 1))

        assertThat(usage).isNull()
    }

    @Test
    @DisplayName("문서 행을 지워도 청구 근거(문서 수·문자 수·크레딧)는 그대로다 — 원장은 documents 를 참조하지 않는다")
    fun `문서 삭제 후에도 집계가 바뀌지 않는다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-04-10T02:00:00Z")
        val documentId = insertDocument(workspaceId, owner, charCount = 1234, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = documentId,
            documentCharCount = 1234,
            inputTokens = 10,
            outputTokens = 5,
            costUsd = BigDecimal("0.002000"),
            calledAt = at,
        )
        val from = zoneMidnight(2026, 4, 1)
        val toExclusive = zoneMidnight(2026, 5, 1)
        val before = repository.aggregate(owner, workspaceId, from, toExclusive)!!

        // 보존 만료·즉시 삭제를 흉내 낸다 — llm_calls.document_id 는 FK가 없어 이 삭제에
        // 영향받지 않는다(V12 머리주석 3차 정정).
        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", documentId).update()

        val after = repository.aggregate(owner, workspaceId, from, toExclusive)!!

        assertThat(after.documents).isEqualTo(before.documents).isEqualTo(1)
        assertThat(after.characters).isEqualTo(before.characters).isEqualTo(1234)
        assertThat(after.credits).isEqualTo(before.credits).isEqualTo(2)
        assertThat(after.estimatedCostUsd).isEqualByComparingTo(before.estimatedCostUsd)
    }

    @Test
    @DisplayName("같은 문서를 대상으로 한 CONVERT 두 행(거절 뒤 재시도)은 문서 하나로 센다")
    fun `재시도로 생긴 같은 문서의 여러 행은 문서 하나로 집계된다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-06-01T02:00:00Z")
        val documentId = insertDocument(workspaceId, owner, charCount = 800, createdAt = at)
        // 첫 시도 — 완성됐지만 거절(REFUSAL)로 분류돼 재시도 대상이 된 호출.
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = documentId,
            documentCharCount = 800,
            inputTokens = 30,
            outputTokens = 0,
            costUsd = null,
            calledAt = at,
        )
        // 재시도 — 같은 문서, 같은 목적(convert)의 두 번째 행.
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = documentId,
            documentCharCount = 800,
            inputTokens = 30,
            outputTokens = 15,
            costUsd = BigDecimal("0.001500"),
            calledAt = at.plusSeconds(5),
        )

        val usage =
            repository.aggregate(owner, workspaceId, zoneMidnight(2026, 6, 1), zoneMidnight(2026, 6, 2))!!

        assertThat(usage.documents).isEqualTo(1)
        assertThat(usage.characters).isEqualTo(800)
        assertThat(usage.credits).isEqualTo(1)
        // 문서는 하나로 세지만 호출은 실제로 벌어진 두 번 다 센다.
        assertThat(usage.llmCalls).isEqualTo(2)
    }

    @Test
    @DisplayName("workspace_id가 NULL인 행(워크스페이스가 나중에 삭제된 행)은 집계에서 제외된다")
    fun `workspace_id가 NULL인 행은 제외된다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-07-01T02:00:00Z")

        val includedDoc = insertDocument(workspaceId, owner, charCount = 100, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = includedDoc,
            documentCharCount = 100,
            inputTokens = 1,
            outputTokens = 1,
            costUsd = null,
            calledAt = at,
        )

        // 워크스페이스가 삭제돼 workspace_id 가 SET NULL 된 상태를 직접 만든다 — 실제로는
        // fk_llm_calls_workspace_id_workspaces 가 이 값을 만든다(V12).
        val excludedDoc = insertDocument(workspaceId, owner, charCount = 999, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = excludedDoc,
            documentCharCount = 999,
            inputTokens = 1,
            outputTokens = 1,
            costUsd = null,
            calledAt = at,
        )
        jdbc.sql("UPDATE llm_calls SET workspace_id = NULL WHERE document_id = :id").param("id", excludedDoc).update()

        val usage =
            repository.aggregate(owner, workspaceId, zoneMidnight(2026, 7, 1), zoneMidnight(2026, 7, 2))!!

        assertThat(usage.documents).isEqualTo(1)
        assertThat(usage.characters).isEqualTo(100)
        assertThat(usage.llmCalls).isEqualTo(1)
    }

    private fun zoneMidnight(
        year: Int,
        month: Int,
        day: Int,
    ): Instant =
        java.time.LocalDate
            .of(year, month, day)
            .atStartOfDay(seoul)
            .toInstant()

    private fun newOwner(): UUID = users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id

    /** `documents` 행 하나를 심고 그 id 를 돌려준다. */
    private fun insertDocument(
        workspaceId: UUID,
        userId: UUID,
        charCount: Int,
        createdAt: Instant,
    ): UUID {
        val documentId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                       source_text_encrypted, char_count, encryption_scheme, key_version, created_at)
                VALUES (:id, :owner, :workspace, :title, :format, :bytes, :charCount, :scheme, 1, :createdAt)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", userId)
            .param("workspace", workspaceId)
            .param("title", "제목")
            .param("format", SourceFormat.TEXT.wireName)
            .param("bytes", byteArrayOf(1, 2, 3, 4))
            .param("charCount", charCount)
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
            .update()
        return documentId
    }

    @Suppress("LongParameterList")
    private fun appendCall(
        workspaceId: UUID,
        userId: UUID,
        purpose: LlmCallPurpose,
        documentId: UUID,
        documentCharCount: Int,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: BigDecimal?,
        calledAt: Instant,
    ) {
        ledger.append(
            listOf(
                LlmCallEntry(
                    conversionId = null,
                    documentId = documentId,
                    workspaceId = workspaceId,
                    userId = userId,
                    record =
                        LlmCallRecord(
                            purpose = purpose,
                            provider = "anthropic",
                            model = "claude-sonnet-5",
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            latencyMs = 100,
                            estimatedCostUsd = costUsd,
                            pricingInputUsdPerMtok = if (costUsd != null) BigDecimal("2.00") else null,
                            pricingOutputUsdPerMtok = if (costUsd != null) BigDecimal("10.00") else null,
                            charCount = 40,
                            calledAt = calledAt,
                        ),
                    calledAt = calledAt,
                    documentCharCount = documentCharCount,
                ),
            ),
        )
    }

    private companion object {
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

package kr.easydoc.infrastructure.usage

import kr.easydoc.application.conversion.LlmCallEntry
import kr.easydoc.application.usage.UsageReadRepository
import kr.easydoc.application.usage.UsageReportRepository
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.llm.LlmCallOutcome
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
 * 운영 리포트(U3) 소유자 전체 집계 — 실물 PostgreSQL. 두 사용자 × 두 워크스페이스, 삭제된
 * 워크스페이스(`workspace_id IS NULL`) 행, 자정 경계, 빈 기간, 비용 미상을 잰다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcUsageReportRepositoryTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var ledger: JdbcLlmCallLedger
    private lateinit var repository: UsageReportRepository
    private lateinit var workspaceRepository: UsageReadRepository

    private val seoul = ZoneId.of("Asia/Seoul")

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("usage_report_repository")
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
        repository = JdbcUsageReportRepository(jdbc)
        workspaceRepository = JdbcUsageReadRepository(jdbc)
    }

    @Test
    @DisplayName("두 사용자 × 두 워크스페이스가 각자 행으로 나뉜다")
    fun `사용자와 워크스페이스별로 행이 나뉜다`() {
        val owner1 = newOwner()
        val owner2 = newOwner()
        val ws1 = workspaces.create(owner1, "공간-${UUID.randomUUID()}")
        val ws2 = workspaces.create(owner2, "공간-${UUID.randomUUID()}")
        val at = Instant.parse("2026-03-15T02:00:00Z")

        val doc1 = insertDocument(ws1.id, owner1, charCount = 1500, createdAt = at)
        appendCall(
            ws1.id,
            owner1,
            LlmCallPurpose.CONVERT,
            documentId = doc1,
            documentCharCount = 1500,
            inputTokens = 100,
            outputTokens = 50,
            costUsd = BigDecimal("0.001000"),
            calledAt = at,
        )
        val doc2 = insertDocument(ws2.id, owner2, charCount = 500, createdAt = at)
        appendCall(
            ws2.id,
            owner2,
            LlmCallPurpose.CONVERT,
            documentId = doc2,
            documentCharCount = 500,
            inputTokens = 20,
            outputTokens = 10,
            costUsd = BigDecimal("0.000500"),
            calledAt = at,
        )

        val rows = repository.reportRows(zoneMidnight(2026, 3, 1), zoneMidnight(2026, 4, 1))

        val row1 = rows.first { it.userId == owner1 }
        assertThat(row1.workspaceId).isEqualTo(ws1.id)
        assertThat(row1.workspaceName).isEqualTo(ws1.name)
        assertThat(row1.documents).isEqualTo(1)
        assertThat(row1.characters).isEqualTo(1500)
        assertThat(row1.credits).isEqualTo(2)
        assertThat(row1.llmCalls).isEqualTo(1)
        assertThat(row1.estimatedCostUsd).isEqualByComparingTo(BigDecimal("0.001000"))
        assertThat(row1.costUnknownCalls).isEqualTo(0)

        val row2 = rows.first { it.userId == owner2 }
        assertThat(row2.workspaceId).isEqualTo(ws2.id)
        assertThat(row2.documents).isEqualTo(1)
        assertThat(row2.characters).isEqualTo(500)
        assertThat(row2.credits).isEqualTo(1)
    }

    @Test
    @DisplayName("워크스페이스가 삭제된(workspace_id NULL) 행은 자기 행으로 남는다 — 이름은 NULL")
    fun `삭제된 워크스페이스 행은 별도로 남는다`() {
        val owner = newOwner()
        val ws = workspaces.create(owner, "공간-${UUID.randomUUID()}")
        val at = Instant.parse("2026-05-01T02:00:00Z")

        val activeDoc = insertDocument(ws.id, owner, charCount = 300, createdAt = at)
        appendCall(
            ws.id,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = activeDoc,
            documentCharCount = 300,
            inputTokens = 5,
            outputTokens = 5,
            costUsd = null,
            calledAt = at,
        )
        val deletedWsDoc = insertDocument(ws.id, owner, charCount = 700, createdAt = at)
        appendCall(
            ws.id,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = deletedWsDoc,
            documentCharCount = 700,
            inputTokens = 7,
            outputTokens = 7,
            costUsd = null,
            calledAt = at,
        )
        // 워크스페이스 삭제로 SET NULL 된 상태를 직접 흉내 낸다(V14 FK ON DELETE SET NULL).
        jdbc
            .sql("UPDATE llm_calls SET workspace_id = NULL WHERE document_id = :id")
            .param("id", deletedWsDoc)
            .update()

        val rows = repository.reportRows(zoneMidnight(2026, 5, 1), zoneMidnight(2026, 6, 1))
        val ownerRows = rows.filter { it.userId == owner }

        assertThat(ownerRows).hasSize(2)
        val activeRow = ownerRows.first { it.workspaceId == ws.id }
        assertThat(activeRow.workspaceName).isEqualTo(ws.name)
        assertThat(activeRow.characters).isEqualTo(300)

        val deletedRow = ownerRows.first { it.workspaceId == null }
        assertThat(deletedRow.workspaceName).isNull()
        assertThat(deletedRow.characters).isEqualTo(700)
        assertThat(deletedRow.documents).isEqualTo(1)
    }

    @Test
    @DisplayName("zone 자정 경계 — to 날짜 23:59:59 KST는 포함, 다음날 00:00:00 KST는 제외")
    fun `자정 경계에서 정확히 나뉜다`() {
        val owner = newOwner()
        val ws = workspaces.create(owner, "공간-${UUID.randomUUID()}")
        val dayStart = Instant.parse("2026-06-10T00:00:00Z")
        val insideDoc = insertDocument(ws.id, owner, charCount = 100, createdAt = dayStart)
        val outsideDoc = insertDocument(ws.id, owner, charCount = 200, createdAt = dayStart)

        appendCall(
            ws.id,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = insideDoc,
            documentCharCount = 100,
            inputTokens = 1,
            outputTokens = 1,
            costUsd = null,
            calledAt = Instant.parse("2026-06-10T14:59:59Z"),
        )
        appendCall(
            ws.id,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = outsideDoc,
            documentCharCount = 200,
            inputTokens = 1,
            outputTokens = 1,
            costUsd = null,
            calledAt = Instant.parse("2026-06-10T15:00:00Z"),
        )

        val rows = repository.reportRows(zoneMidnight(2026, 6, 10), zoneMidnight(2026, 6, 11))
        val row = rows.first { it.userId == owner }

        assertThat(row.documents).isEqualTo(1)
        assertThat(row.characters).isEqualTo(100)
    }

    @Test
    @DisplayName("빈 기간은 빈 목록이다")
    fun `빈 기간은 빈 목록이다`() {
        val rows = repository.reportRows(zoneMidnight(2019, 1, 1), zoneMidnight(2019, 2, 1))

        assertThat(rows).isEmpty()
    }

    @Test
    @DisplayName("비용 미상 호출은 estimated_cost_usd에 섞이지 않고 cost_unknown_calls로 센다")
    fun `비용 미상은 별도로 센다`() {
        val owner = newOwner()
        val ws = workspaces.create(owner, "공간-${UUID.randomUUID()}")
        val at = Instant.parse("2026-07-01T02:00:00Z")
        val doc = insertDocument(ws.id, owner, charCount = 400, createdAt = at)

        appendCall(
            ws.id,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = doc,
            documentCharCount = 400,
            inputTokens = 10,
            outputTokens = 5,
            costUsd = BigDecimal("0.002000"),
            calledAt = at,
        )
        appendCall(
            ws.id,
            owner,
            LlmCallPurpose.REPAIR,
            documentId = doc,
            documentCharCount = 400,
            inputTokens = 3,
            outputTokens = 2,
            costUsd = null,
            calledAt = at.plusSeconds(1),
        )

        val rows = repository.reportRows(zoneMidnight(2026, 7, 1), zoneMidnight(2026, 7, 2))
        val row = rows.first { it.userId == owner }

        assertThat(row.llmCalls).isEqualTo(2)
        assertThat(row.estimatedCostUsd).isEqualByComparingTo(BigDecimal("0.002000"))
        assertThat(row.costUnknownCalls).isEqualTo(1)
    }

    @Test
    @DisplayName(
        "U3(소유자 전체 리포트)와 U2(워크스페이스 단위 집계)가 같은 워크스페이스·기간에 " +
            "완전히 같은 수치를 낸다 — 두 집계 경로의 교차 검증",
    )
    fun `U3 행이 같은 워크스페이스의 U2 집계와 정확히 같다`() {
        val owner = newOwner()
        val ws = workspaces.create(owner, "공간-${UUID.randomUUID()}")
        seedCrossConsistencyLedger(ws.id, owner)

        val from = zoneMidnight(2026, 8, 1)
        val toExclusive = zoneMidnight(2026, 9, 1)

        val u3Row = repository.reportRows(from, toExclusive).first { it.userId == owner }
        val u2Usage = workspaceRepository.aggregate(owner, ws.id, from, toExclusive)!!

        assertThat(u3Row.documents).isEqualTo(u2Usage.documents).isEqualTo(2)
        assertThat(u3Row.characters).isEqualTo(u2Usage.characters).isEqualTo(3501)
        assertThat(u3Row.credits).isEqualTo(u2Usage.credits).isEqualTo(5)
        assertThat(u3Row.llmCalls).isEqualTo(u2Usage.llmCalls).isEqualTo(3)
        assertThat(u3Row.inputTokens).isEqualTo(u2Usage.inputTokens)
        assertThat(u3Row.outputTokens).isEqualTo(u2Usage.outputTokens)
        assertThat(u3Row.estimatedCostUsd).isEqualByComparingTo(u2Usage.estimatedCostUsd)
        assertThat(u3Row.costUnknownCalls).isEqualTo(u2Usage.costUnknownCalls).isEqualTo(1)
        assertThat(u3Row.failedCalls).isEqualTo(u2Usage.failedCalls).isEqualTo(1)
    }

    @Test
    @DisplayName("실패 호출만 있고 완료가 하나도 없는 워크스페이스도 (llm_calls=0, failedCalls>0) 행으로 남는다")
    fun `실패만 있던 워크스페이스도 리포트에서 사라지지 않는다`() {
        val owner = newOwner()
        val ws = workspaces.create(owner, "공간-${UUID.randomUUID()}")
        val at = Instant.parse("2026-08-20T02:00:00Z")
        val doc = insertDocument(ws.id, owner, charCount = 300, createdAt = at)
        appendCall(
            ws.id,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = doc,
            documentCharCount = 300,
            inputTokens = 0,
            outputTokens = 0,
            costUsd = null,
            calledAt = at,
            outcome = LlmCallOutcome.PROVIDER_ERROR,
            failureClass = "LlmProviderException",
        )

        val rows = repository.reportRows(zoneMidnight(2026, 8, 20), zoneMidnight(2026, 8, 21))
        val row = rows.first { it.userId == owner }

        assertThat(row.documents).isEqualTo(0)
        assertThat(row.llmCalls).isEqualTo(0)
        assertThat(row.failedCalls).isEqualTo(1)
        assertThat(row.estimatedCostUsd).isNull()
        assertThat(row.costUnknownCalls).isEqualTo(0)
    }

    /**
     * 문서 둘(1500자·2001자) + 보정 호출 + 비용 미상 호출 + 경계 밖 호출을 심는다.
     * `ceil(1500/1000)=2, ceil(2001/1000)=3` → 문서별로 올려 합하면 5다. 합계
     * 문자수(3501)를 나중에 한 번만 올리면 `ceil(3501/1000)=4`로 어긋난다 — 두 집계
     * 경로가 같은 「문서별 올림」 공식을 쓰는지가 이 테스트의 핵심.
     */
    private fun seedCrossConsistencyLedger(
        workspaceId: UUID,
        owner: UUID,
    ) {
        val at = Instant.parse("2026-08-10T02:00:00Z")

        val doc1 = insertDocument(workspaceId, owner, charCount = 1500, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = doc1,
            documentCharCount = 1500,
            inputTokens = 100,
            outputTokens = 50,
            costUsd = BigDecimal("0.001500"),
            calledAt = at,
        )
        val doc2 = insertDocument(workspaceId, owner, charCount = 2001, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = doc2,
            documentCharCount = 2001,
            inputTokens = 200,
            outputTokens = 80,
            costUsd = BigDecimal("0.002000"),
            calledAt = at,
        )
        // 보정 호출(purpose=repair) — 비용 미상(estimated_cost_usd NULL) 행이라
        // cost_unknown_calls 로만 센다.
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.REPAIR,
            documentId = doc2,
            documentCharCount = 2001,
            inputTokens = 30,
            outputTokens = 10,
            costUsd = null,
            calledAt = at.plusSeconds(1),
        )
        seedCrossConsistencyBoundaryAndFailure(workspaceId, owner, doc2, at)
    }

    /** [seedCrossConsistencyLedger]에서 갈라낸 자리(`LongMethod`) — 경계 밖 호출과 실패 호출. */
    private fun seedCrossConsistencyBoundaryAndFailure(
        workspaceId: UUID,
        owner: UUID,
        doc2: UUID,
        at: Instant,
    ) {
        // 경계 밖 호출 — 기간(8월) 다음 달 1일이라 두 집계 모두에서 제외돼야 한다.
        val outOfRangeDoc = insertDocument(workspaceId, owner, charCount = 999, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = outOfRangeDoc,
            documentCharCount = 999,
            inputTokens = 999,
            outputTokens = 999,
            costUsd = BigDecimal("9.999999"),
            calledAt = Instant.parse("2026-09-01T00:00:00Z"), // 2026-09-01 09:00 KST — 8월 밖.
        )
        // 실패 호출(provider_error, V18) — llmCalls·토큰·비용에는 들어가지 않고
        // failedCalls로만 센다(두 집계 경로가 같은 규칙을 쓰는지 이 테스트가 확인한다).
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.REPAIR,
            documentId = doc2,
            documentCharCount = 2001,
            inputTokens = 0,
            outputTokens = 0,
            costUsd = null,
            calledAt = at.plusSeconds(2),
            outcome = LlmCallOutcome.PROVIDER_ERROR,
            failureClass = "LlmProviderException",
        )
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
        outcome: LlmCallOutcome = LlmCallOutcome.COMPLETED,
        failureClass: String? = null,
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
                            // LlmCallRecord.init 이 outcome=COMPLETED <=> model!=null 을
                            // 강제한다 — 실패 행은 model 도 null 이어야 한다.
                            model = if (outcome == LlmCallOutcome.COMPLETED) "claude-sonnet-5" else null,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            latencyMs = 100,
                            estimatedCostUsd = costUsd,
                            pricingInputUsdPerMtok = if (costUsd != null) BigDecimal("2.00") else null,
                            pricingOutputUsdPerMtok = if (costUsd != null) BigDecimal("10.00") else null,
                            charCount = 40,
                            calledAt = calledAt,
                            outcome = outcome,
                            failureClass = failureClass,
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

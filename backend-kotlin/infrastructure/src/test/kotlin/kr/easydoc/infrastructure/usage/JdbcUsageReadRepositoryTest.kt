package kr.easydoc.infrastructure.usage

import kr.easydoc.application.conversion.LlmCallEntry
import kr.easydoc.application.usage.UsageReadRepository
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
 * 워크스페이스·기간별 사용량 집계(U2) — 실물 PostgreSQL. `easydoc.usage.zone` 자정 경계·
 * 다른 사용자 404·빈 기간 0·비용 미상 건수·문서 삭제 후 청구 근거 보존을 실제 DB 로 잰다.
 *
 * **문서 수·문자 수·크레딧은 `llm_calls`(V14) 에서 유도한다 — `documents` 표를 참조하지
 * 않는다**(2026-09-08 리뷰, `JdbcUsageReadRepository` KDoc). 그래서 이 파일의 픽스처는
 * `documents` 행을 심는 것과 별개로, 그 문서를 대상으로 한 `llm_calls` 행을 반드시
 * `document_char_count` 스냅샷과 함께 남긴다 — 그것이 집계가 실제로 읽는 값이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass")
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
    @DisplayName("성공한 재변환 소비는 같은 문서라도 이번 달 사용 크레딧에 추가한다")
    fun `재변환 소비를 사용 크레딧에 더한다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-03-20T02:00:00Z")
        val documentId = insertDocument(workspaceId, owner, charCount = 1500, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId,
            documentCharCount = 1500,
            inputTokens = 10,
            outputTokens = 5,
            costUsd = null,
            calledAt = at,
        )
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.RECONVERT,
            documentId,
            documentCharCount = 1500,
            inputTokens = 4,
            outputTokens = 2,
            costUsd = null,
            calledAt = at.plusSeconds(1),
        )
        insertCreditConsume(workspaceId, owner, documentId, credits = 2, at = at)
        insertCreditConsume(workspaceId, owner, documentId, credits = 1, at = at.plusSeconds(1))

        val usage =
            repository.aggregate(owner, workspaceId, zoneMidnight(2026, 3, 1), zoneMidnight(2026, 4, 1))!!

        assertThat(usage.documents).isEqualTo(1)
        assertThat(usage.characters).isEqualTo(1500)
        assertThat(usage.credits).isEqualTo(3)
        assertThat(usage.llmCalls).isEqualTo(2)
    }

    @Test
    @DisplayName("행동 안내만 완료되고 예약이 해제되면 문서·문자·크레딧은 0이다")
    fun `해제된 행동 안내 예약은 문서 사용량과 크레딧을 늘리지 않는다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-03-21T02:00:00Z")
        val documentId = insertDocument(workspaceId, owner, charCount = 500, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.ACTION_GUIDE,
            documentId,
            documentCharCount = 500,
            inputTokens = 10,
            outputTokens = 5,
            costUsd = null,
            calledAt = at,
        )
        insertActionGuideCreditTransaction(workspaceId, owner, documentId, "reserve", 1, at)
        insertActionGuideCreditTransaction(workspaceId, owner, documentId, "release", -1, at.plusSeconds(1))

        val usage =
            repository.aggregate(owner, workspaceId, zoneMidnight(2026, 3, 1), zoneMidnight(2026, 4, 1))!!

        assertThat(usage.documents).isZero()
        assertThat(usage.characters).isZero()
        assertThat(usage.credits).isZero()
        assertThat(usage.llmCalls).isEqualTo(1)
    }

    @Test
    @DisplayName("행동 안내 예약·해제만 있을 때 이전 변환 호출의 크레딧 fallback을 유지한다")
    fun `행동 안내 예약은 변환 크레딧 fallback을 가리지 않는다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-03-22T02:00:00Z")
        val convertedDocument = insertDocument(workspaceId, owner, charCount = 1500, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            convertedDocument,
            documentCharCount = 1500,
            inputTokens = 10,
            outputTokens = 5,
            costUsd = BigDecimal("0.001"),
            calledAt = at,
        )
        val guideDocument = insertDocument(workspaceId, owner, charCount = 500, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.ACTION_GUIDE,
            guideDocument,
            documentCharCount = 500,
            inputTokens = 10,
            outputTokens = 5,
            costUsd = BigDecimal("0.002"),
            calledAt = at.plusSeconds(1),
        )
        insertActionGuideCreditTransaction(workspaceId, owner, guideDocument, "reserve", 1, at)
        insertActionGuideCreditTransaction(workspaceId, owner, guideDocument, "release", -1, at.plusSeconds(1))

        val usage = repository.aggregate(owner, workspaceId, zoneMidnight(2026, 3, 1), zoneMidnight(2026, 4, 1))!!

        assertThat(usage.documents).isEqualTo(1)
        assertThat(usage.characters).isEqualTo(1500)
        assertThat(usage.credits).isEqualTo(2)
        assertThat(usage.llmCalls).isEqualTo(2)
        assertThat(usage.estimatedCostUsd).isEqualByComparingTo("0.003")
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
    @DisplayName("유효한 이용 주기는 소진 후에도 결제 완료 시각을 시작점으로 돌려준다")
    fun `소진한 유효 주기도 시작 시각을 유지한다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val startedAt = Instant.parse("2026-09-03T06:24:30Z")
        val now = Instant.parse("2026-09-14T03:00:00Z")
        setCreditCycle(
            workspaceId,
            balance = 0,
            allowance = 50,
            startedAt = startedAt,
            endsAt = now.plusSeconds(86400),
        )

        assertThat(repository.activeCycleStartedAt(owner, workspaceId, now)).isEqualTo(startedAt)
    }

    @Test
    @DisplayName("미결제·결제 실패·만료 주기는 활성 시작 시각을 돌려주지 않는다")
    fun `활성 주기가 아니면 시작 시각이 없다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val now = Instant.parse("2026-09-14T03:00:00Z")
        setCreditCycle(
            workspaceId,
            balance = 0,
            allowance = 0,
            startedAt = now.minusSeconds(86400),
            endsAt = now.plusSeconds(86400),
        )

        assertThat(repository.activeCycleStartedAt(owner, workspaceId, now)).isNull()
        setCreditCycle(
            workspaceId,
            balance = 50,
            allowance = 50,
            startedAt = now.minusSeconds(172800),
            endsAt = now.minusSeconds(86400),
        )
        assertThat(repository.activeCycleStartedAt(owner, workspaceId, now)).isNull()
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

    private fun setCreditCycle(
        workspaceId: UUID,
        balance: Int,
        allowance: Int,
        startedAt: Instant,
        endsAt: Instant,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO workspace_credit_accounts
                    (workspace_id, balance, reserved, allowance, cycle_started_at, cycle_ends_at)
                VALUES (:workspaceId, :balance, 0, :allowance, :startedAt, :endsAt)
                ON CONFLICT (workspace_id) DO UPDATE
                SET balance = EXCLUDED.balance,
                    allowance = EXCLUDED.allowance,
                    cycle_started_at = EXCLUDED.cycle_started_at,
                    cycle_ends_at = EXCLUDED.cycle_ends_at
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("balance", balance)
            .param("allowance", allowance)
            .param("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
            .param("endsAt", OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC))
            .update()
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
        // 영향받지 않는다(V14 머리주석 3차 정정).
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
        // fk_llm_calls_workspace_id_workspaces 가 이 값을 만든다(V14).
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

    @Test
    @DisplayName("실패 호출(provider_error, V18)은 문서·문자·크레딧·토큰·비용에서 빠지고 failedCalls로만 센다")
    fun `실패 호출은 집계에서 빠지고 failedCalls로 센다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-08-01T02:00:00Z")
        val documentId = insertDocument(workspaceId, owner, charCount = 500, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = documentId,
            documentCharCount = 500,
            inputTokens = 30,
            outputTokens = 15,
            costUsd = BigDecimal("0.002000"),
            calledAt = at,
        )
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.REPAIR,
            documentId = documentId,
            documentCharCount = 500,
            inputTokens = 0,
            outputTokens = 0,
            costUsd = null,
            calledAt = at.plusSeconds(1),
            outcome = LlmCallOutcome.PROVIDER_ERROR,
            failureClass = "LlmProviderException",
        )
        insertUnknownCall(workspaceId, owner, documentId, at.plusSeconds(2))

        val usage =
            repository.aggregate(owner, workspaceId, zoneMidnight(2026, 8, 1), zoneMidnight(2026, 8, 2))!!

        assertThat(usage.documents).isEqualTo(1)
        assertThat(usage.characters).isEqualTo(500)
        assertThat(usage.credits).isEqualTo(1)
        assertThat(usage.llmCalls).isEqualTo(1)
        assertThat(usage.inputTokens).isEqualTo(30)
        assertThat(usage.outputTokens).isEqualTo(15)
        assertThat(usage.estimatedCostUsd).isEqualByComparingTo(BigDecimal("0.002000"))
        assertThat(usage.failedCalls).isEqualTo(2)
        val repair = usage.byPurpose.first { it.purpose == LlmCallPurpose.REPAIR }
        assertThat(repair.llmCalls).isEqualTo(0)
        assertThat(repair.failedCalls).isEqualTo(1)
        val convert = usage.byPurpose.first { it.purpose == LlmCallPurpose.CONVERT }
        assertThat(convert.failedCalls).isEqualTo(0)
        val actionGuide = usage.byPurpose.first { it.purpose == LlmCallPurpose.ACTION_GUIDE }
        assertThat(actionGuide.llmCalls).isZero()
        assertThat(actionGuide.failedCalls).isEqualTo(1)
    }

    @Test
    @DisplayName("실패 호출만 있고 완료가 하나도 없는 목적도 (llm_calls=0, failedCalls>0) 행으로 남는다")
    fun `실패만 있던 목적도 목록에서 사라지지 않는다`() {
        val owner = newOwner()
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val at = Instant.parse("2026-08-05T02:00:00Z")
        val documentId = insertDocument(workspaceId, owner, charCount = 200, createdAt = at)
        appendCall(
            workspaceId,
            owner,
            LlmCallPurpose.CONVERT,
            documentId = documentId,
            documentCharCount = 200,
            inputTokens = 0,
            outputTokens = 0,
            costUsd = null,
            calledAt = at,
            outcome = LlmCallOutcome.PROVIDER_ERROR,
            failureClass = "LlmProviderException",
        )

        val usage =
            repository.aggregate(owner, workspaceId, zoneMidnight(2026, 8, 5), zoneMidnight(2026, 8, 6))!!

        assertThat(usage.documents).isEqualTo(0)
        assertThat(usage.llmCalls).isEqualTo(0)
        assertThat(usage.failedCalls).isEqualTo(1)
        assertThat(usage.byPurpose).hasSize(1)
        val convert = usage.byPurpose.single()
        assertThat(convert.purpose).isEqualTo(LlmCallPurpose.CONVERT)
        assertThat(convert.llmCalls).isEqualTo(0)
        assertThat(convert.failedCalls).isEqualTo(1)
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

    private fun insertCreditConsume(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        credits: Int,
        at: Instant,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO credit_transactions
                    (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
                     reserved_delta, reason, created_at)
                VALUES (:id, :workspaceId, :ownerId, :documentId, 'consume', :delta,
                        :delta, 'conversion', :createdAt)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("documentId", documentId)
            .param("delta", -credits)
            .param("createdAt", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .update()
    }

    @Suppress("LongParameterList")
    private fun insertActionGuideCreditTransaction(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        kind: String,
        reservedDelta: Int,
        at: Instant,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO credit_transactions
                    (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
                     reserved_delta, reason, created_at)
                VALUES (:id, :workspaceId, :ownerId, :documentId, :kind, 0,
                        :reservedDelta, 'action_guide', :createdAt)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("documentId", documentId)
            .param("kind", kind)
            .param("reservedDelta", reservedDelta)
            .param("createdAt", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .update()
    }

    private fun insertUnknownCall(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        at: Instant,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO llm_calls
                    (id, document_id, workspace_id, user_id, purpose, provider, model,
                     input_tokens, output_tokens, char_count, document_char_count,
                     called_at, outcome)
                VALUES (:id, :documentId, :workspaceId, :ownerId, 'action_guide', NULL, NULL,
                        0, 0, 0, 500, :calledAt, 'outcome_unknown')
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("documentId", documentId)
            .param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("calledAt", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .update()
    }

    private companion object {
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

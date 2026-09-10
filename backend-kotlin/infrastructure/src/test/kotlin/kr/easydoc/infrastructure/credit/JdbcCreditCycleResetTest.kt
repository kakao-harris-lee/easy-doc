package kr.easydoc.infrastructure.credit

import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.CreditTransactionKind
import kr.easydoc.core.credit.Credits
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * `workspace_credit_accounts` 주기 초기화 — 실제 PostgreSQL 에서만 잴 수 있는 것들
 * (잠금, 말일 clamp 산술, 거래 합 = 잔액 불변식). `JdbcCreditAccountRepositoryTest`와
 * 같은 형태다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcCreditCycleResetTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var accounts: JdbcCreditAccountRepository
    private lateinit var reset: JdbcCreditCycleReset

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("credit_cycle_reset")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbc = JdbcClient.create(dataSource())
        accounts = JdbcCreditAccountRepository(jdbc)
        // 운영 조립(`CreditCycleResetConfiguration`)과 같은 값 — `easydoc.usage.zone`
        // 기본값(Asia/Seoul)을 재사용한다(리뷰 지적, `JdbcCreditCycleReset` KDoc).
        reset = JdbcCreditCycleReset(jdbc, ZoneId.of("Asia/Seoul"))
    }

    /**
     * `reset.reset(now, batchSize)` 는 **워크스페이스 범위가 없다** — 전체
     * `workspace_credit_accounts` 를 훑는다(`resetCount` 는 전역 집계다). 이 클래스는
     * `@TestInstance(PER_CLASS)` 로 DB 를 공유하므로(`JdbcRetentionPurgeTest` 와 같은
     * 판단), 이전 테스트가 남긴 계정 행이 있으면 다음 테스트의 `resetCount` 단언이
     * 어긋난다 — 매 테스트 전에 계정 행만 지운다(`users`·`workspaces` 는 남아도 무해하다).
     */
    @BeforeEach
    fun cleanAccounts() {
        jdbc.sql("DELETE FROM workspace_credit_accounts").update()
    }

    @Test
    @DisplayName("갱신 주기(renews=true)가 끝나면 남은 잔액을 버리고 allowance 로 다시 채운다 — reserved 는 보존된다")
    fun `주기가 끝난 계정은 초기화된다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        openCycle(
            workspaceId,
            ownerId,
            allowance = 50,
            cycleEndsAt = Instant.parse("2026-09-09T00:00:00Z"),
            renews = true,
        )
        // 이번 주기에서 20을 예약해 소비를 확정했고(20 → balance·reserved 함께 -20), 3을
        // 추가로 예약해 둔 상태를 흉내낸다 — reserve 는 항상 consume 보다 먼저다(reserved
        // 는 음수가 될 수 없다, ck_workspace_credit_accounts_reserved_non_negative).
        val consumedDocumentId = UUID.randomUUID()
        accounts.reserve(ownerId, workspaceId, consumedDocumentId, Credits(20), enforced = false)
        accounts.consume(workspaceId, ownerId, consumedDocumentId, UUID.randomUUID(), Credits(20))
        accounts.reserve(ownerId, workspaceId, UUID.randomUUID(), Credits(3), enforced = false)

        val now = Instant.parse("2026-09-10T00:00:00Z")
        val result = reset.reset(now, batchSize = 10)

        assertThat(result.resetCount).isEqualTo(1)
        val row = accounts.read(ownerId, workspaceId)!!
        assertThat(row.balance).isEqualTo(50)
        assertThat(row.reserved).isEqualTo(3)
        assertThat(row.cycleStartedAt).isEqualTo(Instant.parse("2026-09-09T00:00:00Z"))
        assertThat(row.cycleEndsAt).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"))
        val resetTx = row.transactions.first { it.kind == CreditTransactionKind.CYCLE_RESET }
        // 30(=50-20) → 50, delta = +20.
        assertThat(resetTx.balanceDelta).isEqualTo(20)
        assertThat(resetTx.reservedDelta).isZero()
        // 갱신은 그 주기의 제공량을 다시 준 것이 맞다 — plan_monthly.
        assertThat(resetTx.reason).isEqualTo(CreditReason.PLAN_MONTHLY)
    }

    @Test
    @DisplayName(
        "종료 주기(renews=false, 무료 체험)가 끝나면 잔액·이용량을 0으로, cycle_ends_at 을 null 로 닫는다 — " +
            "다시 채우지 않는다",
    )
    fun `종료 주기는 초기화 시 0으로 닫힌다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        openCycle(
            workspaceId,
            ownerId,
            allowance = 50,
            cycleEndsAt = Instant.parse("2026-09-09T00:00:00Z"),
            renews = false,
        )
        val documentId = UUID.randomUUID()
        accounts.reserve(ownerId, workspaceId, documentId, Credits(20), enforced = false)
        accounts.consume(workspaceId, ownerId, documentId, UUID.randomUUID(), Credits(20))
        // 예약 3은 끝나지 않은 문서의 몫이다 — reserved 는 초기화가 건드리지 않는다.
        accounts.reserve(ownerId, workspaceId, UUID.randomUUID(), Credits(3), enforced = false)

        val result = reset.reset(Instant.parse("2026-09-10T00:00:00Z"), batchSize = 10)

        assertThat(result.resetCount).isEqualTo(1)
        val row = accounts.read(ownerId, workspaceId)!!
        assertThat(row.balance).isZero()
        assertThat(row.allowance).isZero()
        assertThat(row.cycleEndsAt).isNull()
        assertThat(row.reserved).isEqualTo(3)
        val resetTx = row.transactions.first { it.kind == CreditTransactionKind.CYCLE_RESET }
        // 30(=50-20) → 0, delta = -30.
        assertThat(resetTx.balanceDelta).isEqualTo(-30)
        assertThat(resetTx.reservedDelta).isZero()
        // 갱신 없이 닫혔다 — plan_monthly(있지도 않은 구독)가 아니라 cycle_end다.
        assertThat(resetTx.reason).isEqualTo(CreditReason.CYCLE_END)
    }

    @Test
    @DisplayName("종료된 주기는 다음 배치가 다시 건드리지 않는다 — cycle_ends_at 이 null 이라 조건에서 걸러진다")
    fun `종료 주기는 다시 초기화되지 않는다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        openCycle(
            workspaceId,
            ownerId,
            allowance = 10,
            cycleEndsAt = Instant.parse("2026-09-09T00:00:00Z"),
            renews = false,
        )

        val first = reset.reset(Instant.parse("2026-09-10T00:00:00Z"), batchSize = 10)
        val second = reset.reset(Instant.parse("2026-09-11T00:00:00Z"), batchSize = 10)

        assertThat(first.resetCount).isEqualTo(1)
        assertThat(second.resetCount).isZero()
    }

    @Test
    @DisplayName("종료 주기 초기화 뒤에도 거래 합 = 잔액 불변식이 성립한다")
    fun `종료 주기 초기화 뒤 정합이 성립한다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        openCycle(
            workspaceId,
            ownerId,
            allowance = 20,
            cycleEndsAt = Instant.parse("2026-09-09T00:00:00Z"),
            renews = false,
        )

        reset.reset(Instant.parse("2026-09-10T00:00:00Z"), batchSize = 10)

        assertThat(accounts.consistencyViolations().map { it.workspaceId }).doesNotContain(workspaceId)
    }

    @Test
    @DisplayName("주기가 없는 계정(cycle_ends_at null)은 건드리지 않는다")
    fun `주기 없는 계정은 건드리지 않는다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        accounts.grant(workspaceId, ownerId, 77, CreditReason.SIGNUP, note = null, actorUserId = null)

        val result = reset.reset(Instant.parse("2026-09-10T00:00:00Z"), batchSize = 10)

        assertThat(result.resetCount).isZero()
        val row = accounts.read(ownerId, workspaceId)!!
        assertThat(row.balance).isEqualTo(77)
        assertThat(row.cycleEndsAt).isNull()
    }

    @Test
    @DisplayName("아직 끝나지 않은 주기는 건드리지 않는다")
    fun `아직 끝나지 않은 주기는 그대로다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        openCycle(workspaceId, ownerId, allowance = 50, cycleEndsAt = Instant.parse("2026-12-31T00:00:00Z"))

        val result = reset.reset(Instant.parse("2026-09-10T00:00:00Z"), batchSize = 10)

        assertThat(result.resetCount).isZero()
        val row = accounts.read(ownerId, workspaceId)!!
        assertThat(row.cycleEndsAt).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"))
    }

    @Test
    @DisplayName("말일 경계 — 1/31 에 끝난 주기는 2/28(평년)로 민다")
    fun `말일 경계 평년`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        openCycle(workspaceId, ownerId, allowance = 10, cycleEndsAt = Instant.parse("2027-01-31T00:00:00Z"))

        reset.reset(Instant.parse("2027-02-01T00:00:00Z"), batchSize = 10)

        val row = accounts.read(ownerId, workspaceId)!!
        assertThat(row.cycleEndsAt).isEqualTo(Instant.parse("2027-02-28T00:00:00Z"))
    }

    @Test
    @DisplayName("말일 경계 — 1/31 에 끝난 주기는 2/29(윤년)로 민다")
    fun `말일 경계 윤년`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        openCycle(workspaceId, ownerId, allowance = 10, cycleEndsAt = Instant.parse("2028-01-31T00:00:00Z"))

        reset.reset(Instant.parse("2028-02-01T00:00:00Z"), batchSize = 10)

        val row = accounts.read(ownerId, workspaceId)!!
        assertThat(row.cycleEndsAt).isEqualTo(Instant.parse("2028-02-29T00:00:00Z"))
    }

    @Test
    @DisplayName(
        "말일 경계는 KST(Asia/Seoul) 기준이다 — UTC 자정과 갈리는 시각(UTC 15시 이후)에서도 " +
            "KST 날짜로 계산한다, MEDIUM 리뷰 지적",
    )
    fun `말일 경계는 KST 기준이다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        // UTC로는 1/30 16:00이지만 KST(+9)로는 이미 1/31 01:00이다 — "한 달 뒤"의 기준
        // 날짜가 UTC냐 KST냐에 따라 갈린다. UTC(1/30) 기준으로 계산하면 2월 28일(2027년
        // 평년, 클램프) 16:00 UTC를 내지만, KST(1/31) 기준으로 계산하면 2월 28일 01:00
        // KST = 2월 27일 16:00 UTC다 — 하루가 갈린다. 이 테스트는 KST 쪽(2/27)이 나오는지
        // 잰다 — UTC로 계산했다면(리뷰가 지적한 버그) 2/28이 나왔을 것이다.
        openCycle(workspaceId, ownerId, allowance = 10, cycleEndsAt = Instant.parse("2027-01-30T16:00:00Z"))

        reset.reset(Instant.parse("2027-01-31T00:00:00Z"), batchSize = 10)

        val row = accounts.read(ownerId, workspaceId)!!
        assertThat(row.cycleEndsAt).isEqualTo(Instant.parse("2027-02-27T16:00:00Z"))
    }

    @Test
    @DisplayName(
        "여러 달치가 밀린 계정은 한 번에 따라잡는다 — balance 는 한 번만 설정되고 거래도 " +
            "한 건뿐이다, MEDIUM 리뷰 지적",
    )
    fun `밀린 계정은 한 번에 따라잡는다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        // 6/9 에 끝났어야 할 주기가 9/10 까지(3개월) 방치됐다고 가정한다 — 배치가 그동안
        // 멈춰 있었던 상태를 흉내낸다.
        openCycle(workspaceId, ownerId, allowance = 50, cycleEndsAt = Instant.parse("2026-06-09T00:00:00Z"))

        val result = reset.reset(Instant.parse("2026-09-10T00:00:00Z"), batchSize = 10)

        assertThat(result.resetCount).isEqualTo(1)
        val row = accounts.read(ownerId, workspaceId)!!
        // balance 는 allowance 한 번으로 설정된다 — 지나간 3개월치를 소급해서 여러 번
        // 지급한 것처럼 보이면 안 된다(리뷰 권고).
        assertThat(row.balance).isEqualTo(50)
        // 주기는 now 를 넘어설 때까지 밀린다 — 6/9 → 7/9 → 8/9 → 9/9 → 10/9(now=9/10 보다 뒤).
        assertThat(row.cycleStartedAt).isEqualTo(Instant.parse("2026-09-09T00:00:00Z"))
        assertThat(row.cycleEndsAt).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"))
        // 거래는 한 건뿐이고, 건너뛴 개월 수(3)를 note 에 남긴다.
        val resetTransactions = row.transactions.filter { it.kind == CreditTransactionKind.CYCLE_RESET }
        assertThat(resetTransactions).hasSize(1)
        assertThat(resetTransactions.single().note).isEqualTo("3개 주기를 건너뛰었다")
    }

    @Test
    @DisplayName("초기화 뒤에도 거래 합 = 잔액 불변식이 성립한다")
    fun `초기화 뒤 정합이 성립한다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        openCycle(workspaceId, ownerId, allowance = 40, cycleEndsAt = Instant.parse("2026-09-09T00:00:00Z"))
        val documentId = UUID.randomUUID()
        accounts.reserve(ownerId, workspaceId, documentId, Credits(15), enforced = false)
        accounts.consume(workspaceId, ownerId, documentId, UUID.randomUUID(), Credits(15))

        reset.reset(Instant.parse("2026-09-10T00:00:00Z"), batchSize = 10)

        assertThat(accounts.consistencyViolations().map { it.workspaceId }).doesNotContain(workspaceId)
    }

    @Test
    @DisplayName("배치 크기를 넘는 대상은 이번 배치만큼만 처리하고 건수를 정확히 낸다")
    fun `배치 크기만큼만 처리한다`() {
        val ids = (1..3).map { newOwnedWorkspace() }
        ids.forEach { (ownerId, workspaceId) ->
            openCycle(workspaceId, ownerId, allowance = 5, cycleEndsAt = Instant.parse("2026-09-09T00:00:00Z"))
        }

        val result = reset.reset(Instant.parse("2026-09-10T00:00:00Z"), batchSize = 2)

        assertThat(result.resetCount).isEqualTo(2)
    }

    private fun openCycle(
        workspaceId: UUID,
        ownerId: UUID,
        allowance: Int,
        cycleEndsAt: Instant,
        renews: Boolean = true,
    ) {
        accounts.setAllowance(
            workspaceId,
            ownerId,
            allowance,
            cycleEndsAt,
            renews,
            CreditReason.PLAN_MONTHLY,
            note = null,
            actorUserId = null,
        )
    }

    private fun newOwnedWorkspace(): Pair<UUID, UUID> {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
            .param("id", ownerId)
            .param("email", "credit-cycle-$ownerId@example.test")
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
            .param("id", workspaceId)
            .param("userId", ownerId)
            .param("name", "credit-cycle-ws-${workspaceId.toString().take(8)}")
            .update()
        accounts.ensureAccount(workspaceId)
        return ownerId to workspaceId
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
}

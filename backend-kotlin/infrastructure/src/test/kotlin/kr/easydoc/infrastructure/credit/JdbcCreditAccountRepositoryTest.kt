package kr.easydoc.infrastructure.credit

import kr.easydoc.application.credit.ReservationResult
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.CreditTransactionKind
import kr.easydoc.core.credit.Credits
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
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** `workspace_credit_accounts`·`credit_transactions` — 실제 PostgreSQL 에서만 잴 수 있는 것들. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcCreditAccountRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcCreditAccountRepository
    private lateinit var interference: ExecutorService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("credit_account_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbc = JdbcClient.create(dataSource())
        repository = JdbcCreditAccountRepository(jdbc)
        interference = Executors.newFixedThreadPool(2)
    }

    @Test
    @DisplayName("예약은 가용 잔액이 충분하면 성공하고 거래를 남긴다")
    fun `예약 성공`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        repository.grant(workspaceId, ownerId, 10, CreditReason.SIGNUP, note = null)

        val result = repository.reserve(ownerId, workspaceId, UUID.randomUUID(), Credits(3), enforced = true)

        assertThat(result).isInstanceOf(ReservationResult.Reserved::class.java)
        val reserved = result as ReservationResult.Reserved
        assertThat(reserved.balance).isEqualTo(10)
        assertThat(reserved.reserved).isEqualTo(3)
        assertThat(reserved.available).isEqualTo(7)
    }

    @Test
    @DisplayName("집행 중 가용 잔액이 모자라면 예약이 거절되고 잔여 가용량을 돌려준다")
    fun `예약 실패`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        repository.grant(workspaceId, ownerId, 2, CreditReason.SIGNUP, note = null)

        val result = repository.reserve(ownerId, workspaceId, UUID.randomUUID(), Credits(3), enforced = true)

        assertThat(result).isEqualTo(ReservationResult.Insufficient(2))
    }

    @Test
    @DisplayName("집행이 꺼지면 가용 잔액이 모자라도 예약이 성공하고 잔액이 음수로 기록된다")
    fun `집행 꺼짐은 음수를 허용한다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()

        val result = repository.reserve(ownerId, workspaceId, UUID.randomUUID(), Credits(5), enforced = false)

        assertThat(result).isInstanceOf(ReservationResult.Reserved::class.java)
        val reserved = result as ReservationResult.Reserved
        assertThat(reserved.reserved).isEqualTo(5)
        assertThat(reserved.available).isEqualTo(-5)
    }

    @Test
    @DisplayName("소비는 잔액과 예약을 함께 줄이고 두 델타 모두 -3 거래를 남긴다")
    fun `소비`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        repository.grant(workspaceId, ownerId, 10, CreditReason.SIGNUP, note = null)
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        repository.reserve(ownerId, workspaceId, documentId, Credits(3), enforced = true)

        repository.consume(workspaceId, ownerId, documentId, conversionId, Credits(3))

        val row = repository.read(ownerId, workspaceId)!!
        assertThat(row.balance).isEqualTo(7)
        assertThat(row.reserved).isEqualTo(0)
        val consumeTx = row.transactions.first { it.kind == CreditTransactionKind.CONSUME }
        assertThat(consumeTx.balanceDelta).isEqualTo(-3)
        assertThat(consumeTx.reservedDelta).isEqualTo(-3)
    }

    @Test
    @DisplayName("해제는 예약만 되돌리고 잔액은 그대로다")
    fun `해제`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        repository.grant(workspaceId, ownerId, 10, CreditReason.SIGNUP, note = null)
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        repository.reserve(ownerId, workspaceId, documentId, Credits(3), enforced = true)

        repository.release(workspaceId, ownerId, documentId, conversionId, Credits(3))

        val row = repository.read(ownerId, workspaceId)!!
        assertThat(row.balance).isEqualTo(10)
        assertThat(row.reserved).isEqualTo(0)
        val releaseTx = row.transactions.first { it.kind == CreditTransactionKind.RELEASE }
        assertThat(releaseTx.balanceDelta).isEqualTo(0)
        assertThat(releaseTx.reservedDelta).isEqualTo(-3)
    }

    @Test
    @DisplayName("부여는 양수면 grant, 음수면 adjust 로 거래 종류를 가른다")
    fun `부여와 조정`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()

        val afterGrant = repository.grant(workspaceId, ownerId, 50, CreditReason.PLAN_MONTHLY, note = "월 구독")
        assertThat(afterGrant).isEqualTo(50)

        val afterAdjust = repository.grant(workspaceId, ownerId, -10, CreditReason.MANUAL, note = "환급 취소")
        assertThat(afterAdjust).isEqualTo(40)

        val row = repository.read(ownerId, workspaceId)!!
        assertThat(row.transactions.map { it.kind })
            .containsExactlyInAnyOrder(CreditTransactionKind.GRANT, CreditTransactionKind.ADJUST)
    }

    @Test
    @DisplayName("읽기는 남의 워크스페이스면 null 이다 — 존재 은닉")
    fun `남의 워크스페이스는 null`() {
        val (_, workspaceId) = newOwnedWorkspace()
        val stranger = UUID.randomUUID()

        assertThat(repository.read(stranger, workspaceId)).isNull()
    }

    @Test
    @DisplayName("최근 거래는 50건으로 잘리고 최신순이다")
    fun `거래는 최근 50건 최신순이다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        repeat(55) { i -> repository.grant(workspaceId, ownerId, 1, CreditReason.MANUAL, note = "grant-$i") }

        val row = repository.read(ownerId, workspaceId)!!

        assertThat(row.transactions).hasSize(50)
        assertThat(row.transactions.map { it.note }.first()).isEqualTo("grant-54")
    }

    @Test
    @DisplayName("정합 검사 — 거래 합계가 balance - reserved 와 어긋나면 잡아낸다")
    fun `정합 검사가 어긋난 계정을 찾는다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        repository.grant(workspaceId, ownerId, 10, CreditReason.SIGNUP, note = null)
        assertThat(repository.consistencyViolations().map { it.workspaceId }).doesNotContain(workspaceId)

        // 거래 없이 계정 행만 직접 뒤튼다 — 정합이 깨진 상태를 인위로 만든다.
        jdbc
            .sql("UPDATE workspace_credit_accounts SET balance = balance + 100 WHERE workspace_id = :workspaceId")
            .param("workspaceId", workspaceId)
            .update()

        val violations = repository.consistencyViolations()
        assertThat(violations.map { it.workspaceId }).contains(workspaceId)
        val violation = violations.first { it.workspaceId == workspaceId }
        assertThat(violation.balance).isNotEqualTo(violation.balanceSum)
    }

    @Test
    @DisplayName("계정 행이 없어도(롤링 배포 창) 집행이 꺼지면 예약이 성공하고 계정을 만든다 — 리뷰 HIGH-2")
    fun `계정 행 없이 예약하면 만들어서 진행한다`() {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
            .param("id", ownerId)
            .param("email", "credit-$ownerId@example.test")
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
            .param("id", workspaceId)
            .param("userId", ownerId)
            .param("name", "credit-ws-${workspaceId.toString().take(8)}")
            .update()
        // ensureAccount 를 부르지 않는다 — 계정 행이 아직 없는 상태를 그대로 흉내낸다.

        val result = repository.reserve(ownerId, workspaceId, UUID.randomUUID(), Credits(5), enforced = false)

        assertThat(result).isInstanceOf(ReservationResult.Reserved::class.java)
        val reserved = result as ReservationResult.Reserved
        assertThat(reserved.reserved).isEqualTo(5)
        assertThat(reserved.available).isEqualTo(-5)
    }

    @Test
    @DisplayName("V15 이전 문서(0 크레딧)는 소비·해제가 계정을 바꾸지 않는다 — 서비스 층 no-op 과 별개로, 0 인자를 그대로 넣어도 금액이 0이라 무해하다")
    fun `0 크레딧 소비는 무해하다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        repository.grant(workspaceId, ownerId, 10, CreditReason.SIGNUP, note = null)

        repository.consume(workspaceId, ownerId, UUID.randomUUID(), UUID.randomUUID(), Credits(0))

        val row = repository.read(ownerId, workspaceId)!!
        assertThat(row.balance).isEqualTo(10)
        assertThat(row.reserved).isEqualTo(0)
    }

    @Test
    @DisplayName("동시 등록 2건, 가용 1 → 정확히 하나만 예약에 성공한다")
    fun `동시 예약은 하나만 성공한다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        repository.grant(workspaceId, ownerId, 1, CreditReason.SIGNUP, note = null)

        val barrier = CyclicBarrier(2)
        val firstAttempt =
            interference.submit<ReservationResult> {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                JdbcCreditAccountRepository(JdbcClient.create(dataSource()))
                    .reserve(ownerId, workspaceId, UUID.randomUUID(), Credits(1), enforced = true)
            }
        val secondAttempt =
            interference.submit<ReservationResult> {
                barrier.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                JdbcCreditAccountRepository(JdbcClient.create(dataSource()))
                    .reserve(ownerId, workspaceId, UUID.randomUUID(), Credits(1), enforced = true)
            }

        val results =
            listOf(
                firstAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                secondAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

        val successes = results.filterIsInstance<ReservationResult.Reserved>()
        val insufficient = results.filterIsInstance<ReservationResult.Insufficient>()
        assertThat(successes)
            .withFailMessage("정확히 하나만 성공해야 하는데 %d 개가 성공했다 — %s", successes.size, results)
            .hasSize(1)
        assertThat(insufficient).hasSize(1)

        val row = repository.read(ownerId, workspaceId)!!
        assertThat(row.balance - row.reserved).isEqualTo(0)
    }

    @Test
    @DisplayName("부여 대상 계정 행이 없으면 NotFoundException — 리뷰 MEDIUM-8")
    fun `부여 대상이 없으면 NotFoundException`() {
        assertThat(
            org.junit.jupiter.api.assertThrows<kr.easydoc.core.exceptions.NotFoundException> {
                repository.grant(UUID.randomUUID(), UUID.randomUUID(), 10, CreditReason.SIGNUP, note = null)
            },
        ).isNotNull()
    }

    @Test
    @DisplayName("워크스페이스 소유자 조회 — credit-grant 프로필(C2)이 쓴다")
    fun `소유자 조회`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()

        assertThat(repository.ownerOf(workspaceId)).isEqualTo(ownerId)
    }

    @Test
    @DisplayName("없는 워크스페이스의 소유자 조회는 null 이다")
    fun `없는 워크스페이스는 null`() {
        assertThat(repository.ownerOf(UUID.randomUUID())).isNull()
    }

    private fun newOwnedWorkspace(): Pair<UUID, UUID> {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)",
            ).param("id", ownerId)
            .param("email", "credit-$ownerId@example.test")
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
            .param("id", workspaceId)
            .param("userId", ownerId)
            .param("name", "credit-ws-${workspaceId.toString().take(8)}")
            .update()
        repository.ensureAccount(workspaceId)
        return ownerId to workspaceId
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private companion object {
        const val TASK_TIMEOUT_SECONDS = 10L
    }
}

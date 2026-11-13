package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.actionguide.JdbcActionGuideJobRepository
import kr.easydoc.infrastructure.auth.JdbcAccountDeletionRepository
import kr.easydoc.infrastructure.document.DocumentJobLocks
import kr.easydoc.infrastructure.document.JdbcConversionRepository
import kr.easydoc.infrastructure.illustration.generation.JdbcIllustrationGenerationJobRepository
import kr.easydoc.infrastructure.illustration.suggestion.JdbcIllustrationSuggestionJobRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

/**
 * **문서 삭제와 worker 정산이 잠금을 같은 순서로 잡는가.**
 *
 * worker 는 언제나 「작업 행 → 이용량 계정」 순서로 잡는다(`ProcessActionGuideJob`·
 * `ProcessIllustrationGenerationJob`·`ProcessIllustrationSuggestionJob`). 삭제가 계정을 **먼저** 쥔 채
 * 아직 잠그지 않은 작업 행을 기다리면 사이클이 닫히고, PostgreSQL 은 둘 중 하나를 죽인다 —
 * 삭제(또는 그 삭제를 묶은 탈퇴·보존 만료 파기 배치 전체)가 통째로 되돌려지거나, 이미 돈을 쓴
 * 정산이 사라진다.
 *
 * 그 사이클은 두 자리에서 열린다.
 *
 * ⑴ **문서 한 건** — 한 문서에 세 가족의 작업이 동시에 활성일 수 있다. 가족마다 BEFORE DELETE
 *    트리거를 두면 이름 순서 때문에 한 가족을 정산한(=계정을 잡은) 뒤에야 다음 가족을 잠근다.
 *    V51 의 단일 조정 트리거가 세 가족의 행을 먼저 모두 잠가 막는다.
 * ⑵ **문서 여러 건** — 트리거는 행 단위라 `OLD.id` 의 작업만 미리 잠근다. 탈퇴·파기처럼 한
 *    문장이 여러 문서를 지우면 첫 문서를 정산하며 계정을 쥔 채 둘째 문서의 작업을 기다린다.
 *    삭제 **전에** [DocumentJobLocks] 가 배치 전체의 활성 작업을 정해진 순서로 잠가 막는다.
 *
 * 두 경우 모두 「삭제가 작업 행 앞에서 멈춰 서 있는 동안 worker 가 계정을 잡을 수 있는가」로
 * 잰다 — 잡히면 순서가 같은 것이고, 교착하면 삭제가 계정을 먼저 쥔 것이다.
 *
 * **접수도 같은 규칙 아래 있다**(이슈 #219). 삭제는 「변환 행 → 문서 행」 순으로 잡으므로 세
 * 가족의 접수(`lockOwnedContext`)가 문서 행을 먼저 잡으면 안 된다 —
 * [assertAcceptanceDoesNotDeadlockWithDelete] 가 가족마다 그것을 지킨다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentDeleteSettlementLockOrderTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var tx: TransactionTemplate
    private lateinit var accountDeletion: JdbcAccountDeletionRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var pool: ExecutorService

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("document_delete_lock_order")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
        accountDeletion = JdbcAccountDeletionRepository(jdbc, DocumentJobLocks(jdbc))
        conversions = JdbcConversionRepository(jdbc)
        // 접수를 두 문장 사이에 세워 둔 채 삭제를 돌리는 시험이 있어 스레드가 둘 필요하다.
        pool = Executors.newFixedThreadPool(2)
    }

    @AfterEach
    fun shutdown() {
        pool.shutdownNow()
    }

    @Test
    @DisplayName("문서 한 건 삭제가 그림 제안 정산과 교착하지 않는다")
    fun `문서 한 건 삭제가 그림 제안 정산과 교착하지 않는다`() {
        val seeded = seedOneDocument()

        raceDeletionAgainstHolderOf(seeded, SUGGESTION_JOBS) { deleteDocument(seeded.documentIds.single()) }

        assertSettledOnce(seeded)
    }

    @Test
    @DisplayName("문서 한 건 삭제가 그림 생성 정산과 교착하지 않는다")
    fun `문서 한 건 삭제가 그림 생성 정산과 교착하지 않는다`() {
        val seeded = seedOneDocument()

        raceDeletionAgainstHolderOf(seeded, GENERATION_JOBS) { deleteDocument(seeded.documentIds.single()) }

        assertSettledOnce(seeded)
    }

    @Test
    @DisplayName("문서 한 건 삭제가 행동 안내 정산과 교착하지 않는다 — 반대 순서도 같은 규칙이다")
    fun `문서 한 건 삭제가 행동 안내 정산과 교착하지 않는다`() {
        val seeded = seedOneDocument()

        raceDeletionAgainstHolderOf(seeded, ACTION_GUIDE_JOBS) { deleteDocument(seeded.documentIds.single()) }

        assertSettledOnce(seeded)
    }

    @Test
    @DisplayName("탈퇴의 여러 문서 삭제가 그림 제안 정산과 교착하지 않는다")
    fun `탈퇴의 여러 문서 삭제가 그림 제안 정산과 교착하지 않는다`() {
        // 먼저 훑는 문서는 행동 안내, 나중 문서는 그림 제안 — 배치 잠금이 없으면 앞 문서를
        // 정산하며 계정을 쥔 채 뒤 문서의 제안 작업을 기다린다.
        val seeded = seedTwoDocuments(first = ACTION_GUIDE_JOBS, second = SUGGESTION_JOBS)

        raceDeletionAgainstHolderOf(seeded, SUGGESTION_JOBS) { deleteAccount(seeded.ownerId) }

        assertSettledAfterAccountDeletion(seeded)
    }

    @Test
    @DisplayName("탈퇴의 여러 문서 삭제가 행동 안내 정산과 교착하지 않는다 — 반대 순서도 같은 규칙이다")
    fun `탈퇴의 여러 문서 삭제가 행동 안내 정산과 교착하지 않는다`() {
        val seeded = seedTwoDocuments(first = SUGGESTION_JOBS, second = ACTION_GUIDE_JOBS)

        raceDeletionAgainstHolderOf(seeded, ACTION_GUIDE_JOBS) { deleteAccount(seeded.ownerId) }

        assertSettledAfterAccountDeletion(seeded)
    }

    @Test
    @DisplayName("미정산 예약이 남은 변환 — 문서 삭제가 그림 생성 접수와 교착하지 않는다")
    fun `문서 삭제가 그림 생성 접수와 교착하지 않는다`() {
        val jobs = JdbcIllustrationGenerationJobRepository(jdbc)

        assertAcceptanceDoesNotDeadlockWithDelete(RESERVED) { ownerId, conversionId ->
            jobs.lockOwnedContext(ownerId, conversionId)
        }
    }

    @Test
    @DisplayName("미정산 예약이 남은 변환 — 문서 삭제가 그림 제안 접수와 교착하지 않는다")
    fun `문서 삭제가 그림 제안 접수와 교착하지 않는다`() {
        val jobs = JdbcIllustrationSuggestionJobRepository(jdbc, MAX_PROVIDER_ATTEMPTS)

        assertAcceptanceDoesNotDeadlockWithDelete(RESERVED) { ownerId, conversionId ->
            jobs.lockOwnedContext(ownerId, conversionId)
        }
    }

    @Test
    @DisplayName("미정산 예약이 남은 변환 — 문서 삭제가 행동 안내 접수와 교착하지 않는다")
    fun `문서 삭제가 행동 안내 접수와 교착하지 않는다`() {
        val jobs = JdbcActionGuideJobRepository(jdbc)

        assertAcceptanceDoesNotDeadlockWithDelete(RESERVED) { ownerId, conversionId ->
            jobs.lockOwnedContext(ownerId, conversionId)
        }
    }

    @Test
    @DisplayName("끝난 변환 — 그림 생성 접수는 삭제가 쥔 문서 행을 건너뛴다")
    fun `그림 생성 접수는 삭제가 쥔 문서 행을 건너뛴다`() {
        val jobs = JdbcIllustrationGenerationJobRepository(jdbc)

        assertAcceptanceSkipsHeldDocument { ownerId, conversionId ->
            jobs.lockOwnedContext(ownerId, conversionId)
        }
    }

    @Test
    @DisplayName("끝난 변환 — 그림 제안 접수는 삭제가 쥔 문서 행을 건너뛴다")
    fun `그림 제안 접수는 삭제가 쥔 문서 행을 건너뛴다`() {
        val jobs = JdbcIllustrationSuggestionJobRepository(jdbc, MAX_PROVIDER_ATTEMPTS)

        assertAcceptanceSkipsHeldDocument { ownerId, conversionId ->
            jobs.lockOwnedContext(ownerId, conversionId)
        }
    }

    @Test
    @DisplayName("끝난 변환 — 행동 안내 접수는 삭제가 쥔 문서 행을 건너뛴다")
    fun `행동 안내 접수는 삭제가 쥔 문서 행을 건너뛴다`() {
        val jobs = JdbcActionGuideJobRepository(jdbc)

        assertAcceptanceSkipsHeldDocument { ownerId, conversionId ->
            jobs.lockOwnedContext(ownerId, conversionId)
        }
    }

    /**
     * **접수와 삭제가 변환 행과 문서 행을 같은 순서로 잡는가**(이슈 #219).
     *
     * 삭제는 변환 → 문서 순이다 — `DocumentService.delete` 가 `lockPendingReservation`
     * (`FOR NO KEY UPDATE OF c`)로 예약을 잠근 뒤 `DELETE FROM documents` 로 문서 행에 닿는다.
     * 접수([lock])가 문서 행을 먼저 잡으면 두 순서가 엇갈려 ABBA 가 닫히고, PostgreSQL 이 둘 중
     * 하나를 죽인다 — 접수가 죽으면 409·404 자리에 500 이, 삭제가 죽으면 되돌려진 삭제가 남는다.
     *
     * 순서를 못박아 잰다. 삭제가 변환 행을 **먼저** 쥔 채 접수를 띄우면 접수는 변환 행 앞에서 멈춘다.
     * 그때 접수가 쥐고 있는 것이 갈림길이다 — 변환을 먼저 잡는 지금 구현은 그 자리에서 **아무것도**
     * 쥐지 않아 삭제가 문서 행으로 그냥 나아가고, 문서를 먼저 잡던 구현은 그 문서 잠금 때문에
     * 사이클을 닫아 아래 `DELETE` 가 `deadlock detected` 로 끊긴다.
     *
     * 반대 순서(접수가 먼저 커밋될 때까지 삭제가 기다리는 쪽)는 어느 구현에서도 교착하지 않아
     * 회귀를 잡지 못한다 — 이 순서 하나가 시험의 전부다.
     */
    private fun assertAcceptanceDoesNotDeadlockWithDelete(
        seedKind: SeedKind,
        lock: (UUID, UUID) -> Any?,
    ) {
        val seeded = seedConversion(seedKind)
        lateinit var acceptance: Future<Any?>
        val started = CountDownLatch(1)
        tx.executeWithoutResult {
            runDeletePathLocks(seeded)
            acceptance =
                pool.submit<Any?> {
                    started.countDown()
                    tx.execute { lock(seeded.ownerId, seeded.conversionId) }
                }
            assertThat(started.await(HANDOFF_SECONDS, TimeUnit.SECONDS))
                .describedAs("접수 스레드가 시작되지 않았다")
                .isTrue()
            awaitBlockedBackend()

            deleteDocument(seeded.documentId)
        }

        assertThat(acceptance.get(HANDOFF_SECONDS, TimeUnit.SECONDS))
            .describedAs("삭제가 커밋된 뒤의 접수는 변환을 찾지 못해야 한다")
            .isNull()
        assertThat(documentCount(seeded.ownerId)).isZero()
    }

    /**
     * **접수가 두 문장 사이에 있을 때 삭제와 교착하지 않는가**(PR #223 리뷰 B1).
     *
     * [ConversionRepository.lockPendingReservation] 은 예약이 남은 `pending`·`processing` 변환만
     * 고른다. 접수가 요구하는 `done` 변환에서는 **한 행도 잠그지 않으므로**, 그 상태에서 삭제가
     * 변환 행에 닿는 길은 FK CASCADE(`conversions.document_id ON DELETE CASCADE`, V1) 하나뿐인데
     * 그것은 문서 행을 지운 **뒤에** 돈다 — 삭제의 실질 순서가 문서→변환이다. 접수가 변환 행을 쥔
     * 채 문서 행을 **기다리면** 그 자리에서 사이클이 닫힌다.
     *
     * 접수의 문서 문장이 `SKIP LOCKED` 인 이유가 이것이다. 기다리지 않고 건너뛰어 `null` 을
     * 돌려주면 사이클이 생기지 않고, 호출자는 404 로 끝난다 — 실제로 그 문서는 지워지는 중이다.
     *
     * 접수의 두 문장을 그대로 펼쳐 그 창을 연다(`lockOwnedContext` 한 번으로는 두 문장 사이에서
     * 멈춰 세울 수 없다). SQL 은 베껴 쓰지 않고 저장소의 상수를 그대로 돌린다. 세 가족이 같은 두
     * 문장을 쓰는 것은 위 여섯 시험이 `lockOwnedContext` 로 고정한다.
     */
    @Test
    @DisplayName("문서 삭제가 쥔 문서 행을 접수는 건너뛴다 — 두 문장 사이에서도 교착하지 않는다")
    fun `문서 삭제와 접수가 두 문장 사이에서 교착하지 않는다`() {
        assertIntakeSkipsDocumentHeldByDeleter { seeded ->
            conversions.lockPendingReservation(seeded.ownerId, seeded.documentId)
            deleteDocument(seeded.documentId)
        }
    }

    @Test
    @DisplayName("탈퇴가 쥔 문서 행을 접수는 건너뛴다 — 끝난 변환에서도 교착하지 않는다")
    fun `탈퇴와 접수가 두 문장 사이에서 교착하지 않는다`() {
        val seeded = assertIntakeSkipsDocumentHeldByDeleter { accountDeletion.deleteUser(it.ownerId) }

        assertThat(queryStrings("SELECT count(*) FROM users WHERE id = '${seeded.ownerId}'").single())
            .isEqualTo("0")
    }

    /**
     * 접수를 **변환 잠금과 문서 잠금 사이에 세워 둔 채** [delete] 를 돌린다. 삭제가 문서 행을 쥐고
     * 변환 행 앞에서 **실제로 멈춰 선 것**을 본 뒤에야 접수의 둘째 문장을 보낸다 — 시간 맞추기가
     * 아니라 관찰된 대기다.
     */
    private fun assertIntakeSkipsDocumentHeldByDeleter(delete: (SeededConversion) -> Unit): SeededConversion {
        val seeded = seedConversion(SETTLED)
        val holdsConversion = CountDownLatch(1)
        val deleterBlocked = CountDownLatch(1)
        val intake =
            pool.submit<Boolean> {
                checkNotNull(
                    tx.execute {
                        intakeConversionLock(seeded)
                        holdsConversion.countDown()
                        check(deleterBlocked.await(HANDOFF_SECONDS, TimeUnit.SECONDS)) { "삭제가 멈춰 서지 않았다" }
                        intakeDocumentLock(seeded)
                    },
                )
            }
        assertThat(holdsConversion.await(HANDOFF_SECONDS, TimeUnit.SECONDS))
            .describedAs("접수가 변환 행을 잡지 못했다")
            .isTrue()

        val deletion = pool.submit { tx.executeWithoutResult { delete(seeded) } }
        awaitBlockedBackend()
        deleterBlocked.countDown()

        // 삭제를 **먼저** 거둔다. 접수가 기다리는 구현이면 사이클이 닫혀 PostgreSQL 이 둘 중
        // 하나를 죽이는데, 대개 삭제가 제물이다 — 그 `deadlock detected` 가 여기서 그대로 보인다.
        deletion.get(HANDOFF_SECONDS, TimeUnit.SECONDS)
        assertThat(intake.get(HANDOFF_SECONDS, TimeUnit.SECONDS))
            .describedAs("접수가 삭제가 쥔 문서 행을 건너뛰지 않았다")
            .isFalse()
        assertThat(documentCount(seeded.ownerId)).isZero()
        return seeded
    }

    /**
     * 삭제가 쥔 문서 행 앞에서 접수가 **기다리지 않고 건너뛰는가.** 삭제가 `DELETE` 로 문서 행에
     * 거는 잠금을 그대로 세워 두고 접수를 통째로 돌린다 — 기다리는 구현이면 여기서 시간이 다 간다.
     */
    private fun assertAcceptanceSkipsHeldDocument(lock: (UUID, UUID) -> Any?) {
        val seeded = seedConversion(SETTLED)
        dataSource.connection.use { deleter ->
            deleter.autoCommit = false
            execute(deleter, "SELECT id FROM documents WHERE id = '${seeded.documentId}' FOR UPDATE")

            val acceptance = pool.submit<Any?> { tx.execute { lock(seeded.ownerId, seeded.conversionId) } }

            assertThat(acceptance.get(HANDOFF_SECONDS, TimeUnit.SECONDS))
                .describedAs("접수가 삭제가 쥔 문서 행을 기다렸다 — 건너뛰고 404 로 끝나야 한다")
                .isNull()
            deleter.rollback()
        }
    }

    /** 접수의 첫 문장 — 저장소 상수를 그대로 돌린다. */
    private fun intakeConversionLock(seeded: SeededConversion): Boolean =
        runIntakeLock(JdbcIllustrationGenerationJobRepository.LOCK_CONTEXT_SQL, seeded)

    /** 접수의 둘째 문장 — `SKIP LOCKED` 라 삭제 뒤에서 기다리지 않는다. */
    private fun intakeDocumentLock(seeded: SeededConversion): Boolean =
        runIntakeLock(JdbcIllustrationGenerationJobRepository.SKIP_LOCKED_DOCUMENT_SQL, seeded)

    private fun runIntakeLock(
        sql: String,
        seeded: SeededConversion,
    ): Boolean =
        jdbc
            .sql(sql)
            .param("ownerId", seeded.ownerId)
            .param("conversionId", seeded.conversionId)
            .query { _, _ -> true }
            .optional()
            .orElse(false)

    /** `DocumentService.delete` 의 잠금 순서 그대로 — 예약을 읽고 문서를 지운다. */
    private fun runDeletePathLocks(seeded: SeededConversion) {
        conversions.lockPendingReservation(seeded.ownerId, seeded.documentId)
    }

    /**
     * 다른 백엔드 하나가 **실제로 잠금을 기다리는 것**을 보고 나서야 다음 문장으로 간다 —
     * 시간 맞추기가 아니라 관찰된 대기다.
     */
    private fun awaitBlockedBackend() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HANDOFF_SECONDS)
        while (System.nanoTime() < deadline) {
            if (blockedBackends() >= 1) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("다른 트랜잭션이 잠금을 기다리지 않았다 — 경합이 성립하지 않았다")
    }

    /**
     * 폴링마다 `pg_stat_clear_snapshot()` 을 먼저 부르는 것은 **방어적 조치**다.
     *
     * `pg_stat_activity` 자체는 트랜잭션 안에서도 갱신된다 — `stats_fetch_consistency` 가 다스리는
     * 것은 누적 통계 뷰이지 이 뷰가 아니고, PG16 에서 clear 없이도 대기 백엔드가 보이는 것을
     * 확인했다. 다만 PostgreSQL 문서가 **반복 폴링에는 스냅샷을 버리라고 권하고**,
     * `stats_fetch_consistency = snapshot` 으로 운영하는 서버에서는 실제로 필요해진다. 한 문장
     * 값으로 그 변수에 대한 의존을 지우는 편이 낫다.
     */
    private fun blockedBackends(): Int {
        jdbc.sql("SELECT pg_stat_clear_snapshot()").query { _, _ -> true }.single()
        return queryStrings(
            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'",
        ).single().toInt()
    }

    private fun seedConversion(kind: SeedKind): SeededConversion {
        val owner = insertOwner()
        val document = insertDocument(owner, kind.conversionStatus, kind.creditsReserved)
        return SeededConversion(owner.ownerId, document.documentId, document.conversionId)
    }

    /**
     * worker 정산과 같은 순서(작업 행 → 이용량 계정)로 잠그는 트랜잭션을 띄워 놓고 그 사이에
     * [delete] 를 돌린다. 삭제가 작업 행 앞에서 **실제로 멈춰 서 있는 것**을 본 뒤에야 worker 가
     * 계정을 잡는다 — 시간 맞추기가 아니라 관찰된 대기다.
     */
    private fun raceDeletionAgainstHolderOf(
        seeded: Seeded,
        heldTable: String,
        delete: () -> Unit,
    ) {
        val heldJobId = seeded.jobIds.getValue(heldTable)
        dataSource.connection.use { worker ->
            worker.autoCommit = false
            // worker 가 `lockIfHeld` 로 잡는 그 행이다.
            execute(worker, "SELECT id FROM $heldTable WHERE id = '$heldJobId' FOR UPDATE")
            val deletion = pool.submit { delete() }

            assertThatThrownBy { deletion.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }
                .describedAs("삭제가 worker 가 쥔 작업 행을 기다리지 않았다 — 경합이 성립하지 않았다")
                .isInstanceOf(TimeoutException::class.java)

            // 정산이 계정 행을 잡는 자리 그대로다. 금액은 건드리지 않는다 — 이 시험이 재는 것은
            // 잠금 순서이지 정산 산술이 아니다(그쪽은 흐름 시험의 몫). 삭제가 계정을 먼저 쥐고
            // 있으면 여기서 `deadlock detected` 로 끊긴다.
            execute(
                worker,
                "UPDATE workspace_credit_accounts SET updated_at = now() " +
                    "WHERE workspace_id = '${seeded.workspaceId}'",
            )
            worker.commit()
            deletion.get(HANDOFF_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun deleteDocument(documentId: UUID) {
        execute("DELETE FROM documents WHERE id = '$documentId'")
    }

    /** 탈퇴 경로 그대로다 — 한 트랜잭션 안에서 문서를 먼저 지우고 사용자를 지운다. */
    private fun deleteAccount(ownerId: UUID) {
        tx.executeWithoutResult { accountDeletion.deleteUser(ownerId) }
    }

    /** 문서 한 건 삭제 — 원장이 남아 있어 해제가 **정확히 한 번**인지까지 본다. */
    private fun assertSettledOnce(seeded: Seeded) {
        seeded.jobIds.forEach { (table, jobId) ->
            assertThat(jobState(table, jobId)).isEqualTo("superseded|released")
            assertThat(releaseCount(table, jobId))
                .describedAs("$table 해제 거래 수")
                .isEqualTo(1)
        }
        assertThat(documentCount(seeded.ownerId)).isZero()
    }

    /** 탈퇴는 원장을 사용자와 함께 지운다(`owner_user_id` CASCADE) — 작업 행의 정산만 남는다. */
    private fun assertSettledAfterAccountDeletion(seeded: Seeded) {
        seeded.jobIds.forEach { (table, jobId) ->
            assertThat(jobState(table, jobId)).isEqualTo("superseded|released")
        }
        assertThat(documentCount(seeded.ownerId)).isZero()
        assertThat(queryStrings("SELECT count(*) FROM users WHERE id = '${seeded.ownerId}'").single())
            .isEqualTo("0")
    }

    /** 한 문서에 세 가족이 모두 활성 — 트리거 하나가 도는 자리다. */
    private fun seedOneDocument(): Seeded {
        val owner = insertOwner()
        val document = insertDocument(owner)
        return Seeded(
            owner = owner,
            documentIds = listOf(document.documentId),
            jobIds =
                mapOf(
                    ACTION_GUIDE_JOBS to insertJob(owner, document, ACTION_GUIDE_JOBS),
                    GENERATION_JOBS to insertJob(owner, document, GENERATION_JOBS),
                    SUGGESTION_JOBS to insertJob(owner, document, SUGGESTION_JOBS),
                ),
        )
    }

    /** 문서 둘에 가족 하나씩 — 한 문장이 여러 문서를 지우는 자리다. */
    private fun seedTwoDocuments(
        first: String,
        second: String,
    ): Seeded {
        val owner = insertOwner()
        val firstDocument = insertDocument(owner)
        val secondDocument = insertDocument(owner)
        val jobIds =
            mapOf(
                first to insertJob(owner, firstDocument, first),
                second to insertJob(owner, secondDocument, second),
            )
        // 삭제가 훑는 물리 순서가 이 시나리오의 전제다 — 앞 문서를 정산한 **뒤에** 뒤 문서에서
        // 막혀야 사이클이 열린다. 순서가 뒤집히면 경합이 성립하지 않으므로 먼저 세워 둔다.
        assertThat(physicalOrder(owner.ownerId))
            .describedAs("삭제가 훑을 문서 순서")
            .containsExactly(firstDocument.documentId, secondDocument.documentId)
        return Seeded(
            owner = owner,
            documentIds = listOf(firstDocument.documentId, secondDocument.documentId),
            jobIds = jobIds,
        )
    }

    private fun insertOwner(): Owner {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        execute(
            "INSERT INTO users (id,email,password_hash) " +
                "VALUES ('$ownerId','lock-$ownerId@example.test','$DUMMY_PHC')",
        )
        execute("INSERT INTO workspaces (id,user_id,name) VALUES ('$workspaceId','$ownerId','공간')")
        execute(
            "INSERT INTO workspace_credit_accounts (workspace_id,balance,reserved) " +
                "VALUES ('$workspaceId',10,3)",
        )
        return Owner(ownerId, workspaceId)
    }

    private fun insertDocument(
        owner: Owner,
        conversionStatus: String = "done",
        creditsReserved: String = "0",
    ): SeededDocument {
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        execute(
            """
            INSERT INTO documents
                (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                 encryption_scheme,key_version,char_count)
            VALUES ('$documentId','${owner.ownerId}','${owner.workspaceId}','제목','txt','\x01'::bytea,
                    'aes256gcm-v1',1,10)
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO conversions
                (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision,
                 credits_reserved)
            VALUES ('$conversionId','$documentId','$conversionStatus','\x02'::bytea,'aes256gcm-v1',1,1,
                    $creditsReserved)
            """.trimIndent(),
        )
        return SeededDocument(documentId, conversionId)
    }

    /** 활성(queued) 작업 한 건과 그 예약 원장 한 행 — 운영이 만드는 모양 그대로다. */
    private fun insertJob(
        owner: Owner,
        document: SeededDocument,
        table: String,
    ): UUID {
        val jobId = UUID.randomUUID()
        val reasonColumn =
            when (table) {
                ACTION_GUIDE_JOBS -> "action_guide_job_id"
                GENERATION_JOBS -> "illustration_generation_job_id"
                else -> "illustration_suggestion_job_id"
            }
        val reason =
            when (table) {
                ACTION_GUIDE_JOBS -> "action_guide"
                GENERATION_JOBS -> "illustration_generation"
                else -> "illustration_suggestion"
            }
        val generationColumns =
            if (table == GENERATION_JOBS) {
                ", suggestion_result_id, suggestion_id, analysis_version, configuration_revision," +
                    " input_payload_encrypted, encryption_scheme, key_version"
            } else {
                ""
            }
        val generationValues =
            if (table == GENERATION_JOBS) {
                ", gen_random_uuid(), gen_random_uuid(), 'r7-illustration-suggestion-5', '$FINGERPRINT'," +
                    " '\\x03'::bytea, 'aes256gcm-v1', 1"
            } else {
                ""
            }
        execute(
            """
            INSERT INTO $table
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 expected_content_revision, based_on_content_revision, input_fingerprint$generationColumns,
                 status, settlement, reserved_credits)
            VALUES ('$jobId', gen_random_uuid(), '${owner.ownerId}', '${owner.workspaceId}',
                    '${document.documentId}', '${document.conversionId}',
                    1, 1, '$FINGERPRINT'$generationValues, 'queued', 'reserved', 1.0)
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO credit_transactions
                (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
                 reserved_delta, reason, $reasonColumn)
            VALUES (gen_random_uuid(), '${owner.workspaceId}', '${owner.ownerId}',
                    '${document.documentId}', 'reserve', 0, 1.0, '$reason', '$jobId')
            """.trimIndent(),
        )
        return jobId
    }

    /** 삭제 문장이 행을 훑는 순서 — `ORDER BY` 없는 seq scan 은 물리 순서를 따른다. */
    private fun physicalOrder(ownerId: UUID): List<UUID> =
        jdbc
            .sql("SELECT id FROM documents WHERE user_id = '$ownerId' ORDER BY ctid")
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun jobState(
        table: String,
        jobId: UUID,
    ): String = queryStrings("SELECT status || '|' || settlement FROM $table WHERE id = '$jobId'").single()

    private fun releaseCount(
        table: String,
        jobId: UUID,
    ): Int {
        val column =
            when (table) {
                ACTION_GUIDE_JOBS -> "action_guide_job_id"
                GENERATION_JOBS -> "illustration_generation_job_id"
                else -> "illustration_suggestion_job_id"
            }
        return queryStrings(
            "SELECT count(*) FROM credit_transactions WHERE kind = 'release' AND $column = '$jobId'",
        ).single().toInt()
    }

    private fun documentCount(ownerId: UUID): Int =
        queryStrings("SELECT count(*) FROM documents WHERE user_id = '$ownerId'").single().toInt()

    private fun queryStrings(sql: String): List<String> = jdbc.sql(sql).query { rs, _ -> rs.getString(1) }.list()

    private fun execute(sql: String) {
        jdbc.sql(sql).update()
    }

    private fun execute(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { it.execute(sql) }
    }

    private class Owner(
        val ownerId: UUID,
        val workspaceId: UUID,
    )

    private class SeededDocument(
        val documentId: UUID,
        val conversionId: UUID,
    )

    private class SeededConversion(
        val ownerId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    )

    /**
     * 삭제가 변환 행을 잠그는 길이 갈리는 두 상태.
     *
     * [RESERVED] 는 `lockPendingReservation` 이 직접 고르는 상태고, [SETTLED] 는 그것이 한 행도
     * 고르지 않는 상태다 — 그래서 접수가 실제로 쓰는 `done` 변환이 더 위험한 쪽이다.
     */
    private class SeedKind(
        val conversionStatus: String,
        val creditsReserved: String,
    )

    private class Seeded(
        val owner: Owner,
        val documentIds: List<UUID>,
        val jobIds: Map<String, UUID>,
    ) {
        val ownerId: UUID get() = owner.ownerId
        val workspaceId: UUID get() = owner.workspaceId
    }

    private companion object {
        const val ACTION_GUIDE_JOBS = "action_guide_jobs"
        const val GENERATION_JOBS = "illustration_generation_jobs"
        const val SUGGESTION_JOBS = "illustration_suggestion_jobs"
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** 접수 저장소를 세우는 데만 쓴다 — 이 시험은 상한이 아니라 잠금 순서를 잰다. */
        const val MAX_PROVIDER_ATTEMPTS = 1

        /** `lockPendingReservation` 이 고르는 상태 — 삭제가 그 문장 하나로 변환 행을 잠근다. */
        val RESERVED = SeedKind(conversionStatus = "processing", creditsReserved = "1.0")

        /** 접수가 요구하는 상태 — `lockPendingReservation` 이 한 행도 고르지 않는다(리뷰 B1). */
        val SETTLED = SeedKind(conversionStatus = "done", creditsReserved = "0")

        const val POLL_MILLIS = 50L

        /** 두 작업 표의 `…_fingerprint_length` 가 정확히 64자를 요구한다. */
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** 삭제가 작업 행 앞에서 멈춰 선 것을 확인할 만큼은 길고, 시험을 늘어뜨리지 않을 만큼은 짧은 창. */
        const val BLOCKED_MILLIS = 500L
        const val HANDOFF_SECONDS = 30L
    }
}

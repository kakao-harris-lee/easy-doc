package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.EmailVerificationService
import kr.easydoc.application.auth.VerificationCodeStore
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 미검증 계정 파기(`JdbcUnverifiedAccountPurge`)와 이메일 인증 확인
 * (`EmailVerificationService.confirm`) 의 경합 — team lead 리뷰(2026-09-07).
 *
 * **원래 결함.** `confirm()` 이 사용자를 잠그지 않고 읽은 뒤, `markEmailVerified` 는 잠금
 * 없는 bare `UPDATE` 로 돌았다. 파기 배치가 같은 행을 먼저 지우고 커밋하면 그 `UPDATE`
 * 는 0행에 "성공"했지만 `confirm()` 은 그래도 정상 응답을 돌려줬다 — 계정은 없는데 API 는
 * "인증 완료"라고 답하는 상태였다.
 *
 * **재현 방법.** 스레드 1(파기 흉내)이 파기의 잠금 질의와 같은 `SELECT … FOR UPDATE` 를
 * 원시 JDBC 트랜잭션 안에서 열어 둔 채로 대상 행을 잠근다 — 진짜 `JdbcUnverifiedAccountPurge`
 * 를 그대로 부르면 잠금과 삭제가 한 호출 안에서 곧바로 끝나 버려 경합을 재현할 수 없으므로,
 * 같은 잠금·삭제 SQL 을 손으로 갈라 사이에 신호를 끼운다. 스레드 2 는 **실물**
 * `EmailVerificationService.confirm()` 을 부른다 — 이 시점부터 `lockForUpdate` 가 스레드
 * 1 의 트랜잭션이 끝날 때까지 블록돼야 한다. 스레드 1 이 커밋(삭제 확정)하면 스레드 2 의
 * 잠금은 빈 결과를 받고, 고친 `confirm()` 은 그 경우 계정 소멸 예외를 던져야 한다 —
 * **"confirm 이 성공했는데 행이 이미 없다"는 절대 나오면 안 된다.**
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnverifiedAccountPurgeConfirmRaceTest {
    private lateinit var database: DatabaseHandle
    private lateinit var interference: ExecutorService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("unverified_purge_confirm_race")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        interference = Executors.newFixedThreadPool(2)
    }

    @AfterAll
    fun shutdown() {
        interference.shutdownNow()
    }

    @Test
    @DisplayName("파기가 행을 먼저 잠그고 지우면, 동시 confirm() 은 성공을 돌려주지 않고 계정 소멸 예외를 던진다")
    fun `파기가 이기면 confirm 은 계정 소멸로 실패한다`() {
        val userId = insertUnverifiedUser(ageHours = 25)

        val purgeLockAcquired = CountDownLatch(1)
        val releasePurgeLock = CountDownLatch(1)

        val purgeAttempt =
            interference.submit<Unit> {
                simulatePurgeHoldingLockThenDelete(userId, purgeLockAcquired, releasePurgeLock)
            }

        // 파기가 행을 잠글 때까지 기다린 뒤에만 confirm 을 부른다 — 그래야 confirm 의
        // `lockForUpdate` 가 파기 트랜잭션이 끝날 때까지 블록되는 상황을 확실히 만든다.
        assertThat(purgeLockAcquired.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .withFailMessage("파기 스레드가 시간 안에 행을 잠그지 못했다")
            .isTrue()

        val confirmAttempt =
            interference.submit<Result<Unit>> {
                // confirm 의 SELECT 가 실제로 DB 에 도달해 블록을 시작할 시간을 준다 —
                // 그렇지 않으면 파기 잠금 해제 신호가 confirm 의 질의보다 먼저 나갈 수 있다.
                Thread.sleep(BLOCK_SETTLE_MILLIS)
                releasePurgeLock.countDown()
                runCatching { confirmService().confirm(userId, ANY_CODE) }
            }

        purgeAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val confirmResult = confirmAttempt.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val rowExists = database.queryInt("SELECT count(*) FROM users WHERE id = '$userId'") == 1

        assertThat(confirmResult.isSuccess && !rowExists)
            .withFailMessage(
                "confirm() 이 성공을 돌려줬는데 계정 행은 이미 지워졌다 — " +
                    "\"인증 완료\"라고 답했지만 계정이 없는 상태다(원래 결함이 되살아났다)",
            ).isFalse()
        assertThat(rowExists)
            .withFailMessage("파기가 먼저 잠그고 커밋했으니 행은 지워져 있어야 한다 — 이 테스트의 전제가 깨졌다")
            .isFalse()
        assertThat(confirmResult.isFailure)
            .withFailMessage("행이 없는데 confirm() 이 예외 없이 끝났다: %s", confirmResult)
            .isTrue()
        assertThat(confirmResult.exceptionOrNull()).isInstanceOf(InvalidCredentialsException::class.java)
    }

    /**
     * `JdbcUnverifiedAccountPurge` 의 잠금·삭제 SQL(`LOCK_CANDIDATES_SQL`·`deleteSql`)과
     * 같은 조건을 원시 JDBC 트랜잭션 하나로 재현한다 — 잠금을 잡은 뒤 신호를 보내고,
     * 해제 신호를 받으면 같은 `AND email_verified_at IS NULL` 조건으로 지운 뒤 커밋한다.
     */
    private fun simulatePurgeHoldingLockThenDelete(
        userId: UUID,
        lockAcquired: CountDownLatch,
        release: CountDownLatch,
    ) {
        dataSource().connection.use { connection ->
            connection.autoCommit = false
            connection
                .prepareStatement("SELECT id FROM users WHERE id = ? AND email_verified_at IS NULL FOR UPDATE")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { it.next() }
                }
            lockAcquired.countDown()
            release.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            connection
                .prepareStatement("DELETE FROM users WHERE id = ? AND email_verified_at IS NULL")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
            connection.commit()
        }
    }

    /** 실물 `EmailVerificationService` — `confirm()` 만 부르므로 메일은 부르면 끊는 대역이다. */
    private fun confirmService(): EmailVerificationService =
        EmailVerificationService(
            users = JdbcUserRepository(JdbcClient.create(dataSource())),
            codes = AlwaysMatchingVerificationCodeStore,
            mail = NeverCalledMailSender,
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource()))),
            codeTtl = Duration.ofMinutes(10),
            resendCooldown = Duration.ofSeconds(60),
            maxAttempts = 5,
        )

    private fun insertUnverifiedUser(ageHours: Int): UUID {
        val userId = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO users (id, email, password_hash, created_at, email_verified_at)
            VALUES ('$userId', '${UUID.randomUUID()}@example.test', 'argon2id${'$'}dummy',
                    now() - interval '$ageHours hours', NULL);
            """.trimIndent(),
        )
        return userId
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    /** 이 테스트는 코드값을 재지 않는다 — 어떤 코드든 통과시켜 잠금 경합만 남긴다. */
    private object AlwaysMatchingVerificationCodeStore : VerificationCodeStore {
        override fun issue(
            userId: UUID,
            ttl: Duration,
            cooldown: Duration,
        ): String = error("이 테스트는 코드 발급을 부르지 않는다")

        override fun attempt(
            userId: UUID,
            code: String,
            maxAttempts: Int,
        ): Boolean = true
    }

    private object NeverCalledMailSender : MailSender {
        override fun send(message: OutboundMail): MailDelivery = error("이 테스트는 메일 발송을 부르지 않는다")
    }

    private companion object {
        const val ANY_CODE = "000000"

        /** 방해 스레드가 서로를 기다리고, 잠긴 트랜잭션이 풀리기를 기다리는 상한. */
        const val TASK_TIMEOUT_SECONDS = 30L

        /** confirm 의 잠금 시도가 실제로 블록을 시작할 시간 — 짧지만 0은 아니다. */
        const val BLOCK_SETTLE_MILLIS = 300L
    }
}

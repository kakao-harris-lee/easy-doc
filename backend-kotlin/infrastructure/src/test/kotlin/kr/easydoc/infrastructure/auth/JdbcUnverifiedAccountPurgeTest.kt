package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.PurgeUnverifiedAccounts
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UnverifiedAccountPurgeObserver
import kr.easydoc.application.auth.UnverifiedAccountPurgePolicy
import kr.easydoc.application.auth.UnverifiedAccountPurgeResult
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * 미검증 계정 파기 — 실제 PostgreSQL 에서 `created_at` 기준 TTL 판정, 문서 보유 계정
 * 건너뛰기, FK CASCADE 삭제를 잰다(`docs/kotlin-redevelopment-backlog.md` §1.4 ⑵ ⓐ).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcUnverifiedAccountPurgeTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("unverified_account_purge")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
    }

    @BeforeEach
    fun cleanUsers() {
        // users 를 지우면 workspaces·documents·user_identities·oauth_states·
        // email_verification_codes·password_reset_codes 가 전부 CASCADE 로 함께 사라진다.
        jdbc.sql("DELETE FROM users").update()
    }

    @Test
    @DisplayName("TTL 을 넘긴 미검증 비밀번호 가입 계정은 신원·작업 공간·인증 코드까지 함께 사라진다")
    fun `미검증 비밀번호 가입 계정을 지운다`() {
        val userId = insertUser(ageHours = 25, emailVerified = false)
        val workspaceId = insertWorkspace(userId)
        insertVerificationCode(userId)

        val result = purge(ttlHours = 24).run()

        assertThat(result.deleted).isEqualTo(1)
        assertThat(userExists(userId)).isFalse()
        assertThat(workspaceExists(workspaceId)).isFalse()
        assertThat(verificationCodeCountFor(userId)).isZero()
    }

    @Test
    @DisplayName("비밀번호 없이 가입한 미검증 소셜 계정도 신원까지 함께 사라진다")
    fun `미검증 소셜 가입 계정을 지운다`() {
        val userId = insertUser(ageHours = 25, emailVerified = false, passwordHash = null)
        val identityId = insertUserIdentity(userId)

        val result = purge(ttlHours = 24).run()

        assertThat(result.deleted).isEqualTo(1)
        assertThat(userExists(userId)).isFalse()
        assertThat(userIdentityExists(identityId)).isFalse()
    }

    @Test
    @DisplayName("검증된 계정은 나이와 무관하게 남는다")
    fun `검증된 계정은 남긴다`() {
        val userId = insertUser(ageHours = 100, emailVerified = true)

        val result = purge(ttlHours = 24).run()

        assertThat(result.deleted).isZero()
        assertThat(userExists(userId)).isTrue()
    }

    @Test
    @DisplayName("TTL 보다 어린 미검증 계정은 남는다")
    fun `TTL 안의 계정은 남긴다`() {
        val userId = insertUser(ageHours = 1, emailVerified = false)

        val result = purge(ttlHours = 24).run()

        assertThat(result.deleted).isZero()
        assertThat(userExists(userId)).isTrue()
    }

    @Test
    @DisplayName("생성 시각이 정확히 TTL 경계면 아직 지우지 않는다")
    fun `경계값은 지우지 않는다`() {
        // now(고정 시계) - ttl 과 정확히 같은 created_at. `created_at < createdBefore` 는
        // 엄격한 부등호라 경계값 자체는 대상이 아니다.
        val userId = insertUserAt(createdAt = FIXED_NOW.minus(Duration.ofHours(24)), emailVerified = false)

        val result = purge(ttlHours = 24, now = FIXED_NOW).run()

        assertThat(result.deleted).isZero()
        assertThat(userExists(userId)).isTrue()
    }

    @Test
    @DisplayName("경계보다 1초라도 오래되면 지운다")
    fun `경계보다 1초 오래되면 지운다`() {
        val userId =
            insertUserAt(createdAt = FIXED_NOW.minus(Duration.ofHours(24)).minusSeconds(1), emailVerified = false)

        val result = purge(ttlHours = 24, now = FIXED_NOW).run()

        assertThat(result.deleted).isEqualTo(1)
        assertThat(userExists(userId)).isFalse()
    }

    @Test
    @DisplayName("문서를 가진 미검증 계정은 건너뛰고 개수만 센다")
    fun `문서 있는 계정은 건드리지 않는다`() {
        val userId = insertUser(ageHours = 25, emailVerified = false)
        val workspaceId = insertWorkspace(userId)
        insertDocument(userId, workspaceId)

        val result = purge(ttlHours = 24).run()

        assertThat(result.deleted).isZero()
        assertThat(result.skippedWithDocuments).isEqualTo(1)
        assertThat(userExists(userId)).isTrue()
    }

    @Test
    @DisplayName("한 스케줄이 배치를 넘겨 대상을 모두 지운다")
    fun `배치보다 많은 대상을 한 번에 지운다`() {
        val first = insertUser(ageHours = 100, emailVerified = false)
        val second = insertUser(ageHours = 50, emailVerified = false)
        val third = insertUser(ageHours = 25, emailVerified = false)

        val result = purge(ttlHours = 24, batchSize = 2).run()

        assertThat(result.deleted).isEqualTo(3)
        assertThat(userExists(first)).isFalse()
        assertThat(userExists(second)).isFalse()
        assertThat(userExists(third)).isFalse()
    }

    @Test
    @DisplayName("결과 문자열에 이메일이 없다")
    fun `결과에 이메일이 없다`() {
        insertUser(ageHours = 25, emailVerified = false, email = "purge-target@example.com")

        val result = purge(ttlHours = 24).run()

        assertThat(result.toString()).doesNotContain("purge-target@example.com")
        assertThat(result.toString()).doesNotContain("@")
    }

    private fun purge(
        ttlHours: Long,
        batchSize: Int = BATCH,
        now: Instant = FIXED_NOW,
    ): PurgeUnverifiedAccounts =
        PurgeUnverifiedAccounts(
            store = JdbcUnverifiedAccountPurge(jdbc),
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            observer = NoopObserver,
            policy =
                UnverifiedAccountPurgePolicy(enabled = true, ttl = Duration.ofHours(ttlHours), batchSize = batchSize),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    /**
     * [ageHours] 는 [FIXED_NOW] 기준이다 — `purge()` 의 기본 `now` 도 같은 상수라, 실제
     * 벽시계(`Instant.now()`)를 썼다면 이 파일이 오래 살아 있는 세션에서 도는 동안 실제
     * 시각이 [FIXED_NOW] 에서 멀어질수록 "25시간 전" 이 더 이상 파기 기준보다 오래되지
     * 않게 되는 시간 경합 버그가 있었다(리뷰 후속, 2026-09-07 — `result.deleted` 가
     * 0 이나 후보 수보다 적게 나오던 원인). 두 경계 테스트가 이미 [FIXED_NOW] 를 직접 쓰던
     * 것과 같은 기준으로 통일한다.
     */
    private fun insertUser(
        ageHours: Int,
        emailVerified: Boolean,
        passwordHash: String? = "argon2id\$dummy",
        email: String = "${UUID.randomUUID()}@example.com",
    ): UUID =
        insertUserAt(
            createdAt = FIXED_NOW.minus(Duration.ofHours(ageHours.toLong())),
            emailVerified = emailVerified,
            passwordHash = passwordHash,
            email = email,
        )

    private fun insertUserAt(
        createdAt: Instant,
        emailVerified: Boolean,
        passwordHash: String? = "argon2id\$dummy",
        email: String = "${UUID.randomUUID()}@example.com",
    ): UUID {
        val userId = UUID.randomUUID()
        val passwordSql = passwordHash?.let { "'$it'" } ?: "NULL"
        val verifiedAtSql = if (emailVerified) "'${java.sql.Timestamp.from(createdAt)}'" else "NULL"
        database.execute(
            """
            INSERT INTO users (id, email, password_hash, created_at, email_verified_at)
            VALUES ('$userId', '$email', $passwordSql, '${java.sql.Timestamp.from(createdAt)}', $verifiedAtSql);
            """.trimIndent(),
        )
        return userId
    }

    private fun insertWorkspace(userId: UUID): UUID {
        val workspaceId = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO workspaces (id, user_id, name) VALUES ('$workspaceId', '$userId', 'default');
            """.trimIndent(),
        )
        return workspaceId
    }

    private fun insertUserIdentity(userId: UUID): UUID {
        val identityId = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO user_identities (id, user_id, provider, provider_user_id, email, email_verified)
            VALUES ('$identityId', '$userId', 'naver', 'naver-${UUID.randomUUID()}', NULL, false);
            """.trimIndent(),
        )
        return identityId
    }

    private fun insertVerificationCode(userId: UUID) {
        database.execute(
            """
            INSERT INTO email_verification_codes (id, user_id, code_hash, salt, expires_at)
            VALUES ('${UUID.randomUUID()}', '$userId', 'hash', 'salt', now() + interval '10 minutes');
            """.trimIndent(),
        )
    }

    private fun insertDocument(
        userId: UUID,
        workspaceId: UUID,
    ) {
        database.execute(
            """
            INSERT INTO documents
                (id, user_id, title, source_format, source_text_encrypted, encryption_scheme, key_version,
                 char_count, workspace_id)
            VALUES ('${UUID.randomUUID()}', '$userId', 't', 'txt', '\x00'::bytea, 'aes256gcm-v1', 1, 1, '$workspaceId');
            """.trimIndent(),
        )
    }

    private fun userExists(userId: UUID): Boolean =
        database.queryInt("SELECT count(*) FROM users WHERE id = '$userId'") == 1

    private fun workspaceExists(workspaceId: UUID): Boolean =
        database.queryInt("SELECT count(*) FROM workspaces WHERE id = '$workspaceId'") == 1

    private fun userIdentityExists(identityId: UUID): Boolean =
        database.queryInt("SELECT count(*) FROM user_identities WHERE id = '$identityId'") == 1

    private fun verificationCodeCountFor(userId: UUID): Int =
        database.queryInt("SELECT count(*) FROM email_verification_codes WHERE user_id = '$userId'")

    private object NoopObserver : UnverifiedAccountPurgeObserver {
        override fun record(result: UnverifiedAccountPurgeResult) = Unit
    }

    private companion object {
        const val BATCH: Int = 100
        val FIXED_NOW: Instant = Instant.parse("2026-09-07T00:00:00Z")
    }
}

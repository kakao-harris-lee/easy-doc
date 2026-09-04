package kr.easydoc.infrastructure.auth

import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.workspace.DEFAULT_WORKSPACE_NAME
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

/** `users`·`workspaces` 저장소 — 실제 PostgreSQL 에서 잰다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcUserRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var transaction: SpringTransactionRunner

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("auth_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = dataSource()
        val jdbcClient = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbcClient)
        workspaces = JdbcWorkspaceRepository(jdbcClient)
        transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
    }

    @Test
    @DisplayName("가입한 사용자를 이메일과 식별자로 다시 읽는다")
    fun `저장하고 다시 읽는다`() {
        val email = uniqueEmail()

        val created = users.create(email, HASH)

        assertThat(users.findByEmail(email)?.user).isEqualTo(created)
        assertThat(users.findById(created.id)).isEqualTo(created)

        assertThat(created.createdAt).isNotNull()
    }

    @Test
    @DisplayName("없는 이메일·식별자는 null 이다")
    fun `없는 사용자는 null 이다`() {
        assertThat(users.findByEmail(uniqueEmail())).isNull()
        assertThat(users.findById(UUID.randomUUID())).isNull()
    }

    /** X-1 — 인증 경계가 매 요청 부르는 질의다. 양쪽 갈래를 다 잰다. */
    @Test
    @DisplayName("exists 가 있는 계정에 true, 없는 계정에 false 다")
    fun `존재 확인이 양쪽으로 갈린다`() {
        val created = users.create(uniqueEmail(), HASH)

        assertThat(users.exists(created.id)).isTrue()
        assertThat(users.exists(UUID.randomUUID())).isFalse()
    }

    @Test
    @DisplayName("같은 이메일을 두 번 넣으면 도메인 예외다 — 유일 인덱스가 판정한다")
    fun `중복 이메일은 도메인 예외다`() {
        val email = uniqueEmail()
        users.create(email, HASH)

        assertThatThrownBy { users.create(email, HASH) }
            .isInstanceOf(EmailAlreadyRegisteredException::class.java)
    }

    @Test
    @DisplayName("예외 메시지와 원인 체인에 이메일·해시가 실리지 않는다")
    fun `제약 위반이 행 내용을 흘리지 않는다`() {
        val email = uniqueEmail()
        users.create(email, HASH)

        val failure = runCatching { users.create(email, HASH) }.exceptionOrNull()

        assertThat(failure?.message).doesNotContain(email).doesNotContain(HASH.reveal())
        assertThat(failure?.cause).isNull()
    }

    @Test
    @DisplayName("대문자가 섞인 이메일은 CHECK 제약에 걸려 500 계열로 끊긴다")
    fun `정규화되지 않은 이메일은 저장소 오류다`() {
        assertThatThrownBy { users.create("Mixed@Example.Test", HASH) }
            .isInstanceOf(StorageException::class.java)
    }

    @Test
    @DisplayName("재해시 결과가 반영된다")
    fun `비밀번호 해시를 갱신한다`() {
        val email = uniqueEmail()
        val created = users.create(email, HASH)
        val upgraded = PasswordHash("\$argon2id\$v=19\$m=65536,t=3,p=4\$YWJjZGVmZ2hpamtsbW5vcA\$dXBncmFkZWQ")

        users.updatePasswordHash(created.id, upgraded)

        assertThat(users.findByEmail(email)?.passwordHash).isEqualTo(upgraded)
    }

    @Test
    @DisplayName("비밀번호 없이 만든 사용자는 password_hash 가 null 이다 — 소셜 로그인 최초 가입")
    fun `비밀번호 없는 사용자를 만든다`() {
        val email = uniqueEmail()

        val created = users.createWithoutPassword(email, emailVerified = true)

        assertThat(users.findByEmail(email)?.user).isEqualTo(created)
        assertThat(users.findByEmail(email)?.passwordHash).isNull()
    }

    @Test
    @DisplayName("emailVerified=true 는 email_verified_at 을 생성 시각으로 채운다 — 제공자가 이미 검증했다")
    fun `소셜 최초 가입은 인증 완료로 만들어진다`() {
        val email = uniqueEmail()

        val created = users.createWithoutPassword(email, emailVerified = true)

        assertThat(created.emailVerifiedAt).isNotNull()
    }

    @Test
    @DisplayName("emailVerified=false 는 email_verified_at 을 비워 둔다")
    fun `emailVerified 거짓이면 미인증으로 만들어진다`() {
        val email = uniqueEmail()

        val created = users.createWithoutPassword(email, emailVerified = false)

        assertThat(created.emailVerifiedAt).isNull()
    }

    @Test
    @DisplayName("비밀번호 없는 사용자도 이메일 유일성은 그대로 지킨다")
    fun `비밀번호 없는 사용자도 이메일이 겹치면 도메인 예외다`() {
        val email = uniqueEmail()
        users.createWithoutPassword(email, emailVerified = true)

        assertThatThrownBy { users.createWithoutPassword(email, emailVerified = true) }
            .isInstanceOf(EmailAlreadyRegisteredException::class.java)
        assertThatThrownBy { users.create(email, HASH) }
            .isInstanceOf(EmailAlreadyRegisteredException::class.java)
    }

    @Test
    @DisplayName("비밀번호로 만든 사용자는 email_verified_at 이 비어 있다 — 인증 코드로 채워야 한다")
    fun `비밀번호 가입은 미인증으로 시작한다`() {
        val email = uniqueEmail()

        val created = users.create(email, HASH)

        assertThat(created.emailVerifiedAt).isNull()
    }

    @Test
    @DisplayName("markEmailVerified 는 email_verified_at 을 채운다 — 이미 채워졌으면 그대로 둔다(멱등)")
    fun `markEmailVerified 는 채우고 멱등이다`() {
        val email = uniqueEmail()
        val created = users.create(email, HASH)

        users.markEmailVerified(created.id)
        val firstVerifiedAt = users.findById(created.id)?.emailVerifiedAt
        assertThat(firstVerifiedAt).isNotNull()

        users.markEmailVerified(created.id)
        assertThat(users.findById(created.id)?.emailVerifiedAt).isEqualTo(firstVerifiedAt)
    }

    @Test
    @DisplayName("기본 작업 공간이 계약이 정한 이름으로 만들어진다")
    fun `기본 작업 공간을 만든다`() {
        val created = users.create(uniqueEmail(), HASH)

        val workspaceId = workspaces.createDefault(created.id)

        assertThat(database.queryFirstColumn("SELECT name FROM workspaces WHERE id = '$workspaceId'"))
            .containsExactly(DEFAULT_WORKSPACE_NAME)
    }

    /** 가입의 원자성 — 계정만 남는 상태를 만들지 않는다. */
    @Test
    @DisplayName("작업 공간 생성이 실패하면 사용자도 저장되지 않는다")
    fun `가입 트랜잭션이 원자적이다`() {
        val email = uniqueEmail()

        val failure =
            runCatching {
                transaction.inTransaction {
                    users.create(email, HASH)

                    workspaces.createDefault(UUID.randomUUID())
                }
            }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(users.findByEmail(email)).isNull()
    }

    /** URL 만으로 만든다 — 드라이버 클래스를 이름으로도 타입으로도 붙잡지 않는다. */
    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun uniqueEmail(): String = "repo${counter++}@example.test"

    private companion object {
        /** 형식만 맞는 합성 PHC. 여기서 재는 것은 저장·조회이지 해시의 정확성이 아니다. */
        val HASH = PasswordHash("\$argon2id\$v=19\$m=65536,t=3,p=4\$YWJjZGVmZ2hpamtsbW5vcA\$c3RvcmVkLWhhc2g")

        var counter = 0
    }
}

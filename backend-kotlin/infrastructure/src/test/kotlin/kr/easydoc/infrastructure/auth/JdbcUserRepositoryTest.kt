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

/**
 * `users`·`workspaces` 저장소 — **실제 PostgreSQL 에서** 잰다.
 *
 * 여기서 재는 것은 스키마가 실제로 강제하는 것들이다. 인메모리 대역으로는 **유일 인덱스가
 * 던지는 예외의 종류**도, **CHECK 제약**도, **트랜잭션 원자성**도 잴 수 없다 — 그 셋이
 * 이 파일의 이유다.
 */
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
        // created_at 은 DB DEFAULT now() 가 채운다 — 앱 시계와 갈리는 자리를 만들지 않는다.
        assertThat(created.createdAt).isNotNull()
    }

    @Test
    @DisplayName("없는 이메일·식별자는 null 이다")
    fun `없는 사용자는 null 이다`() {
        assertThat(users.findByEmail(uniqueEmail())).isNull()
        assertThat(users.findById(UUID.randomUUID())).isNull()
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

        // PostgreSQL 은 제약 위반 DETAIL 에 실패한 행 전체를 담는다. 원인을 이으면
        // 트레이스백을 통해 이메일과 해시가 로그로 나간다.
        assertThat(failure?.message).doesNotContain(email).doesNotContain(HASH.reveal())
        assertThat(failure?.cause).isNull()
    }

    @Test
    @DisplayName("대문자가 섞인 이메일은 CHECK 제약에 걸려 500 계열로 끊긴다")
    fun `정규화되지 않은 이메일은 저장소 오류다`() {
        // 서비스가 정규화를 건너뛰면 여기까지 온다. 사용자 잘못이 아니라 코드 버그이므로
        // 4xx 로 감싸지 않는다 — 감싸면 서버 버그가 조용히 묻힌다.
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
    @DisplayName("기본 작업 공간이 계약이 정한 이름으로 만들어진다")
    fun `기본 작업 공간을 만든다`() {
        val created = users.create(uniqueEmail(), HASH)

        val workspaceId = workspaces.createDefault(created.id)

        assertThat(database.queryFirstColumn("SELECT name FROM workspaces WHERE id = '$workspaceId'"))
            .containsExactly(DEFAULT_WORKSPACE_NAME)
    }

    /**
     * 가입의 원자성 — **계정만 남는 상태를 만들지 않는다.**
     *
     * 작업 공간 생성이 실패하면 사용자 행도 함께 사라져야 한다. 나눠 커밋하면 첫 업로드가
     * 갈 곳 없는 계정이 생기고, 그 계정은 스스로 복구되지 않는다.
     */
    @Test
    @DisplayName("작업 공간 생성이 실패하면 사용자도 저장되지 않는다")
    fun `가입 트랜잭션이 원자적이다`() {
        val email = uniqueEmail()

        val failure =
            runCatching {
                transaction.inTransaction {
                    users.create(email, HASH)
                    // 없는 사용자를 가리켜 FK 위반을 만든다 — 두 번째 문장이 실패하는 상황이다.
                    workspaces.createDefault(UUID.randomUUID())
                }
            }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(users.findByEmail(email)).isNull()
    }

    /**
     * URL 만으로 만든다 — 드라이버 **클래스**를 이름으로도 타입으로도 붙잡지 않는다.
     *
     * `postgresql` 은 이 모듈의 `runtimeOnly` 의존이라 테스트 컴파일 클래스패스에 없다.
     * 그 경계는 사고가 아니라 설계이므로 우회하려고 의존성을 올리지 않는다.
     */
    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun uniqueEmail(): String = "repo${counter++}@example.test"

    private companion object {
        /** 형식만 맞는 합성 PHC. 여기서 재는 것은 저장·조회이지 해시의 정확성이 아니다. */
        val HASH = PasswordHash("\$argon2id\$v=19\$m=65536,t=3,p=4\$YWJjZGVmZ2hpamtsbW5vcA\$c3RvcmVkLWhhc2g")

        var counter = 0
    }
}

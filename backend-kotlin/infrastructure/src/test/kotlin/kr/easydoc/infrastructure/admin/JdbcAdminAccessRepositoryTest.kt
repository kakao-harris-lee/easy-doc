package kr.easydoc.infrastructure.admin

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
import javax.sql.DataSource

/** `users.is_admin`·`email_verified_at` — 실제 PostgreSQL 에서만 잴 수 있는 것들. 스키마는 `V17__admin.sql`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAdminAccessRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcAdminAccessRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("admin_access_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(dataSource())
        repository = JdbcAdminAccessRepository(jdbc)
    }

    @Test
    @DisplayName("is_admin이 참이고 이메일이 검증됐으면 관리자다")
    fun `검증된 관리자는 참이다`() {
        val userId = newUser(emailVerified = true)
        repository.setIsAdmin(userId, true)

        assertThat(repository.isVerifiedAdmin(userId)).isTrue()
    }

    @Test
    @DisplayName("is_admin이 참이어도 이메일이 미검증이면 관리자가 아니다")
    fun `미검증 관리자는 거짓이다`() {
        val userId = newUser(emailVerified = false)
        repository.setIsAdmin(userId, true)

        assertThat(repository.isVerifiedAdmin(userId)).isFalse()
    }

    @Test
    @DisplayName("is_admin이 거짓이면 이메일이 검증돼도 관리자가 아니다")
    fun `일반 계정은 거짓이다`() {
        val userId = newUser(emailVerified = true)

        assertThat(repository.isVerifiedAdmin(userId)).isFalse()
    }

    @Test
    @DisplayName("setIsAdmin(false)로 회수하면 즉시 거짓이다")
    fun `회수는 즉시 반영된다`() {
        val userId = newUser(emailVerified = true)
        repository.setIsAdmin(userId, true)
        assertThat(repository.isVerifiedAdmin(userId)).isTrue()

        repository.setIsAdmin(userId, false)

        assertThat(repository.isVerifiedAdmin(userId)).isFalse()
    }

    @Test
    @DisplayName("존재하지 않는 계정은 관리자가 아니다")
    fun `존재하지 않는 계정은 거짓이다`() {
        assertThat(repository.isVerifiedAdmin(UUID.randomUUID())).isFalse()
    }

    private fun newUser(emailVerified: Boolean): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO users (id, email, password_hash, email_verified_at) " +
                    "VALUES (:id, :email, :hash, CASE WHEN :verified THEN now() ELSE NULL END)",
            ).param("id", id)
            .param("email", "admin-access-$id@example.test")
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
            .param("verified", emailVerified)
            .update()
        return id
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = database.jdbcUrl
            username = database.username
            password = database.password
        }
}

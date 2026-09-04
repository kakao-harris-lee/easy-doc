package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import javax.sql.DataSource

/** `user_identities` 저장소 — 실제 PostgreSQL 에서만 잴 수 있는 유일성 제약. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcUserIdentityRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var users: JdbcUserRepository
    private lateinit var identities: JdbcUserIdentityRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("user_identity_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val jdbcClient = JdbcClient.create(dataSource())
        users = JdbcUserRepository(jdbcClient)
        identities = JdbcUserIdentityRepository(jdbcClient)
    }

    @Test
    @DisplayName("연결한 신원을 (provider, provider_user_id) 로 다시 찾는다")
    fun `연결하고 다시 찾는다`() {
        val user = users.createWithoutPassword(uniqueEmail())

        val linked = identities.link(user.id, SocialLoginProviderId.GOOGLE, "sub-1", user.email, true)

        val found = identities.findByProviderIdentity(SocialLoginProviderId.GOOGLE, "sub-1")
        assertThat(found).isEqualTo(linked)
        assertThat(found?.userId).isEqualTo(user.id)
    }

    @Test
    @DisplayName("없는 신원은 null 이다")
    fun `없는 신원은 null 이다`() {
        assertThat(identities.findByProviderIdentity(SocialLoginProviderId.GOOGLE, "never-linked")).isNull()
    }

    @Test
    @DisplayName("같은 (provider, provider_user_id) 를 두 사용자에 연결할 수 없다 — 유일성 제약")
    fun `신원 유일성을 지킨다`() {
        val first = users.createWithoutPassword(uniqueEmail())
        val second = users.createWithoutPassword(uniqueEmail())
        identities.link(first.id, SocialLoginProviderId.GOOGLE, "shared-sub", first.email, true)

        assertThatThrownBy {
            identities.link(second.id, SocialLoginProviderId.GOOGLE, "shared-sub", second.email, true)
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    @DisplayName("계정을 지우면 연결된 신원도 함께 사라진다 — ON DELETE CASCADE")
    fun `계정 삭제가 신원까지 지운다`() {
        val user = users.createWithoutPassword(uniqueEmail())
        identities.link(user.id, SocialLoginProviderId.GOOGLE, "cascade-sub", user.email, true)

        database.connect().use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM users WHERE id = '${user.id}'") }
        }

        assertThat(identities.findByProviderIdentity(SocialLoginProviderId.GOOGLE, "cascade-sub")).isNull()
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun uniqueEmail(): String = "identity${counter++}@example.test"

    private companion object {
        var counter = 0
    }
}

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
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** `announcements` — 실제 PostgreSQL 에서만 잴 수 있는 것들. 스키마는 `V17__admin.sql`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAnnouncementRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcAnnouncementRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("announcement_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(dataSource())
        repository = JdbcAnnouncementRepository(jdbc)
    }

    @Test
    @DisplayName("create는 항상 active=true로 만든다")
    fun `생성은 항상 활성이다`() {
        val admin = newAdmin()

        val created = repository.create(UUID.randomUUID(), "점검 안내", admin, Instant.now())

        assertThat(created.active).isTrue()
        assertThat(created.body).isEqualTo("점검 안내")
        assertThat(created.createdBy).isEqualTo(admin)
    }

    @Test
    @DisplayName("update는 준 필드만 바꾼다 — body만 주면 active는 그대로")
    fun `부분 갱신`() {
        val admin = newAdmin()
        val created = repository.create(UUID.randomUUID(), "원래 내용", admin, Instant.now())

        val updated = repository.update(created.id, body = "바뀐 내용", active = null, updatedAt = Instant.now())

        assertThat(updated).isNotNull()
        assertThat(updated!!.body).isEqualTo("바뀐 내용")
        assertThat(updated.active).isTrue()
    }

    @Test
    @DisplayName("update는 active만 줘도 body를 건드리지 않는다")
    fun `active만 갱신한다`() {
        val admin = newAdmin()
        val created = repository.create(UUID.randomUUID(), "그대로 유지", admin, Instant.now())

        val updated = repository.update(created.id, body = null, active = false, updatedAt = Instant.now())

        assertThat(updated!!.body).isEqualTo("그대로 유지")
        assertThat(updated.active).isFalse()
    }

    @Test
    @DisplayName("update는 없는 id면 null이다")
    fun `없는 공지 갱신은 null이다`() {
        assertThat(repository.update(UUID.randomUUID(), "x", true, Instant.now())).isNull()
    }

    @Test
    @DisplayName("listAll은 활성·비활성 전부를 최신순으로 낸다")
    fun `전체 목록은 최신순이다`() {
        val admin = newAdmin()
        val first = repository.create(UUID.randomUUID(), "첫 공지", admin, Instant.parse("2026-01-01T00:00:00Z"))
        val second = repository.create(UUID.randomUUID(), "둘째 공지", admin, Instant.parse("2026-02-01T00:00:00Z"))
        repository.update(first.id, body = null, active = false, updatedAt = Instant.now())

        val all = repository.listAll()

        assertThat(all.map { it.id }).contains(first.id, second.id)
        assertThat(all.indexOfFirst { it.id == second.id })
            .isLessThan(all.indexOfFirst { it.id == first.id })
    }

    @Test
    @DisplayName("listActive는 활성만, 최신순 최대 limit건을 낸다")
    fun `활성 목록만 낸다`() {
        val admin = newAdmin()
        val active = repository.create(UUID.randomUUID(), "활성 공지", admin, Instant.now())
        val inactiveCreated = repository.create(UUID.randomUUID(), "비활성 공지", admin, Instant.now())
        repository.update(inactiveCreated.id, body = null, active = false, updatedAt = Instant.now())

        val activeList = repository.listActive(limit = 5)

        assertThat(activeList.map { it.id }).contains(active.id)
        assertThat(activeList.map { it.id }).doesNotContain(inactiveCreated.id)
    }

    @Test
    @DisplayName("listActive는 limit을 넘지 않는다")
    fun `활성 목록은 limit 안이다`() {
        val admin = newAdmin()
        repeat(3) { repository.create(UUID.randomUUID(), "공지-$it-${UUID.randomUUID()}", admin, Instant.now()) }

        val activeList = repository.listActive(limit = 2)

        assertThat(activeList).hasSizeLessThanOrEqualTo(2)
    }

    private fun newAdmin(): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id, email, password_hash, is_admin) VALUES (:id, :email, :hash, true)")
            .param("id", id)
            .param("email", "announcement-admin-$id@example.test")
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
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

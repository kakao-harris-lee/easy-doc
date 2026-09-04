package kr.easydoc.infrastructure.document

import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
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

/** `notified_at`(migration V5) 을 통한 완료 알림 멱등 표시 — 실물 PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcConversionNotificationStoreTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var store: JdbcConversionNotificationStore
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("conversion_notification_store")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        conversions = JdbcConversionRepository(jdbc)
        store = JdbcConversionNotificationStore(jdbc)
    }

    @Test
    @DisplayName("문서 제목·소유자 이메일을 읽고, 아직 알림을 보내지 않았다고 본다")
    fun `대상을 읽는다`() {
        val ownerEmail = "u${UUID.randomUUID()}@example.com"
        val (conversionId, _) = seedConversion(ownerEmail, "복지 안내문")

        val target = checkNotNull(store.findTarget(conversionId))

        assertThat(target.documentTitle).isEqualTo("복지 안내문")
        assertThat(target.ownerEmail.value).isEqualTo(ownerEmail)
        assertThat(target.alreadyNotified).isFalse()
    }

    @Test
    @DisplayName("markNotified 는 처음 호출에만 true 를 돌려준다 — 재실행 멱등")
    fun `표시는 한 번만 갱신된다`() {
        val (conversionId, _) = seedConversion("u${UUID.randomUUID()}@example.com", "제목")

        val first = store.markNotified(conversionId)
        val second = store.markNotified(conversionId)

        assertThat(first).isTrue()
        assertThat(second).isFalse()
    }

    @Test
    @DisplayName("markNotified 뒤에는 findTarget 이 alreadyNotified=true 를 돌려준다")
    fun `표시 뒤에는 이미 보냈다고 본다`() {
        val (conversionId, _) = seedConversion("u${UUID.randomUUID()}@example.com", "제목")

        store.markNotified(conversionId)
        val target = checkNotNull(store.findTarget(conversionId))

        assertThat(target.alreadyNotified).isTrue()
    }

    @Test
    @DisplayName("변환이 없으면 null")
    fun `없는 변환은 null`() {
        assertThat(store.findTarget(UUID.randomUUID())).isNull()
    }

    private fun seedConversion(
        ownerEmail: String,
        title: String,
    ): Pair<UUID, UUID> {
        val owner = users.create(ownerEmail, PasswordHash(DUMMY_PHC)).id
        val workspace = workspaces.create(owner, "공간").id
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                       source_text_encrypted, char_count, encryption_scheme, key_version)
                VALUES (:id, :owner, :workspace, :title, :format, :bytes, 4, :scheme, 1)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", owner)
            .param("workspace", workspace)
            .param("title", title)
            .param("format", SourceFormat.TEXT.wireName)
            .param("bytes", byteArrayOf(1, 2, 3, 4))
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()
        conversions.insertPending(conversionId, documentId, EncryptionScheme.AES_256_GCM_V1, 1)
        return conversionId to documentId
    }

    private companion object {
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

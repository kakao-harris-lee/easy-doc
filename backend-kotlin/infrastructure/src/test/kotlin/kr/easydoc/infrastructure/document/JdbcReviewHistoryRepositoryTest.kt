package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ReviewHistoryCursor
import kr.easydoc.application.document.ReviewHistoryEventToStore
import kr.easydoc.application.document.ReviewHistoryEventType
import kr.easydoc.application.document.ReviewHistorySnapshotKind
import kr.easydoc.application.document.ReviewHistorySnapshotToStore
import kr.easydoc.application.document.StoredReviewHistoryEvent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** R5 persistence invariants: ownership/retention, bounded snapshots, cascade and key rotation. */
class JdbcReviewHistoryRepositoryTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcReviewHistoryRepository
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var fixture: Fixture
    private val cipher = cipher(writeVersion = 1)

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("review_history")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        repository = JdbcReviewHistoryRepository(jdbc)
        fixture = seed()
    }

    @Test
    fun `이벤트는 21개여도 snapshot은 최신 20개이고 오래된 event는 missing으로 남는다`() {
        repeat(21) { index ->
            append(index + 1, artifactRevision = index.toLong() + 1)
        }

        assertThat(count("review_events")).isEqualTo(21)
        assertThat(count("review_snapshots")).isEqualTo(20)
        val rows = repository.pageOwned(fixture.ownerId, fixture.conversionId, NOW.plusSeconds(100), null, 50)
        assertThat(rows).hasSize(21)
        assertThat(rows.count { it.snapshot == null }).isEqualTo(1)
    }

    @Test
    fun `created_at이 같은 페이지도 cursor의 event id로 이어서 읽는다`() {
        repeat(55) { index ->
            append(index + 1, artifactRevision = index.toLong() + 1, createdAt = NOW)
        }

        val first = repository.pageOwned(fixture.ownerId, fixture.conversionId, NOW, null, 50)
        val cursor =
            ReviewHistoryCursor(
                conversionId = fixture.conversionId,
                cutoff = NOW,
                createdAt = first.last().createdAt,
                eventId = first.last().eventId,
            )
        val second = repository.pageOwned(fixture.ownerId, fixture.conversionId, NOW, cursor, 50)

        assertThat(first).hasSize(50)
        assertThat(second).hasSize(5)
        assertThat((first + second).map(StoredReviewHistoryEvent::eventId)).doesNotHaveDuplicates()
    }

    @Test
    fun `소유권 보존기간 만료와 문서 삭제가 history를 노출하거나 남기지 않는다`() {
        append(1, artifactRevision = 1)
        assertThat(repository.pageOwned(UUID.randomUUID(), fixture.conversionId, NOW, null, 20)).isEmpty()

        jdbc
            .sql("UPDATE documents SET retention_expires_at=now()-interval '1 second' WHERE id=:id")
            .param("id", fixture.documentId)
            .update()
        assertThat(
            repository.pageOwned(fixture.ownerId, fixture.conversionId, NOW.plusSeconds(100), null, 20),
        ).isEmpty()

        jdbc.sql("DELETE FROM documents WHERE id=:id").param("id", fixture.documentId).update()
        assertThat(count("review_events")).isZero()
        assertThat(count("review_snapshots")).isZero()
    }

    @Test
    fun `키 회전은 R5 AAD로 다시 봉인하고 다른 snapshot id를 거절한다`() {
        append(1, artifactRevision = 1)
        val old = repository.pageOwned(fixture.ownerId, fixture.conversionId, NOW.plusSeconds(100), null, 20).single()
        val oldSnapshot = old.snapshot!!
        val rotatedCipher = cipher(writeVersion = 2, includeOld = true)
        val rotation =
            ReviewHistoryKeyRotation(
                jdbc,
                rotatedCipher,
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
                batchSize = 1,
            )

        assertThat(rotation.run()).isEqualTo(1)
        assertThat(rotation.run()).isZero()
        val rotated =
            repository
                .pageOwned(
                    fixture.ownerId,
                    fixture.conversionId,
                    NOW.plusSeconds(100),
                    null,
                    20,
                ).single()
                .snapshot!!
        assertThat(rotated.payload.keyVersion).isEqualTo(2)
        assertThat(
            rotatedCipher.decrypt(rotated.payload, rotated.snapshotId, EncryptedField.REVIEW_HISTORY_SNAPSHOT).value,
        ).contains("본문")
        assertThatThrownBy {
            rotatedCipher.decrypt(rotated.payload, UUID.randomUUID(), EncryptedField.REVIEW_HISTORY_SNAPSHOT)
        }.isInstanceOf(RuntimeException::class.java)
        assertThat(oldSnapshot.snapshotId).isEqualTo(rotated.snapshotId)
    }

    private fun append(
        contentRevision: Int,
        artifactRevision: Long,
        createdAt: Instant = NOW.plusSeconds(contentRevision.toLong()),
    ) {
        val eventId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val payload =
            cipher.encrypt(
                PlainBody("본문 $contentRevision"),
                snapshotId,
                EncryptedField.REVIEW_HISTORY_SNAPSHOT,
            )
        repository.append(
            fixture.ownerId,
            ReviewHistoryEventToStore(
                eventId,
                fixture.conversionId,
                ReviewHistoryEventType.ITEM_CONFIRMED,
                fixture.ownerId,
                createdAt,
                contentRevision.toLong(),
                artifactRevision,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                snapshotId,
            ),
            ReviewHistorySnapshotToStore(
                snapshotId,
                fixture.conversionId,
                contentRevision.toLong(),
                artifactRevision,
                ReviewHistorySnapshotKind.REVIEW_ASSESSMENT,
                payload,
            ),
        )
    }

    private fun seed(): Fixture {
        val value = Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,:password)")
            .param("id", value.ownerId)
            .param("email", "r5-${value.ownerId}@example.test")
            .param("password", DUMMY_PHC)
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'공간')")
            .param("id", value.workspaceId)
            .param("owner", value.ownerId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO documents
                    (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                     encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'제목','txt',:bytes,'aes256gcm-v1',1,10)
                """.trimIndent(),
            ).param("id", value.documentId)
            .param("owner", value.ownerId)
            .param("workspace", value.workspaceId)
            .param("bytes", byteArrayOf(1))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions
                    (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
                VALUES (:id,:document,'done',:bytes,'aes256gcm-v1',1,1)
                """.trimIndent(),
            ).param("id", value.conversionId)
            .param("document", value.documentId)
            .param("bytes", byteArrayOf(2))
            .update()
        return value
    }

    private fun cipher(
        writeVersion: Int,
        includeOld: Boolean = false,
    ): AesGcmContentCipher {
        val materials =
            mutableMapOf(
                writeVersion to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { writeVersion.toByte() })),
            )
        if (includeOld) materials[1] = Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
        return AesGcmContentCipher(materials, writeVersion)
    }

    private fun count(table: String): Int =
        jdbc.sql("SELECT count(*) FROM $table").query { rs, _ -> rs.getInt(1) }.single()

    private data class Fixture(
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-21T00:00:00Z")
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

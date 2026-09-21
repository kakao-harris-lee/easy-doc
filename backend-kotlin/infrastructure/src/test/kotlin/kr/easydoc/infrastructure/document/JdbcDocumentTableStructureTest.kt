package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.DocumentSourceService
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.TableCellStructure
import kr.easydoc.core.document.TableStructure
import kr.easydoc.core.document.TableStructurePayloadCodec
import kr.easydoc.core.document.TableSupportStatus
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.Base64
import java.util.UUID

/** R4 payload ownership, encryption, retention/cascade, and independent key rotation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDocumentTableStructureTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var documents: JdbcDocumentRepository
    private lateinit var tables: JdbcDocumentTableStructureRepository

    @BeforeAll
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("document_table_structure")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        documents = JdbcDocumentRepository(jdbc)
        tables = JdbcDocumentTableStructureRepository(jdbc)
    }

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM document_table_structures").update()
        jdbc.sql("DELETE FROM documents").update()
        jdbc.sql("DELETE FROM workspaces").update()
        jdbc.sql("DELETE FROM users").update()
    }

    @Test
    fun `payload는 암호화되어 저장되고 feature gate가 source와 함께 읽는다`() {
        val fixture = seedDocument()
        val cipher = cipher(1)
        val payload =
            cipher.encrypt(
                PlainBody(TableStructurePayloadCodec.encode(sampleTables())),
                fixture.documentId,
                EncryptedField.DOCUMENT_TABLE_STRUCTURE,
            )

        tables.insert(fixture.ownerId, fixture.documentId, payload)

        val stored = documents.findOwnedSource(fixture.ownerId, fixture.documentId)
        assertThat(stored?.tableStructures).isEqualTo(payload)
        assertThat(String(payload.bytes, Charsets.UTF_8)).doesNotContain("table-0")
        assertThat(
            DocumentSourceService(documents, cipher, tableRelationsEnabled = false)
                .read(fixture.ownerId, fixture.documentId)
                .tables,
        ).isNull()
        assertThat(
            DocumentSourceService(documents, cipher, tableRelationsEnabled = true)
                .read(fixture.ownerId, fixture.documentId)
                .tables,
        ).containsExactlyElementsOf(sampleTables())
    }

    @Test
    fun `old document without payload is nullable and ownership retention are enforced`() {
        val fixture = seedDocument()
        val service = DocumentSourceService(documents, cipher(1), tableRelationsEnabled = true)

        assertThat(service.read(fixture.ownerId, fixture.documentId).tables).isNull()
        assertThat(documents.findOwnedSource(UUID.randomUUID(), fixture.documentId)).isNull()

        jdbc
            .sql("UPDATE documents SET retention_expires_at=now()-interval '1 second' WHERE id=:id")
            .param("id", fixture.documentId)
            .update()
        assertThatThrownBy { service.read(fixture.ownerId, fixture.documentId) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `문서 삭제가 table payload를 cascade로 제거한다`() {
        val fixture = seedDocument()
        val cipher = cipher(1)
        val payload =
            cipher.encrypt(
                PlainBody(TableStructurePayloadCodec.encode(sampleTables())),
                fixture.documentId,
                EncryptedField.DOCUMENT_TABLE_STRUCTURE,
            )
        tables.insert(fixture.ownerId, fixture.documentId, payload)

        assertThat(documents.deleteOwned(fixture.ownerId, fixture.documentId)).isTrue()
        assertThat(countFor(fixture.documentId)).isZero()
        assertThat(documents.findOwnedSource(fixture.ownerId, fixture.documentId)).isNull()
    }

    @Test
    fun `table payload key rotation is AAD bound and idempotent`() {
        val fixture = seedDocument()
        val oldCipher = cipher(1)
        val rotatedCipher = cipher(2, includeOld = true)
        val payload =
            oldCipher.encrypt(
                PlainBody(TableStructurePayloadCodec.encode(sampleTables())),
                fixture.documentId,
                EncryptedField.DOCUMENT_TABLE_STRUCTURE,
            )
        tables.insert(fixture.ownerId, fixture.documentId, payload)
        val rotation =
            TableStructureKeyRotation(
                jdbc,
                rotatedCipher,
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
                batchSize = 1,
            )

        assertThat(rotation.run()).isEqualTo(1)
        assertThat(rotation.run()).isZero()
        val stored = requireNotNull(documents.findOwnedSource(fixture.ownerId, fixture.documentId)?.tableStructures)
        assertThat(stored.keyVersion).isEqualTo(2)
        assertThat(
            TableStructurePayloadCodec.decodeOrNull(
                rotatedCipher.decrypt(stored, fixture.documentId, EncryptedField.DOCUMENT_TABLE_STRUCTURE).value,
            ),
        ).containsExactlyElementsOf(sampleTables())
        assertThatThrownBy {
            rotatedCipher.decrypt(stored, UUID.randomUUID(), EncryptedField.DOCUMENT_TABLE_STRUCTURE)
        }.isInstanceOf(RuntimeException::class.java)
    }

    private fun seedDocument(): Fixture {
        val fixture = Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,:password)")
            .param("id", fixture.ownerId)
            .param("email", "r4-${fixture.ownerId}@example.test")
            .param("password", "unused")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'r4 공간')")
            .param("id", fixture.workspaceId)
            .param("owner", fixture.ownerId)
            .update()
        val source =
            cipher(1).encrypt(
                PlainBody("원문 첫 줄\n원문 둘째 줄"),
                fixture.documentId,
                EncryptedField.DOCUMENT_SOURCE_TEXT,
            )
        jdbc
            .sql(
                """
                INSERT INTO documents
                    (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                     encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'R4 표','txt',:bytes,:scheme,:keyVersion,12)
                """.trimIndent(),
            ).param("id", fixture.documentId)
            .param("owner", fixture.ownerId)
            .param("workspace", fixture.workspaceId)
            .param("bytes", source.bytes)
            .param("scheme", source.scheme)
            .param("keyVersion", source.keyVersion)
            .update()
        return fixture
    }

    private fun countFor(documentId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM document_table_structures WHERE document_id=:id")
            .param("id", documentId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun cipher(
        writeVersion: Int,
        includeOld: Boolean = false,
    ): AesGcmContentCipher {
        val materials =
            mutableMapOf(
                writeVersion to
                    Secret(
                        Base64.getEncoder().encodeToString(ByteArray(32) { writeVersion.toByte() }),
                    ),
            )
        if (includeOld) materials[1] = Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
        return AesGcmContentCipher(materials, writeVersion)
    }

    private fun sampleTables(): List<TableStructure> =
        listOf(
            TableStructure(
                tableId = "table-0",
                sourceUnitIndexes = listOf(0, 1),
                rowCount = 1,
                columnCount = 2,
                cells =
                    listOf(
                        TableCellStructure(0, 0, listOf(0), emptyList()),
                        TableCellStructure(0, 1, listOf(1), emptyList()),
                    ),
                supportStatus = TableSupportStatus.SUPPORTED,
            ),
        )

    private data class Fixture(
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
    )
}

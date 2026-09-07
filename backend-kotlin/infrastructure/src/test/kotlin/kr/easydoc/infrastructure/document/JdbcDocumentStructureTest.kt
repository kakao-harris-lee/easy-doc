package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.security.Secret
import kr.easydoc.core.segment.UnitKind
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * `documents.source_unit_kinds` 저장·조회 — S8-1 A7(계획 §2 표). [JdbcDocumentStoreTest] 와
 * 별도 파일인 것은 그 파일이 이미 `LargeClass` 문턱에 닿아 있어서다 — 셋업이 겹치지만
 * (`PostgresTestSupport`·Flyway) 파일 하나가 재는 관심사가 다르면 나누는 편이 낫다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDocumentStructureTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var documents: JdbcDocumentRepository
    private lateinit var service: DocumentService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("document_structure")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = dataSource()
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        documents = JdbcDocumentRepository(jdbc)
        val cipher = cipherWith(writeKeyVersion = 1)
        service =
            DocumentService(
                storage =
                    DocumentStorage(
                        documents = documents,
                        originals = JdbcDocumentOriginalRepository(jdbc),
                        conversions = JdbcConversionRepository(jdbc),
                        queue = JdbcConversionQueue(jdbc),
                    ),
                workspaces = JdbcWorkspaceLookup(jdbc),
                users = users,
                cipher = cipher,
                extractor = DocumentTextExtractor { _, _ -> error("이 배선은 파일 경로를 쓰지 않는다") },
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            )
    }

    @Test
    @DisplayName("원본 단위 종류가 저장되고 그대로 읽힌다 — 붙여넣기 목록 항목이 인코딩을 왕복한다")
    fun `원본 단위 종류가 왕복한다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "구조").id

        val accepted = service.createFromText(owner, "1. 첫째\n본문", null, workspace.toString())

        val stored = documents.findOwnedSource(owner, accepted.documentId)
        checkNotNull(stored)
        assertThat(stored.structureOrBody(2).kinds).containsExactly(UnitKind.LIST_ITEM, UnitKind.BODY)
    }

    @Test
    @DisplayName("A7 — source_unit_kinds 가 NULL 인 옛 문서도 조회가 깨지지 않고 전부 BODY 로 읽힌다")
    fun `NULL 구조 컬럼도 조회가 깨지지 않는다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "옛문서").id
        val documentId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                       source_text_encrypted, char_count, encryption_scheme, key_version)
                VALUES (:id, :owner, :workspace, '옛 문서', 'text', :bytes, 2, :scheme, 1)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", owner)
            .param("workspace", workspace)
            .param("bytes", byteArrayOf(0))
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()

        val stored = documents.findOwnedSource(owner, documentId)

        checkNotNull(stored)
        assertThat(stored.structure).isNull()
        assertThat(stored.structureOrBody(2).kinds).containsExactly(UnitKind.BODY, UnitKind.BODY)
    }

    private fun newUser(): UUID =
        users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id.also(users::markEmailVerified)

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun cipherWith(writeKeyVersion: Int): ContentCipher =
        AesGcmContentCipher(
            keyMaterial = mapOf(1 to randomKey()),
            writeKeyVersion = writeKeyVersion,
            random = SecureRandom(),
        )

    private companion object {
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
        const val KEY_BYTES = 32

        private val random = SecureRandom()

        private fun randomKey(): Secret {
            val material = ByteArray(KEY_BYTES)
            random.nextBytes(material)
            return Secret(Base64.getEncoder().encodeToString(material))
        }
    }
}

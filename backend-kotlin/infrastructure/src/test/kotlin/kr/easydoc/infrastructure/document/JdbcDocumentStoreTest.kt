package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionQueue
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.RotationOutcome
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.CountingDataSource
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * 문서·변환 저장 경로 — **실제 PostgreSQL 에서만 잴 수 있는 것들**.
 *
 * 유스케이스 대역 테스트(`DocumentServiceTest`)가 순서와 경계를 재는 동안 이 파일은 그
 * 아래를 잰다. 인메모리 대역으로는 다음을 잴 수 없고, 그것이 이 파일의 이유다.
 *
 * ⑴ **원자성** — 문서·변환·작업 세 행이 함께 있거나 함께 없다.
 * ⑵ **봉투 두 값이 실제 컬럼에 적힌다** — 그리고 빠뜨린 쓰기가 `V3` 의 설계대로 **즉시**
 *    실패한다(NOT NULL 위반). 「DEFAULT 가 조용히 채운다」가 되살아나면 여기서 빨개진다.
 * ⑶ **`V4` 의 CHECK 가 새 INSERT 경로에도 선다** — 도메인 타입이 먼저 막지만 마지막
 *    방어선은 DB 다.
 * ⑷ **FK CASCADE** — 문서를 지우면 변환과 작업이 함께 사라진다.
 * ⑸ **문장 수** — 소유권 은닉의 **구조 축**(시간 축으로는 못 잡는다는 실측이
 *    `CountingDataSource` KDoc 에 있다)과 재암호화의 「단일 UPDATE」.
 * ⑹ **낙관적 조건** — 두 프로세스가 같은 행을 회전할 때 뒤엣것이 0행을 갱신한다.
 *
 * ## 암호는 **실제 AES-GCM** 이다
 *
 * 대역이 아니라 `AesGcmContentCipher` 를 실행 시점 난수 키로 조립해 쓴다. 그래야
 * 「암호문이 실제로 평문과 다르다」와 「행을 다시 읽어 열 수 있다」를 함께 잴 수 있다.
 * 조립된 **Spring 빈**으로 같은 것을 재는 것은 `DocumentStorageContextTest` 다(X9/F-6).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDocumentStoreTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var documents: JdbcDocumentRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var cipher: ContentCipher
    private lateinit var service: DocumentService
    private lateinit var rotation: EnvelopeRotation

    private lateinit var counting: CountingDataSource
    private lateinit var countedService: DocumentService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("document_store")
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
        conversions = JdbcConversionRepository(jdbc)
        cipher = cipherWith(writeKeyVersion = 1)
        service = serviceOn(dataSource, cipher)
        rotation =
            EnvelopeRotation(
                documents = documents,
                conversions = conversions,
                cipher = cipherWith(writeKeyVersion = 2),
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            )

        // 구조 축 계측용 두 번째 배선. 트랜잭션 관리자도 **같은** DataSource 를 받아야 한다 —
        // 다른 것을 주면 유스케이스의 트랜잭션이 계측되지 않은 커넥션을 잡는다.
        counting = CountingDataSource(dataSource())
        countedService = serviceOn(counting, cipher)
    }

    // ================================================================ 원자성

    @Test
    @DisplayName("업로드가 문서·변환·작업 **세 행을 함께** 남긴다")
    fun `세 행이 함께 남는다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "가").id

        val accepted = service.createFromText(owner, "복지 급여 안내\n둘째 줄", null, workspace)

        assertThat(documentRow(accepted.documentId)).isNotNull()
        assertThat(conversionStatus(accepted.conversionId)).isEqualTo(ConversionStatus.PENDING.wireName)
        assertThat(jobState(accepted.conversionId)).isEqualTo(JdbcConversionQueue.READY_STATE)
    }

    @Test
    @DisplayName("큐 등록이 실패하면 문서·변환도 남지 않는다 — 「저장은 됐는데 등록은 실패」가 구조적으로 없다")
    fun `등록 실패는 저장을 되돌린다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "나").id
        val failing = serviceOn(dataSource(), cipher) { ConversionQueue { error("큐 등록 실패") } }
        val jobsBefore = jobCount()

        assertThatThrownBy { failing.createFromText(owner, "본문", null, workspace) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(documentCountIn(workspace)).isZero()
        assertThat(jobCount()).isEqualTo(jobsBefore)
    }

    @Test
    @DisplayName("작업 공간 확인이 저장보다 먼저다 — 거절당한 업로드가 기본 공간에 남지 않는다")
    fun `남의 작업 공간 업로드는 아무것도 남기지 않는다`() {
        val owner = newUser()
        val stranger = newUser()
        workspaces.create(owner, "다")
        val theirs = workspaces.create(stranger, "남의 것").id

        assertThatThrownBy { service.createFromText(owner, "본문", null, theirs) }
            .isInstanceOf(NotFoundException::class.java)

        assertThat(documentCountFor(owner)).isZero()
        assertThat(documentCountIn(theirs)).isZero()
    }

    // ================================================================ 봉투

    @Test
    @DisplayName("봉투 두 값이 두 테이블에 **명시적으로** 적힌다 — 대기 중 변환도 예외가 아니다")
    fun `봉투 두 값이 적힌다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "라").id

        val accepted = service.createFromText(owner, "본문", null, workspace)

        assertThat(envelopeOf("documents", accepted.documentId))
            .isEqualTo(EncryptionScheme.AES_256_GCM_V1 to 1)
        assertThat(envelopeOf("conversions", accepted.conversionId))
            .isEqualTo(EncryptionScheme.AES_256_GCM_V1 to 1)
        assertThat(conversionCiphertextsAllNull(accepted.conversionId))
            .describedAs("대기 중 변환의 암호문 세 열은 NULL 이어야 한다")
            .isTrue()
    }

    @Test
    @DisplayName("원문이 **평문으로 저장되지 않는다** — 컬럼을 직접 읽어도 본문이 보이지 않는다")
    fun `원문이 평문으로 저장되지 않는다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "마").id
        val body = "주민등록번호 안내문 본문"

        val accepted = service.createFromText(owner, body, null, workspace)

        val stored = documents.loadSourceText(accepted.documentId)
        checkNotNull(stored)
        assertThat(String(stored.bytes, Charsets.UTF_8)).doesNotContain(body)
        assertThat(cipher.decrypt(stored, accepted.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .isEqualTo(body)
    }

    @Test
    @DisplayName("`encryption_scheme` 을 빠뜨린 INSERT 는 **NOT NULL 위반으로 즉시** 실패한다 (V3 의 설계 의도)")
    fun `봉투를 빠뜨린 쓰기는 실패한다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "바").id

        assertThatThrownBy {
            jdbc
                .sql(
                    """
                    INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                           source_text_encrypted, char_count, key_version)
                    VALUES (:id, :owner, :workspace, 'fixture', 'text', :bytes, 1, 1)
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("owner", owner)
                .param("workspace", workspace)
                .param("bytes", byteArrayOf(0))
                .update()
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    @DisplayName("`V4` 의 CHECK 가 새 INSERT 경로에도 선다 — 도메인 밖 세대는 DB 가 거부한다")
    fun `도메인 밖 세대는 DB 가 거부한다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "사").id

        assertThatThrownBy {
            jdbc
                .sql(
                    """
                    INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                           source_text_encrypted, char_count, encryption_scheme, key_version)
                    VALUES (:id, :owner, :workspace, 'fixture', 'text', :bytes, 1, :scheme, 0)
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("owner", owner)
                .param("workspace", workspace)
                .param("bytes", byteArrayOf(0))
                .param("scheme", EncryptionScheme.AES_256_GCM_V1)
                .update()
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    @DisplayName("보존 만료가 **DB 시계** 기준 30일이다 (계약 x-input-limits.retention_days)")
    fun `보존 만료가 DB 시계 기준이다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "아").id

        val accepted = service.createFromText(owner, "본문", null, workspace)

        val days =
            jdbc
                .sql(
                    """
                    SELECT round(extract(epoch FROM retention_expires_at - created_at) / 86400)
                    FROM documents WHERE id = :id
                    """.trimIndent(),
                ).param("id", accepted.documentId)
                .query { rs, _ -> rs.getInt(1) }
                .single()
        assertThat(days).isEqualTo(RETENTION_DAYS)
    }

    // ================================================================ 삭제 연쇄

    @Test
    @DisplayName("문서를 지우면 변환과 **작업 행까지** 함께 사라진다 (FK CASCADE 연쇄)")
    fun `삭제가 작업 행까지 연쇄한다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "자").id
        val accepted = service.createFromText(owner, "본문", null, workspace)

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", accepted.documentId).update()

        assertThat(conversionStatus(accepted.conversionId)).isNull()
        assertThat(jobState(accepted.conversionId))
            .describedAs("작업 행이 남으면 워커가 매번 없는 변환을 읽으러 간다")
            .isNull()
    }

    // ================================================================ 목록

    @Test
    @DisplayName("목록이 소유자 범위이고 `created_at DESC, id DESC` 로 흔들리지 않는다")
    fun `목록이 소유자 범위이고 안정 정렬이다`() {
        val owner = newUser()
        val stranger = newUser()
        val workspace = workspaces.create(owner, "차").id
        workspaces.create(stranger, "남의 것")
        val first = service.createFromText(owner, "첫째", null, workspace).documentId
        val second = service.createFromText(owner, "둘째", null, workspace).documentId
        insertStrangerDocument(stranger)

        val listed = documents.listOwned(owner, null, limit = 10, offset = 0)

        assertThat(listed.map { it.document.id }).containsExactly(second, first)
        assertThat(listed.map { it.status }).containsOnly(ConversionStatus.PENDING)
        assertThat(listed.map { it.reviewedAt }).containsOnlyNulls()
    }

    @Test
    @DisplayName("작업 공간 필터가 걸려도 **소유자 조건이 남는다** — 필터를 빠뜨린 호출이 남의 문서를 내주지 않는다")
    fun `작업 공간 필터에도 소유자 조건이 남는다`() {
        val owner = newUser()
        val stranger = newUser()
        val mine = workspaces.create(owner, "카").id
        val theirs = workspaces.create(stranger, "남의 것 2").id
        service.createFromText(owner, "내 문서", null, mine)

        assertThat(documents.listOwned(owner, mine, 10, 0)).hasSize(1)
        assertThat(documents.listOwned(owner, theirs, 10, 0))
            .describedAs("남의 작업 공간을 지목해도 소유자 조건이 걸러야 한다")
            .isEmpty()
    }

    @Test
    @DisplayName("변환이 여럿이면 **최신 하나**만 목록에 실린다")
    fun `최신 변환 하나만 실린다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "타").id
        val accepted = service.createFromText(owner, "본문", null, workspace)
        val newer =
            conversions.insertPending(
                id = UUID.randomUUID(),
                documentId = accepted.documentId,
                scheme = cipher.writeScheme,
                keyVersion = cipher.writeKeyVersion,
            )
        // 같은 트랜잭션 밖이라도 `now()` 는 밀리초 단위로 같을 수 있다. 「최신」 판정을 재는
        // 케이스이므로 시각을 **결정적으로** 벌린다 — 그러지 않으면 동률 tie-break(id DESC)가
        // 무작위 UUID 에 좌우돼 이 단언이 실행마다 갈린다.
        jdbc
            .sql("UPDATE conversions SET created_at = created_at + interval '1 second' WHERE id = :id")
            .param("id", newer.id)
            .update()

        val listing = documents.listOwned(owner, workspace, 10, 0).single()

        assertThat(listing.conversionId).isEqualTo(newer.id)
    }

    // ================================================================ 구조 축

    @Test
    @DisplayName("업로드 한 번이 내는 SQL 문 수가 **고정**이다 — 조회가 하나 늘면 그 정수가 움직인다")
    fun `업로드의 문장 수가 고정이다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "파").id

        val statements = counting.countStatements { countedService.createFromText(owner, "본문", null, workspace) }

        assertThat(statements)
            .describedAs("작업 공간 소유 판정 1 + 문서 INSERT 1 + 변환 INSERT 1 + 작업 INSERT 1")
            .isEqualTo(UPLOAD_STATEMENTS)
    }

    @Test
    @DisplayName("남의 작업 공간을 지목한 업로드는 **한 문장**에서 끝난다 — 존재 여부가 일한 양으로 새지 않는다")
    fun `거절 경로의 문장 수`() {
        val owner = newUser()
        val stranger = newUser()
        workspaces.create(owner, "하")
        val theirs = workspaces.create(stranger, "남의 것 3").id

        val missing =
            counting.countStatements {
                runCatching { countedService.createFromText(owner, "본문", null, UUID.randomUUID()) }
            }
        val notMine =
            counting.countStatements {
                runCatching { countedService.createFromText(owner, "본문", null, theirs) }
            }

        assertThat(missing).isEqualTo(1)
        assertThat(notMine).describedAs("없는 것과 남의 것이 다른 만큼 일하면 그 차이가 시간에 남는다").isEqualTo(missing)
    }

    // ================================================================ 재암호화

    @Test
    @DisplayName("재암호화가 **한 UPDATE** 다 — 열별 갱신으로 나뉘면 이 정수가 움직인다")
    fun `재암호화가 한 문장이다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "회전1").id
        val accepted = service.createFromText(owner, "회전 대상 본문", null, workspace)
        fillConversionResult(accepted.conversionId)

        val countedRotation =
            EnvelopeRotation(
                documents = JdbcDocumentRepository(JdbcClient.create(counting)),
                conversions = JdbcConversionRepository(JdbcClient.create(counting)),
                cipher = cipherWith(writeKeyVersion = 2),
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(counting))),
            )

        val statements = counting.countStatements { countedRotation.rotateConversion(accepted.conversionId) }

        assertThat(statements).describedAs("SELECT 1 + UPDATE 1").isEqualTo(2)
    }

    @Test
    @DisplayName("회전한 행이 새 세대로 열린다 — 세 열과 봉투가 함께 옮겨진다")
    fun `회전한 행이 새 세대로 열린다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "회전2").id
        val accepted = service.createFromText(owner, "본문", null, workspace)
        fillConversionResult(accepted.conversionId)

        assertThat(rotation.rotateConversion(accepted.conversionId)).isEqualTo(RotationOutcome.ROTATED)

        val envelope = conversions.loadEnvelope(accepted.conversionId)
        checkNotNull(envelope)
        assertThat(envelope.keyVersion).isEqualTo(2)
        val rotatedCipher = cipherWith(writeKeyVersion = 2)
        val easy = envelope.ciphertexts.easyText
        checkNotNull(easy)
        assertThat(rotatedCipher.decrypt(easy, accepted.conversionId, EncryptedField.CONVERSION_EASY_TEXT).value)
            .isEqualTo(DRAFT_BODY)
    }

    @Test
    @DisplayName("대기 중 변환을 회전해도 세 열이 **NULL 로 남는다** — 빈 문자열을 암호화하지 않는다")
    fun `대기 중 변환의 NULL 이 보존된다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "회전3").id
        val accepted = service.createFromText(owner, "본문", null, workspace)

        assertThat(rotation.rotateConversion(accepted.conversionId)).isEqualTo(RotationOutcome.ROTATED)

        assertThat(conversionCiphertextsAllNull(accepted.conversionId)).isTrue()
        assertThat(envelopeOf("conversions", accepted.conversionId)).isEqualTo(EncryptionScheme.AES_256_GCM_V1 to 2)
    }

    @Test
    @DisplayName("**낙관적 조건** — 이미 회전된 행을 옛 세대로 다시 회전하면 0행이 갱신된다")
    fun `낙관적 조건이 경합을 가른다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "회전4").id
        val accepted = service.createFromText(owner, "본문", null, workspace)
        rotation.rotateConversion(accepted.conversionId)

        // 다른 프로세스가 먼저 회전한 상태를 그대로 재현한다 — 세대 1 을 기대하고 UPDATE 한다.
        val updated =
            conversions.rewriteEnvelope(
                conversionId = accepted.conversionId,
                expectedKeyVersion = 1,
                scheme = EncryptionScheme.AES_256_GCM_V1,
                keyVersion = 2,
                ciphertexts =
                    ConversionCiphertexts(null, null, null),
            )

        assertThat(updated).isFalse()
    }

    @Test
    @DisplayName("문서 원문도 회전한다 — 회전 뒤 새 세대로 열린다")
    fun `문서 원문을 회전한다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "회전5").id
        val body = "회전할 원문"
        val accepted = service.createFromText(owner, body, null, workspace)

        assertThat(rotation.rotateDocument(accepted.documentId)).isEqualTo(RotationOutcome.ROTATED)

        val stored = documents.loadSourceText(accepted.documentId)
        checkNotNull(stored)
        assertThat(stored.keyVersion).isEqualTo(2)
        val reopened =
            cipherWith(writeKeyVersion = 2)
                .decrypt(stored, accepted.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(reopened.value).isEqualTo(body)
    }

    @Test
    @DisplayName("쓰기 키가 없으면 업로드가 **설정 오류**로 끊기고 아무 행도 남지 않는다")
    fun `쓰기 키가 없으면 아무것도 남지 않는다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "무키").id
        val keyless =
            serviceOn(
                dataSource(),
                AesGcmContentCipher(keyMaterial = emptyMap(), writeKeyVersion = 1, random = SecureRandom()),
            )

        assertThatThrownBy { keyless.createFromText(owner, "본문", null, workspace) }
            .isInstanceOf(ConfigurationException::class.java)

        assertThat(documentCountIn(workspace)).isZero()
    }

    // ================================================================ 도구

    /**
     * 유스케이스 한 벌을 **하나의 [DataSource] 위에** 조립한다.
     *
     * 저장소와 트랜잭션 관리자가 **같은 DataSource 인스턴스**를 봐야 한다. Spring 은 트랜잭션
     * 커넥션을 그 인스턴스를 키로 스레드에 묶으므로, 둘이 갈리면 저장소가 autocommit 커넥션을
     * 잡고 **롤백이 아무것도 되돌리지 않는다.** 실측으로 밟은 자리라 인자를 하나로 좁혔다 —
     * 클라이언트를 밖에서 받으면 그 갈림을 호출부마다 다시 만들 수 있다.
     */
    private fun serviceOn(
        dataSource: DataSource,
        contentCipher: ContentCipher,
        conversionQueue: (JdbcClient) -> ConversionQueue = { JdbcConversionQueue(it) },
    ): DocumentService {
        val client = JdbcClient.create(dataSource)
        return DocumentService(
            storage =
                DocumentStorage(
                    documents = JdbcDocumentRepository(client),
                    conversions = JdbcConversionRepository(client),
                    queue = conversionQueue(client),
                ),
            workspaces = JdbcWorkspaceLookup(client),
            cipher = contentCipher,
            extractor = { _, _ -> error("이 테스트는 파일 경로를 쓰지 않는다") },
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
        )
    }

    /**
     * 실행 시점 난수 키로 조립한 실제 AES-GCM 암호기.
     *
     * 두 세대를 모두 싣는다 — 회전 왕복을 재려면 옛 세대로 읽고 새 세대로 써야 한다.
     * 키를 상수로 적지 않는다(스캐너 `SECRET-LITERAL`, 프로젝트 `CLAUDE.md`).
     */
    private fun cipherWith(writeKeyVersion: Int): ContentCipher =
        AesGcmContentCipher(
            keyMaterial = mapOf(1 to KEY_GEN_1, 2 to KEY_GEN_2),
            writeKeyVersion = writeKeyVersion,
            random = SecureRandom(),
        )

    private fun newUser(): UUID = users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun documentRow(id: UUID): UUID? =
        jdbc
            .sql("SELECT id FROM documents WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .optional()
            .orElse(null)

    private fun conversionStatus(id: UUID): String? =
        jdbc
            .sql("SELECT status FROM conversions WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getString("status") }
            .optional()
            .orElse(null)

    private fun jobState(conversionId: UUID): String? =
        jdbc
            .sql("SELECT state FROM conversion_jobs WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getString("state") }
            .optional()
            .orElse(null)

    private fun jobCount(): Int =
        jdbc.sql("SELECT count(*) FROM conversion_jobs").query { rs, _ -> rs.getInt(1) }.single()

    private fun documentCountIn(workspaceId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM documents WHERE workspace_id = :id")
            .param("id", workspaceId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun documentCountFor(ownerId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM documents WHERE user_id = :id")
            .param("id", ownerId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun envelopeOf(
        table: String,
        id: UUID,
    ): Pair<String, Int> =
        jdbc
            .sql("SELECT encryption_scheme, key_version FROM $table WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.getString("encryption_scheme") to rs.getInt("key_version") }
            .single()

    private fun conversionCiphertextsAllNull(id: UUID): Boolean =
        jdbc
            .sql(
                """
                SELECT easy_text_encrypted IS NULL AND masked_items_encrypted IS NULL
                       AND edited_text_encrypted IS NULL
                FROM conversions WHERE id = :id
                """.trimIndent(),
            ).param("id", id)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()

    /** 완료된 변환처럼 세 열을 채운다. 워커(Phase 5)가 할 일을 여기서는 손으로 만든다. */
    private fun fillConversionResult(conversionId: UUID) {
        val writer = cipherWith(writeKeyVersion = 1)
        val codec = MaskedItemCodec()
        conversions.rewriteEnvelope(
            conversionId = conversionId,
            expectedKeyVersion = 1,
            scheme = EncryptionScheme.AES_256_GCM_V1,
            keyVersion = 1,
            ciphertexts =
                ConversionCiphertexts(
                    easyText =
                        writer.encrypt(
                            PlainBody(DRAFT_BODY),
                            conversionId,
                            EncryptedField.CONVERSION_EASY_TEXT,
                        ),
                    maskedItems =
                        writer.encrypt(
                            codec.encode(emptyList()),
                            conversionId,
                            EncryptedField.CONVERSION_MASKED_ITEMS,
                        ),
                    editedText = null,
                ),
        )
    }

    private fun insertStrangerDocument(strangerId: UUID) {
        val owned = workspaces.listOwned(strangerId)
        val workspace = owned.first().workspace.id
        jdbc
            .sql(
                """
                INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                       source_text_encrypted, char_count, encryption_scheme, key_version)
                VALUES (:id, :owner, :workspace, 'fixture', :format, :bytes, 1, :scheme, 1)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("owner", strangerId)
            .param("workspace", workspace)
            .param("format", SourceFormat.TEXT.wireName)
            .param("bytes", byteArrayOf(0))
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()
    }

    private companion object {
        /** 계약 `x-input-limits.retention_days`. */
        const val RETENTION_DAYS = 30

        /** 작업 공간 소유 판정 1 + 문서 INSERT 1 + 변환 INSERT 1 + 작업 INSERT 1. */
        const val UPLOAD_STATEMENTS = 4

        const val DRAFT_BODY = "쉬운 글 초안"

        /**
         * 사용자 행을 만들기 위한 자리 채움 PHC. 검증에 쓰이지 않으므로 실제 해시가 아니어도
         * 되지만, **형식은 갖춘다** — `PasswordHash` 가 도메인 타입이다.
         */
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** AES-256. */
        const val KEY_BYTES = 32

        private val random = SecureRandom()

        private fun randomKey(): Secret {
            val material = ByteArray(KEY_BYTES)
            random.nextBytes(material)
            return Secret(Base64.getEncoder().encodeToString(material))
        }

        val KEY_GEN_1: Secret = randomKey()
        val KEY_GEN_2: Secret = randomKey()
    }
}

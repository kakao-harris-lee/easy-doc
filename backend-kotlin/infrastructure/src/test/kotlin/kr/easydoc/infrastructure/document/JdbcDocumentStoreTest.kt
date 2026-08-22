package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionQueue
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.ExtractedDocument
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

/** P2 목록 팔의 쪽 크기. 거절 경로는 여기까지 오지 않으므로 값 자체는 성질과 무관하다. */
private const val LIST_LIMIT = 10

/** P2 목록 팔이 남의 작업 공간에 심는 문서 수. 결정적 축이라 「0이 아니다」면 된다. */
private const val FOREIGN_DOCUMENTS = 3

/** 문서·변환 저장 경로 — 실제 PostgreSQL 에서만 잴 수 있는 것들. */

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

        counting = CountingDataSource(dataSource())
        countedService = serviceOn(counting, cipher)
    }

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

        val failing =
            serviceOn(dataSource(), cipher, conversionQueue = { ConversionQueue { error("큐 등록 실패") } })
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

        val stored = documents.lockSourceText(accepted.documentId)
        checkNotNull(stored)
        assertThat(String(stored.bytes, Charsets.UTF_8)).doesNotContain(body)
        assertThat(cipher.decrypt(stored, accepted.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .isEqualTo(body)
    }

    @Test
    @DisplayName("본문 표식이 문서 행의 **어느 열에도** 남지 않는다 — 열 목록을 카탈로그에서 파생한다")
    fun `본문 표식이 평문 열에 남지 않는다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "표식").id
        val marker = newMarker()
        val body = "$marker 로 시작하는 안내문\n둘째 줄에도 $marker 가 있다"

        val accepted = service.createFromText(owner, body, null, workspace)

        assertNoMarkerInDocumentRow(
            marker = marker,
            documentId = accepted.documentId,
            what = "본문",
            why =
                "`documents` 의 모든 열은 사용자 본문을 담지 않는다 — 본문은 " +
                    "`source_text_encrypted` 에 AEAD 로만 들어간다.\n" +
                    "  업로드 시점에는 마스킹이 돌지 않으므로(마스킹은 워커의 일이다) " +
                    "본문에서 유도한 값은 아무 방어도 받지 않는다.",
        )
    }

    @Test
    @DisplayName("**파일 이름** 표식이 문서 행의 어느 열에도 남지 않는다 — I-4 를 문장이 아니라 실행으로 세운다")
    fun `파일 이름 표식이 평문 열에 남지 않는다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "이름 표식").id
        val marker = newMarker()
        val fileService = serviceOn(dataSource(), cipher, extractor = fixedExtractor(FILE_BODY))

        val accepted =
            fileService.createFromFile(owner, "$marker-주민등록등본.docx", ByteArray(1), null, workspace.toString())

        assertNoMarkerInDocumentRow(
            marker = marker,
            documentId = accepted.documentId,
            what = "파일 이름",
            why =
                "파일 이름은 그 자체가 개인정보일 수 있다(`홍길동_주민등록등본.docx`).\n" +
                    "  계약 `DocumentTextRequest.title` 과 `migration-safety-gate` I-4 가 " +
                    "저장을 금지한다 — 이름은 형식 판별에만 쓰이고 버려져야 한다.",
        )
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

    /** 제품 포트로 지운다 + 그것이 한 문장이다. */
    @Test
    @DisplayName("포트 경유 삭제가 **한 문장**으로 변환·작업까지 연쇄한다")
    fun `포트 경유 삭제가 한 문장으로 연쇄한다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "카").id
        val accepted = countedService.createFromText(owner, "본문", null, workspace)

        val statements = counting.countStatements { countedService.delete(owner, accepted.documentId) }

        assertThat(statements).describedAs("DELETE 1").isEqualTo(1)
        assertThat(documentRow(accepted.documentId)).isNull()
        assertThat(conversionStatus(accepted.conversionId)).isNull()
        assertThat(jobState(accepted.conversionId)).isNull()
    }

    /** 소유 술어가 실제로 좁힌다 + 문장 수가 소유 결과와 무관하다. */
    @Test
    @DisplayName("남의 문서·없는 문서 삭제가 0행이고, 두 거절의 문장 수가 같다")
    fun `삭제가 소유 술어로 좁혀지고 거절 비용이 같다`() {
        val owner = newUser()
        val stranger = newUser()
        val workspace = workspaces.create(owner, "타").id
        workspaces.create(stranger, "남의 것 4")
        val accepted = countedService.createFromText(owner, "본문", null, workspace)

        assertThat(documents.deleteOwned(stranger, accepted.documentId))
            .describedAs("0행이 아니라 성공이면 남의 문서를 지운 것이다 — 복구 수단이 없다")
            .isFalse()
        assertThat(documents.deleteOwned(owner, UUID.randomUUID())).isFalse()

        val missing = counting.countStatements { runCatching { countedService.delete(owner, UUID.randomUUID()) } }
        val notMine = counting.countStatements { runCatching { countedService.delete(stranger, accepted.documentId) } }

        assertThat(missing).isEqualTo(1)
        assertThat(notMine)
            .describedAs("없는 것과 남의 것이 다른 만큼 일하면 그 차이가 시간에 남는다")
            .isEqualTo(missing)

        assertThat(documentRow(accepted.documentId)).isNotNull()
        assertThat(conversionStatus(accepted.conversionId)).isEqualTo(ConversionStatus.PENDING.wireName)
    }

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

        jdbc
            .sql("UPDATE conversions SET created_at = created_at + interval '1 second' WHERE id = :id")
            .param("id", newer.id)
            .update()

        val listing = documents.listOwned(owner, workspace, 10, 0).single()

        assertThat(listing.conversionId).isEqualTo(newer.id)
    }

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

    /** 성질 P2 — 거절 비용의 무상관 (`privacy-gate` 회차 2, 처방 X1-2). */
    @Test
    @DisplayName("거절 경로가 **일한 양으로 존재를 흘리지 않는다** — 업로드는 한 문장, 목록은 남의 공간 크기와 무관")
    fun `거절 경로의 문장 수`() {
        val owner = newUser()
        val stranger = newUser()
        workspaces.create(owner, "하")
        val theirs = workspaces.create(stranger, "남의 것 3").id

        fun upload(target: UUID) =
            counting.countStatements { runCatching { countedService.createFromText(owner, "본문", null, target) } }

        fun list(target: UUID) =
            counting.countStatements { runCatching { countedService.list(owner, target, LIST_LIMIT, 0) } }

        val missing = upload(UUID.randomUUID())
        val notMine = upload(theirs)
        val listMissing = list(UUID.randomUUID())
        val listEmpty = list(theirs)
        repeat(FOREIGN_DOCUMENTS) { service.createFromText(stranger, "남의 안내문 $it", null, theirs) }
        val listFilled = list(theirs)

        assertThat(missing).isEqualTo(1)
        assertThat(notMine).describedAs("없는 것과 남의 것이 다른 만큼 일하면 그 차이가 시간에 남는다").isEqualTo(missing)
        assertThat(listMissing).describedAs("목록 거절이 소유 판정 한 문장에서 끝나지 않았다").isEqualTo(1)
        assertThat(listEmpty).describedAs("없는 공간과 남의 공간이 다른 만큼 일한다").isEqualTo(listMissing)
        assertThat(listFilled).describedAs("남의 공간에 행이 있을 때 일이 는다 — 새는 것은 그 크기다").isEqualTo(listEmpty)
    }

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

        val envelope = conversions.lockEnvelope(accepted.conversionId)
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
    @DisplayName("**낙관적 조건** — 낡은 기대로 쓰면 0행이 갱신된다 (SQL 축)")
    fun `낙관적 조건이 낡은 기대를 거른다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "회전4").id
        val accepted = service.createFromText(owner, "본문", null, workspace)
        rotation.rotateConversion(accepted.conversionId)

        val stale =
            ConversionEnvelope(
                conversionId = accepted.conversionId,
                scheme = EncryptionScheme.AES_256_GCM_V1,
                keyVersion = 1,
                ciphertexts = ConversionCiphertexts(null, null, null),
            )
        val updated =
            conversions.rewriteEnvelope(
                expected = stale,
                scheme = EncryptionScheme.AES_256_GCM_V1,
                keyVersion = 2,
                ciphertexts = ConversionCiphertexts(null, null, null),
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

        val stored = documents.lockSourceText(accepted.documentId)
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

    /** 문서 행의 어느 평문 열에도 [marker] 가 없음을 확인한다. 표식 검사 둘이 공유한다. */
    private fun assertNoMarkerInDocumentRow(
        marker: String,
        documentId: UUID,
        what: String,
        why: String,
    ) {
        val columns = columnsOf("documents")
        assertThat(columns)
            .withFailMessage("`documents` 의 열을 하나도 읽지 못했다 — 검사 대상 0건은 통과가 아니라 미검사다.")
            .isNotEmpty()
        assertThat(columns)
            .describedAs("바닥 목록 — 이 열들이 분모에서 빠지면 이 검사는 껍데기가 된다")
            .containsAll(DOCUMENT_COLUMN_FLOOR)
        assertThat(columns)
            .describedAs("열 수 하한 — 카탈로그 조회가 조용히 좁아지는 것을 막는다")
            .hasSizeGreaterThanOrEqualTo(MIN_DOCUMENT_COLUMNS)

        val offenders = columns.filter { marker in columnTextOf("documents", it, documentId) }

        assertThat(offenders)
            .withFailMessage { "$what 표식이 문서 행의 평문 열에 남았다: $offenders\n  $why" }
            .isEmpty()
    }

    /** 실행마다 다른 표식. 앞자리가 고정이라 실패 메시지에서 눈에 띈다. */
    private fun newMarker(): String = "EDPROBE" + UUID.randomUUID().toString().take(MARKER_HEX_CHARS)

    /** 파일 경로를 쓰는 케이스용 추출기 대역. 이름을 보지 않고 정해진 본문을 돌려준다. */
    private fun fixedExtractor(text: String): DocumentTextExtractor =
        DocumentTextExtractor { _, _ -> ExtractedDocument(SourceFormat.DOCX, text) }

    /** 유스케이스 한 벌을 하나의 [DataSource] 위에 조립한다. */
    private fun serviceOn(
        dataSource: DataSource,
        contentCipher: ContentCipher,
        conversionQueue: (JdbcClient) -> ConversionQueue = { JdbcConversionQueue(it) },
        extractor: DocumentTextExtractor = DocumentTextExtractor { _, _ -> error("이 배선은 파일 경로를 쓰지 않는다") },
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
            extractor = extractor,
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
        )
    }

    /** 실행 시점 난수 키로 조립한 실제 AES-GCM 암호기. */
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

    /** 그 테이블의 열 이름 전부를 DB 카탈로그에서 받는다. */
    private fun columnsOf(table: String): List<String> =
        jdbc
            .sql(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = :table
                ORDER BY ordinal_position
                """.trimIndent(),
            ).param("table", table)
            .query { rs, _ -> rs.getString(1) }
            .list()

    /** 열 하나를 텍스트로 읽는다. `NULL` 은 빈 문자열이다 — 표식 검사에서 둘은 같다. */
    private fun columnTextOf(
        table: String,
        column: String,
        id: UUID,
    ): String =
        jdbc
            .sql("""SELECT coalesce("$column"::text, '') FROM $table WHERE id = :id""")
            .param("id", id)
            .query { rs, _ -> rs.getString(1) }
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

        val current = checkNotNull(conversions.lockEnvelope(conversionId)) { "변환 행이 없다" }
        conversions.rewriteEnvelope(
            expected = current,
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

        /**
         * 표식 검사가 반드시 덮어야 하는 열. 면제 목록의 반대다 — 여기 있는 열이
         * 분모에서 빠지면 검사가 껍데기이므로 빨개진다. 새 열을 여기 적을 필요는 없다
         * (분모는 카탈로그가 정한다).
         */
        val DOCUMENT_COLUMN_FLOOR =
            listOf("title", "source_format", "char_count", "source_text_encrypted")

        /** `V1`~`V5` 이후 `documents` 의 열 수. 카탈로그 조회가 조용히 좁아지는 것을 막는 하한이다. */
        const val MIN_DOCUMENT_COLUMNS = 11

        /**
         * 표식에 붙이는 난수 자릿수. 표식 전체 길이가 잘라 옮기는 갈래의 창(옛 판 30자)보다
         * 짧아야 음성 대조가 성립한다 — 길면 잘려서 검사가 통과한다.
         */
        const val MARKER_HEX_CHARS = 8

        /** 작업 공간 소유 판정 1 + 문서 INSERT 1 + 변환 INSERT 1 + 작업 INSERT 1. */
        const val UPLOAD_STATEMENTS = 4

        const val DRAFT_BODY = "쉬운 글 초안"

        /** 파일 경로 케이스가 쓰는 본문. 표식은 파일 이름에 있고 본문에는 없다. */
        const val FILE_BODY = "복지 급여 안내\n둘째 줄"

        /**
         * 사용자 행을 만들기 위한 자리 채움 PHC. 검증에 쓰이지 않으므로 실제 해시가 아니어도
         * 되지만, 형식은 갖춘다 — `PasswordHash` 가 도메인 타입이다.
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

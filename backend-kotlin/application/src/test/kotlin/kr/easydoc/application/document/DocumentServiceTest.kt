package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.FALLBACK_TITLE
import kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS
import kr.easydoc.core.document.MAX_UPLOAD_BYTES
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.exceptions.UploadTooLargeException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 문서 등록 유스케이스 — **Spring 도 DB 도 없이** 대역으로 돈다.
 *
 * ## 여기서 재는 것과 재지 않는 것
 *
 * 재는 것은 **순서와 경계**다 — 계약이 못박은 검사 순서(파일 크기 → 추출 → 본문 길이 →
 * 작업 공간 소유권 → 저장), 그리고 저장·큐 등록이 **한 트랜잭션 안**이라는 사실.
 *
 * 재지 않는 것은 SQL 이 실제로 무엇을 하는가다 — 원자성·제약·CASCADE 는 실제 PostgreSQL 이
 * 있어야 잴 수 있고 `JdbcDocumentStoreTest` 가 맡는다. 두 파일이 같은 것을 두 번 재면
 * 어느 쪽이 무엇을 지키는지가 흐려진다.
 *
 * ## 트랜잭션 경계를 **깊이로** 잰다
 *
 * 「같은 트랜잭션인가」를 대역 호출 순서로 재면 순서만 맞고 경계는 밖일 수 있다. 그래서
 * [RecordingTransactionRunner] 가 진행 중 깊이를 세고, 저장소·큐 대역이 **불린 시점의
 * 깊이**를 기록한다. 등록이 커밋 밖으로 나가면 그 값이 0 이 되어 빨개진다.
 */
class DocumentServiceTest {
    // ============================================================ 검사 순서

    @Test
    @DisplayName("파일 크기 판정이 추출보다 먼저다 — 상한을 넘는 바이트를 파서에 넘기지 않는다")
    fun `크기 판정이 추출보다 먼저다`() {
        val world = World()
        val oversized = ByteArray((MAX_UPLOAD_BYTES + 1).toInt())

        assertThatThrownBy { world.service.createFromFile(OWNER, "a.docx", oversized, null, null) }
            .isInstanceOf(UploadTooLargeException::class.java)

        assertThat(world.extractorCalls).describedAs("추출기가 불렸다 — 순서가 뒤집혔다").isZero()
        assertThat(world.documents.inserted).isEmpty()
    }

    @Test
    @DisplayName("정확히 상한인 파일은 통과한다 — 경계는 초과에서만 걸린다")
    fun `정확히 상한은 통과한다`() {
        val world = World()

        world.service.createFromFile(OWNER, "a.docx", ByteArray(MAX_UPLOAD_BYTES.toInt()), null, null)

        assertThat(world.documents.inserted).hasSize(1)
    }

    @Test
    @DisplayName("추출 결과가 공백뿐이면 422 — 빈 docx·hwpx 는 예외 없이 빈 문자열을 돌려준다")
    fun `빈 추출 결과는 거절한다`() {
        val world = World(extracted = "   \n\t ")

        assertThatThrownBy { world.service.createFromFile(OWNER, "a.docx", ByteArray(1), null, null) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(NO_TEXT_IN_DOCUMENT_MESSAGE)

        assertThat(world.documents.inserted).isEmpty()
    }

    @Test
    @DisplayName("붙여넣기 본문이 공백뿐이면 422")
    fun `빈 본문은 거절한다`() {
        val world = World()

        assertThatThrownBy { world.service.createFromText(OWNER, " \n ", null, null) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(EMPTY_BODY_MESSAGE)
    }

    @Test
    @DisplayName("본문 길이 판정이 작업 공간 조회보다 먼저다 — 두 입력이 만나는 자리에서 한 번 잰다")
    fun `길이 판정이 작업 공간 조회보다 먼저다`() {
        val world = World()

        assertThatThrownBy {
            world.service.createFromText(OWNER, "가".repeat(MAX_CONVERTIBLE_CHARS + 1), null, null)
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(BODY_TOO_LONG_MESSAGE)

        assertThat(world.workspaces.lookups).describedAs("작업 공간을 먼저 조회했다").isZero()
    }

    @Test
    @DisplayName("길이는 **코드 포인트**로 잰다 — 상한 정확히면 통과한다")
    fun `길이는 코드 포인트로 잰다`() {
        val world = World()
        // BMP 밖 문자 4,000개 = 코드 단위 8,000개. 코드 단위로 재는 구현이면 여기서 거절된다.
        val body = "𝓐".repeat(MAX_CONVERTIBLE_CHARS)

        val accepted = world.service.createFromText(OWNER, body, null, null)

        assertThat(accepted.charCount).isEqualTo(MAX_CONVERTIBLE_CHARS)
    }

    @Test
    @DisplayName("작업 공간이 남의 것이면 404이고 문서가 남지 않는다 — 거절당한 업로드가 기본 공간에 남으면 안 된다")
    fun `남의 작업 공간은 404 다`() {
        val world = World()

        assertThatThrownBy { world.service.createFromText(OWNER, "본문", null, STRANGER_WORKSPACE) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(WORKSPACE_NOT_FOUND_FOR_DOCUMENT_MESSAGE)

        assertThat(world.documents.inserted).isEmpty()
        assertThat(world.conversions.inserted).isEmpty()
        assertThat(world.queue.enqueued).isEmpty()
    }

    @Test
    @DisplayName("작업 공간이 하나도 없으면 5xx 다 — 사용자 입력 문제가 아니라 우리 불변식이 깨진 것이다")
    fun `작업 공간이 없으면 5xx 다`() {
        val world = World(defaultWorkspace = null)

        assertThatThrownBy { world.service.createFromText(OWNER, "본문", null, null) }
            .isInstanceOf(StorageException::class.java)
            .hasMessage(NO_WORKSPACE_MESSAGE)
    }

    // ============================================================ 저장과 등록

    @Test
    @DisplayName("문서·변환·작업 등록이 **같은 트랜잭션 안**이다 (계획 §4.4)")
    fun `저장과 등록이 한 트랜잭션이다`() {
        val world = World()

        val accepted = world.service.createFromText(OWNER, "복지 급여 안내\n둘째 줄", null, null)

        assertThat(world.transaction.started).isEqualTo(1)
        assertThat(world.transaction.committed).isEqualTo(1)
        assertThat(world.documents.depthWhenInserted).containsExactly(1)
        assertThat(world.conversions.depthWhenInserted).containsExactly(1)
        assertThat(world.queue.depthWhenEnqueued)
            .describedAs("큐 등록이 트랜잭션 밖이다 — 「저장은 됐는데 등록은 실패」 간극이 되살아난다")
            .containsExactly(1)
        assertThat(world.queue.enqueued).containsExactly(accepted.conversionId)
    }

    @Test
    @DisplayName("큐 등록이 실패하면 트랜잭션이 실패로 끝난다 — 문서만 남는 상태가 생기지 않는다")
    fun `등록 실패는 트랜잭션을 되돌린다`() {
        val world = World(queueFailure = IllegalStateException("큐 없음"))

        assertThatThrownBy { world.service.createFromText(OWNER, "본문", null, null) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(world.transaction.committed).isZero()
        assertThat(world.transaction.failed).isEqualTo(1)
    }

    @Test
    @DisplayName("봉투 두 값은 **쓰기 설정**에서 온다 — 행과 암호문의 세대가 갈리지 않는다")
    fun `봉투 두 값이 쓰기 설정에서 온다`() {
        val world = World(writeKeyVersion = 2)

        world.service.createFromText(OWNER, "본문", null, null)

        val storedDocument = world.documents.inserted.single()
        assertThat(storedDocument.second.scheme).isEqualTo(EncryptionScheme.AES_256_GCM_V1)
        assertThat(storedDocument.second.keyVersion).isEqualTo(2)
        assertThat(
            world.conversions.inserted
                .single()
                .second,
        ).isEqualTo(EncryptionScheme.AES_256_GCM_V1 to 2)
    }

    @Test
    @DisplayName("본문은 **행 식별자와 컬럼에 결속되어** 암호화된다 — 결속 인자는 암호문의 일부다")
    fun `본문이 행과 컬럼에 결속된다`() {
        val world = World()

        val accepted = world.service.createFromText(OWNER, "본문", null, null)

        val (plain, record, field) = world.cipher.sealed.single()
        assertThat(plain).isEqualTo("본문")
        assertThat(record).isEqualTo(accepted.documentId)
        assertThat(field).isEqualTo(EncryptedField.DOCUMENT_SOURCE_TEXT)
    }

    @Test
    @DisplayName("파일 모드에서 제목을 생략하면 **대체 제목**이다 — 본문도 파일 이름도 쓰지 않는다")
    fun `파일 모드는 제목을 생략하면 대체 제목이다`() {
        // 두 갈래를 한 케이스가 함께 잰다. 본문 유도(게이트 27 Critical ①)와 파일 이름
        // 유도(2026-08-20 재판정) 중 어느 쪽이 되살아나도 여기서 빨개진다 — 되살아난 값이
        // 무엇이든 `FALLBACK_TITLE` 이 아니기 때문이다.
        val world = World(extracted = "복지 급여 안내\n둘째 줄")

        world.service.createFromFile(OWNER, "홍길동_주민등록등본.docx", ByteArray(1), null, null)

        val title =
            world.documents.inserted
                .single()
                .first.title
        assertThat(title).isEqualTo(FALLBACK_TITLE)
        assertThat(title)
            .describedAs("본문 조각이 평문 title 로 새면 암호화·마스킹 두 방어를 동시에 우회한다")
            .doesNotContain("복지 급여 안내")
        assertThat(title)
            .describedAs("파일 이름은 그 자체가 개인정보일 수 있다 — 계약과 게이트 I-4 가 저장을 금지한다")
            .doesNotContain("홍길동")
    }

    @Test
    @DisplayName("붙여넣기에서 제목을 생략하면 **대체 제목**이다 — 첫 줄을 옮겨 적지 않는다")
    fun `붙여넣기는 제목을 생략하면 대체 제목이다`() {
        val world = World()

        world.service.createFromText(OWNER, "주민등록번호 안내 첫 줄\n둘째 줄", null, null)

        assertThat(
            world.documents.inserted
                .single()
                .first.title,
        ).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("파일 이름이 아예 없어도 같은 결과다 — 이름 유무가 제목을 바꾸지 않는다")
    fun `파일 이름이 없어도 대체 제목이다`() {
        val world = World(extracted = "복지 급여 안내\n둘째 줄")

        world.service.createFromFile(OWNER, null, ByteArray(1), null, null)

        assertThat(
            world.documents.inserted
                .single()
                .first.title,
        ).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("접수 응답은 대기 상태와 문자 수를 싣는다 (계약 DocumentCreatedResponse)")
    fun `접수 응답 모양`() {
        val world = World()

        val accepted = world.service.createFromText(OWNER, "가나다", "제목", null)

        assertThat(accepted.status).isEqualTo(ConversionStatus.PENDING)
        assertThat(accepted.charCount).isEqualTo(3)
        assertThat(accepted.documentId).isEqualTo(
            world.documents.inserted
                .single()
                .first.id,
        )
        assertThat(accepted.conversionId).isEqualTo(
            world.conversions.inserted
                .single()
                .first,
        )
    }

    @Test
    @DisplayName("추출한 형식이 그대로 저장된다 — 붙여넣기는 text 다")
    fun `형식이 그대로 저장된다`() {
        val text = World()
        text.service.createFromText(OWNER, "본문", null, null)
        assertThat(
            text.documents.inserted
                .single()
                .first.sourceFormat,
        ).isEqualTo(SourceFormat.TEXT)

        val file = World(extractedFormat = SourceFormat.HWPX)
        file.service.createFromFile(OWNER, "a.hwpx", ByteArray(1), null, null)
        assertThat(
            file.documents.inserted
                .single()
                .first.sourceFormat,
        ).isEqualTo(SourceFormat.HWPX)
    }

    @Test
    @DisplayName("사용자가 준 제목은 그대로 저장된다 — 앞뒤 공백만 턴다")
    fun `사용자 제목이 그대로 저장된다`() {
        val world = World()

        world.service.createFromText(OWNER, "본문 첫 줄\n둘째 줄", "  복지 급여 안내  ", null)

        assertThat(
            world.documents.inserted
                .single()
                .first.title,
        ).isEqualTo("복지 급여 안내")
    }

    // ============================================================ 목록

    @Test
    @DisplayName("목록은 **한 건 더** 읽는다 — 다음 쪽 유무를 전수 count 없이 판정하기 위해서다")
    fun `목록은 한 건 더 읽는다`() {
        val world = World()

        world.service.list(OWNER, null, limit = 20, offset = 40)

        assertThat(world.documents.listQueries.single()).isEqualTo(ListQuery(OWNER, null, 21, 40))
    }

    @Test
    @DisplayName("남의 작업 공간을 지목한 목록 조회는 **빈 목록이 아니라 404** 다")
    fun `남의 작업 공간 목록은 404 다`() {
        val world = World()

        assertThatThrownBy { world.service.list(OWNER, STRANGER_WORKSPACE, 20, 0) }
            .isInstanceOf(NotFoundException::class.java)

        assertThat(world.documents.listQueries).isEmpty()
    }

    // ============================================================ 대역

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val OWNED_WORKSPACE: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
        val STRANGER_WORKSPACE: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
    }

    /** 한 케이스가 쓰는 대역 묶음. 케이스마다 새로 만든다 — 대역이 상태를 들고 있다. */
    private class World(
        extracted: String = "추출한 본문",
        extractedFormat: SourceFormat = SourceFormat.DOCX,
        defaultWorkspace: UUID? = OWNED_WORKSPACE,
        writeKeyVersion: Int = 1,
        queueFailure: RuntimeException? = null,
    ) {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion)
        val documents = FakeDocumentRepository(transaction)
        val conversions = FakeConversionRepository(transaction)
        val workspaces = FakeWorkspaceLookup(defaultWorkspace)
        val queue = FakeConversionQueue(transaction, queueFailure)
        var extractorCalls: Int = 0
            private set

        val service =
            DocumentService(
                storage = DocumentStorage(documents = documents, conversions = conversions, queue = queue),
                workspaces = workspaces,
                cipher = cipher,
                extractor = { _, _ ->
                    extractorCalls++
                    ExtractedDocument(extractedFormat, extracted)
                },
                transaction = transaction,
            )
    }

    /**
     * 트랜잭션 경계를 **깊이로** 드러내는 대역.
     *
     * `catch` 절을 쓰지 않고 [runCatching] 으로 받는 이유: 이 자리에서 잡아야 하는 것은
     * 「블록이 실패했다」 하나인데 그 타입은 대역마다 다르다. 넓은 타입을 `catch` 로 잡으면
     * detekt `TooGenericExceptionCaught` 가 옳게 지적한다.
     */
    private class RecordingTransactionRunner : TransactionRunner {
        var depth: Int = 0
            private set
        var started: Int = 0
            private set
        var committed: Int = 0
            private set
        var failed: Int = 0
            private set

        override fun <T> inTransaction(block: () -> T): T {
            started++
            depth++
            val result = runCatching(block)
            depth--
            result.onSuccess { committed++ }.onFailure { failed++ }
            return result.getOrThrow()
        }
    }

    /** 결속 인자를 그대로 기록하는 암호 대역. 실제 암호는 여기서 재지 않는다. */
    private class FakeContentCipher(override val writeKeyVersion: Int) : ContentCipher {
        override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1

        val sealed = mutableListOf<Triple<String, UUID, EncryptedField>>()

        override fun encrypt(
            plain: PlainBody,
            record: UUID,
            field: EncryptedField,
        ): EncryptedContent {
            sealed += Triple(plain.value, record, field)
            return EncryptedContent(plain.value.toByteArray(Charsets.UTF_8), writeScheme, writeKeyVersion)
        }

        override fun decrypt(
            content: EncryptedContent,
            record: UUID,
            field: EncryptedField,
        ): PlainBody = PlainBody(String(content.bytes, Charsets.UTF_8))
    }

    private data class ListQuery(
        val ownerId: UUID,
        val workspaceId: UUID?,
        val limit: Int,
        val offset: Int,
    )

    private class FakeDocumentRepository(private val transaction: RecordingTransactionRunner) : DocumentRepository {
        val inserted = mutableListOf<Pair<DocumentDraft, EncryptedContent>>()
        val depthWhenInserted = mutableListOf<Int>()
        val listQueries = mutableListOf<ListQuery>()

        override fun insert(
            ownerId: UUID,
            draft: DocumentDraft,
            sourceText: EncryptedContent,
        ): Document {
            inserted += draft to sourceText
            depthWhenInserted += transaction.depth
            return Document(
                id = draft.id,
                title = draft.title,
                sourceFormat = draft.sourceFormat,
                charCount = draft.charCount,
                createdAt = Instant.EPOCH,
                retentionExpiresAt = Instant.EPOCH,
            )
        }

        override fun listOwned(
            ownerId: UUID,
            workspaceId: UUID?,
            limit: Int,
            offset: Int,
        ): List<DocumentListing> {
            listQueries += ListQuery(ownerId, workspaceId, limit, offset)
            return emptyList()
        }

        override fun lockSourceText(documentId: UUID): EncryptedContent? = null

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            sourceText: EncryptedContent,
        ): Boolean = false
    }

    private class FakeConversionRepository(private val transaction: RecordingTransactionRunner) : ConversionRepository {
        val inserted = mutableListOf<Pair<UUID, Pair<String, Int>>>()
        val depthWhenInserted = mutableListOf<Int>()

        override fun insertPending(
            id: UUID,
            documentId: UUID,
            scheme: String,
            keyVersion: Int,
        ): Conversion {
            inserted += id to (scheme to keyVersion)
            depthWhenInserted += transaction.depth
            return Conversion(
                id = id,
                documentId = documentId,
                status = ConversionStatus.PENDING,
                failureCode = null,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        }

        override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? = null

        override fun rewriteEnvelope(
            expected: ConversionEnvelope,
            scheme: String,
            keyVersion: Int,
            ciphertexts: ConversionCiphertexts,
        ): Boolean = false
    }

    private class FakeConversionQueue(
        private val transaction: RecordingTransactionRunner,
        private val failure: RuntimeException?,
    ) : ConversionQueue {
        val enqueued = mutableListOf<UUID>()
        val depthWhenEnqueued = mutableListOf<Int>()

        override fun enqueue(conversionId: UUID) {
            failure?.let { throw it }
            enqueued += conversionId
            depthWhenEnqueued += transaction.depth
        }
    }

    private class FakeWorkspaceLookup(private val defaultWorkspace: UUID?) : WorkspaceLookup {
        var lookups: Int = 0
            private set

        override fun findOwnedId(
            ownerId: UUID,
            workspaceId: UUID,
        ): UUID? {
            lookups++
            return workspaceId.takeIf { it == OWNED_WORKSPACE }
        }

        override fun findDefaultId(ownerId: UUID): UUID? {
            lookups++
            return defaultWorkspace
        }
    }
}

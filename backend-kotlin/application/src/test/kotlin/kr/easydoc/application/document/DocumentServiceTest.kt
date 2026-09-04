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
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.exceptions.UploadTooLargeException
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** 문서 등록 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다. */
class DocumentServiceTest {
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

        val body = "𝓐".repeat(MAX_CONVERTIBLE_CHARS)

        val accepted = world.service.createFromText(OWNER, body, null, null)

        assertThat(accepted.charCount).isEqualTo(MAX_CONVERTIBLE_CHARS)
    }

    @Test
    @DisplayName("작업 공간이 남의 것이면 404이고 문서가 남지 않는다 — 거절당한 업로드가 기본 공간에 남으면 안 된다")
    fun `남의 작업 공간은 404 다`() {
        val world = World()

        assertThatThrownBy { world.service.createFromText(OWNER, "본문", null, STRANGER_WORKSPACE.toString()) }
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
    @DisplayName("파일 업로드가 **원본 바이트를 함께 남긴다** — 추출 텍스트는 그대로 남는다")
    fun `파일 업로드가 원본을 남긴다`() {
        val world = World()

        val accepted = world.service.createFromFile(OWNER, "a.docx", ORIGINAL_FILE, null, null)

        val original = world.originals.rows.getValue(accepted.documentId)
        assertThat(original.byteSize)
            .describedAs("봉하기 전 바이트 수다 — nonce·태그가 붙은 암호문 길이가 아니다")
            .isEqualTo(ORIGINAL_FILE.size)
        assertThat(original.bytes.bytes)
            .describedAs("원본이 UTF-8 왕복으로 눌리면 파일이 조용히 망가진다")
            .isEqualTo(ORIGINAL_FILE)
        assertThat(world.documents.inserted)
            .describedAs("원본을 남긴다고 추출 텍스트를 없애지 않는다 — 변환·마스킹은 텍스트로 돈다")
            .hasSize(1)
        assertThat(world.cipher.sealed.map { it.third })
            .describedAs("원본은 원문과 **다른 열**로 결속된다 — 결속이 같으면 서로 바꿔치기가 된다")
            .containsExactly(EncryptedField.DOCUMENT_SOURCE_TEXT, EncryptedField.DOCUMENT_ORIGINAL_BYTES)
        assertThat(
            world.cipher.sealed
                .map { it.second }
                .distinct(),
        ).describedAs("두 암호문 모두 그 문서 행에 결속된다")
            .containsExactly(accepted.documentId)
    }

    @Test
    @DisplayName("**붙여넣기에는 원본 행이 없다** — 없는 파일을 빈 바이트로 지어내지 않는다")
    fun `붙여넣기는 원본을 남기지 않는다`() {
        val world = World()

        world.service.createFromText(OWNER, "복지 급여 안내", null, null)

        assertThat(world.originals.rows)
            .describedAs("빈 원본 행이 생기면 「원본이 있다」와 「없다」를 스키마가 구분하지 못한다")
            .isEmpty()
        assertThat(world.cipher.sealed.map { it.third }).containsExactly(EncryptedField.DOCUMENT_SOURCE_TEXT)
    }

    @Test
    @DisplayName("**txt 업로드도 원본 행이 없다** — 평문에는 반영할 서식이 없어 저장해도 쓰이지 않는다")
    fun `txt 업로드는 원본을 남기지 않는다`() {
        val world = World(extractedFormat = SourceFormat.TXT)

        val accepted = world.service.createFromFile(OWNER, "a.txt", ORIGINAL_FILE, null, null)

        assertThat(world.originals.rows)
            .describedAs("원본을 남기면 `hasStoredOriginal` 이 참이 되어 내보내기가 500 으로 떨어진다")
            .isEmpty()
        assertThat(world.cipher.sealed.map { it.third }).containsExactly(EncryptedField.DOCUMENT_SOURCE_TEXT)
        assertThat(
            world.documents.inserted
                .single()
                .first.sourceFormat,
        ).isEqualTo(SourceFormat.TXT)
        assertThat(accepted.documentId).isNotNull()
    }

    @Test
    @DisplayName("원본 저장이 **문서 등록과 같은 트랜잭션**이다")
    fun `원본 저장이 같은 트랜잭션이다`() {
        val world = World()

        world.service.createFromFile(OWNER, "a.docx", ORIGINAL_FILE, null, null)

        assertThat(world.transaction.started).isEqualTo(1)
        assertThat(world.originals.depthWhenInserted)
            .describedAs("원본 저장이 트랜잭션 밖이면 「문서는 남았는데 원본은 없다」가 생긴다")
            .containsExactly(1)
    }

    @Test
    @DisplayName("**원본 저장이 실패하면 업로드가 실패한다** — 조용히 성공하지 않는다")
    fun `원본 저장 실패는 업로드를 되돌린다`() {
        val world = World(originalFailure = IllegalStateException("원본 저장 실패"))

        assertThatThrownBy { world.service.createFromFile(OWNER, "a.docx", ORIGINAL_FILE, null, null) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(world.transaction.committed)
            .describedAs("원본을 잃은 채 문서만 남으면 §6.5 의 원본 내보내기가 그 문서에서 영영 불가능하다")
            .isZero()
        assertThat(world.transaction.failed).isEqualTo(1)
        assertThat(world.queue.enqueued)
            .describedAs("작업까지 갔다면 원본 없는 문서가 변환을 돈다")
            .isEmpty()
    }

    @Test
    @DisplayName("**봉인이 트랜잭션 밖에서 끝난다** — 10MB AEAD 가 열린 트랜잭션을 붙잡지 않는다")
    fun `봉인이 트랜잭션 밖이다`() {
        val world = World()

        world.service.createFromFile(OWNER, "a.docx", ORIGINAL_FILE, null, null)

        assertThat(world.cipher.depthWhenSealed)
            .describedAs("봉인이 트랜잭션 안에서 돌면 스냅샷과 연결을 AEAD 시간만큼 더 붙잡는다")
            .containsOnly(0)
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

    @Test
    @DisplayName("삭제가 **소유자를 저장소까지** 넘기고 트랜잭션 안에서 돈다")
    fun `삭제가 소유자와 트랜잭션 경계를 지킨다`() {
        val world = World()
        val documentId = UUID.randomUUID()
        world.documents.deletable += documentId

        world.service.delete(OWNER, documentId)

        assertThat(world.documents.deleteQueries.single()).isEqualTo(DeleteQuery(OWNER, documentId))

        assertThat(world.documents.depthWhenDeleted).containsExactly(1)
        assertThat(world.transaction.committed).isEqualTo(1)
    }

    @Test
    @DisplayName("지울 것이 없으면 **404** 다 — 조용한 성공(멱등 204)이 아니다")
    fun `지울 것이 없으면 404 다`() {
        val world = World()

        assertThatThrownBy { world.service.delete(OWNER, UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(DOCUMENT_NOT_FOUND_MESSAGE)

        assertThat(world.documents.deleteQueries).hasSize(1)
        assertThat(world.transaction.failed).isEqualTo(1)
    }

    @Test
    @DisplayName("삭제 경로가 **변환 저장소·큐를 건드리지 않는다** — 연쇄는 FK 의 일이다")
    fun `삭제가 변환을 따로 지우지 않는다`() {
        val world = World()
        val documentId = UUID.randomUUID()
        world.documents.deletable += documentId

        world.service.delete(OWNER, documentId)

        assertThat(world.conversions.inserted).isEmpty()
        assertThat(world.queue.enqueued).isEmpty()
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val OWNED_WORKSPACE: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
        val STRANGER_WORKSPACE: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b2")

        /**
         * 업로드 원본을 흉내 내는 바이트. **UTF-8 로 해석되지 않는 값을 섞는다** — 문자열 짝을
         * 타는 경로가 생기면 이 바이트가 U+FFFD 로 눌려 단언이 빨개진다(zip 머리 뒤 단독 0x80·0xFF).
         */
        val ORIGINAL_FILE: ByteArray =
            byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x80.toByte(), 0xFF.toByte(), 0x00, 0xC0.toByte())
    }

    /** 한 케이스가 쓰는 대역 묶음. 케이스마다 새로 만든다 — 대역이 상태를 들고 있다. */
    private class World(
        extracted: String = "추출한 본문",
        extractedFormat: SourceFormat = SourceFormat.DOCX,
        defaultWorkspace: UUID? = OWNED_WORKSPACE,
        writeKeyVersion: Int = 1,
        queueFailure: RuntimeException? = null,
        originalFailure: RuntimeException? = null,
    ) {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion, transaction)
        val documents = FakeDocumentRepository(transaction)
        val originals = FakeDocumentOriginalRepository(transaction, originalFailure)
        val conversions = FakeConversionRepository(transaction, originals)
        val workspaces = FakeWorkspaceLookup(defaultWorkspace)
        val queue = FakeConversionQueue(transaction, queueFailure)
        var extractorCalls: Int = 0
            private set

        val service =
            DocumentService(
                storage =
                    DocumentStorage(
                        documents = documents,
                        originals = originals,
                        conversions = conversions,
                        queue = queue,
                    ),
                workspaces = workspaces,
                cipher = cipher,
                extractor = { _, _ ->
                    extractorCalls++
                    ExtractedDocument(extractedFormat, extracted)
                },
                transaction = transaction,
            )
    }

    private data class ListQuery(
        val ownerId: UUID,
        val workspaceId: UUID?,
        val limit: Int,
        val offset: Int,
    )

    /** 삭제 요청 한 건. 소유자가 실제로 저장소까지 갔는가를 재는 재료다. */
    private data class DeleteQuery(
        val ownerId: UUID,
        val documentId: UUID,
    )

    private class FakeDocumentRepository(private val transaction: RecordingTransactionRunner) : DocumentRepository {
        val inserted = mutableListOf<Pair<DocumentDraft, EncryptedContent>>()
        val depthWhenInserted = mutableListOf<Int>()
        val listQueries = mutableListOf<ListQuery>()
        val deleteQueries = mutableListOf<DeleteQuery>()
        val depthWhenDeleted = mutableListOf<Int>()

        /** 여기 있는 식별자만 지워진다. 「없는 문서」 갈래를 만드는 스위치다. */
        val deletable = mutableSetOf<UUID>()

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

        override fun findOwnedSource(
            ownerId: UUID,
            documentId: UUID,
        ): StoredSourceText? = error("업로드 경로가 원문 조회 포트를 부르면 안 된다")

        override fun lockSourceText(documentId: UUID): EncryptedContent? = null

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            sourceText: EncryptedContent,
        ): Boolean = false

        /** 회전 배치 후보 포트 — 업로드 경로가 부를 일이 없다. */
        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = emptyList()

        /** 삭제 요청을 기록한다. 소유자 인자가 실제로 전달되는지를 잴 재료다. */
        override fun deleteOwned(
            ownerId: UUID,
            documentId: UUID,
        ): Boolean {
            deleteQueries += DeleteQuery(ownerId, documentId)
            depthWhenDeleted += transaction.depth
            return deletable.remove(documentId)
        }
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

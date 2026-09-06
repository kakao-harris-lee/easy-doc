package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** 내보내기 유스케이스 — HTTP·zip 없이 판정만 잰다. */
class ConversionExportServiceTest {
    @Test
    @DisplayName("남의 변환과 없는 변환이 **같은 404·같은 문구**다")
    fun `남의 변환은 404 다`() {
        val world = World()
        val theirs = UUID.randomUUID()
        world.seedDone(theirs, Seed(easyText = "초안"), owner = STRANGER)

        assertThatThrownBy { world.export(theirs) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)
        assertThatThrownBy { world.export(UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)
        assertThat(world.exporter.calls).isEmpty()
    }

    @Test
    @DisplayName("결과를 내보내지 않는 상태 **전부**가 409 다 — 분모를 enum 전체로 잡는다")
    fun `완료 전이면 409 다`() {
        ConversionStatus.entries.filterNot { it.exposesResult }.forEach { status ->
            val world = World()
            val conversionId = UUID.randomUUID()
            world.seedDone(conversionId, Seed(easyText = "초안", status = status))

            assertThatThrownBy { world.export(conversionId) }
                .isInstanceOf(ConflictException::class.java)
                .hasMessage(EXPORT_NOT_DONE_MESSAGE)
        }
    }

    @Test
    @DisplayName("검수본이 없으면 초안을 그대로 내보낸다")
    fun `검수본이 없으면 초안 그대로다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, Seed(easyText = "쉬운 글 초안"))

        val file = world.export(conversionId)

        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("쉬운 글 초안")
    }

    @Test
    @DisplayName("검수본이 있으면 검수본을 내보낸다 — 초안은 버려진다")
    fun `검수본이 있으면 검수본을 내보낸다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(
            conversionId,
            Seed(easyText = "버려질 초안", editedText = "검수본입니다."),
        )

        val file = world.export(conversionId)

        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("검수본입니다.")
    }

    @Test
    @DisplayName("파일명에 문서 제목을 쓴다")
    fun `제목이 파일명이다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.documentTitle = "기초연금 신청 안내"
        world.seedDone(conversionId, Seed(easyText = "본문"))

        val file = world.export(conversionId, ExportFormat.TXT)

        assertThat(file.filename).isEqualTo("기초연금 신청 안내-쉬운글.txt")
        assertThat(
            world.exporter.calls
                .single()
                .title,
        ).isEqualTo("기초연금 신청 안내")
    }

    @Test
    @DisplayName("복호화는 트랜잭션 안, 파일 조립은 밖에서 한다")
    fun `패키지 조립이 트랜잭션 밖이다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, Seed(easyText = "본문"))

        world.export(conversionId)

        assertThat(world.conversions.depthWhenRead).containsExactly(1)
        assertThat(world.cipher.depthWhenDecrypted).containsOnly(1)
        assertThat(world.exporter.depthWhenCalled).containsExactly(0)
    }

    @Test
    @DisplayName("형식을 **요청이 아니라 원본**이 정한다 — `format` 을 생략해도 같은 파일이 나간다")
    fun `형식을 원본이 정한다`() {
        SourceFormat.entries
            .mapNotNull { source -> ExportFormat.ofSource(source)?.let { source to it } }
            .forEach { (source, derived) ->
                listOf(null, derived).forEach { requested ->
                    val world = World()
                    val conversionId = UUID.randomUUID()
                    world.seedDone(conversionId, Seed(easyText = "본문", sourceFormat = source))

                    world.export(conversionId, requested)

                    assertThat(
                        world.exporter.calls
                            .single()
                            .format,
                    ).withFailMessage(
                        "원본 %s · 요청 %s 에서 조립기가 받은 형식이 유도값과 다르다: %s",
                        source.wireName,
                        requested,
                        world.exporter.calls
                            .single()
                            .format,
                    ).isEqualTo(derived)
                }
            }
    }

    @Test
    @DisplayName("원본이 정한 형식과 **다른** 값은 409 다 — 값 집합 안이어도 거절한다")
    fun `원본과 다른 형식은 409 다`() {
        SourceFormat.entries
            .mapNotNull { source -> ExportFormat.ofSource(source)?.let { source to it } }
            .forEach { (source, derived) ->
                ExportFormat.entries.filterNot { it == derived }.forEach { outsider ->
                    val world = World()
                    val conversionId = UUID.randomUUID()
                    world.seedDone(conversionId, Seed(easyText = "본문", sourceFormat = source))

                    assertThatThrownBy { world.export(conversionId, outsider) }
                        .describedAs("원본 ${source.wireName} 에 ${outsider.extension} 를 요청했는데 통과했다")
                        .isInstanceOf(ConflictException::class.java)
                        .hasMessage(EXPORT_FORMAT_MISMATCH_MESSAGE)
                    assertThat(world.exporter.calls).isEmpty()
                }
            }
    }

    @Test
    @DisplayName(
        "유도값도 선택지도 없는 원본은 **어떤 값도·생략도** 409 다 — 오늘은 실재하는 " +
            "`SourceFormat` 이 없어 이 대조는 값 집합이 넓어질 때를 대비한 안전망이다",
    )
    fun `유도값도 선택지도 없으면 409 다`() {
        val trulyUnexportable =
            SourceFormat.entries.filter { ExportFormat.ofSource(it) == null && ExportFormat.choicesFor(it).isEmpty() }
        assertThat(trulyUnexportable)
            .describedAs("오늘은 이 갈래에 드는 원본이 없다 — PDF 는 `choicesFor` 가 채운다")
            .isEmpty()
    }

    @Test
    @DisplayName("PDF 원본에서 `format` 을 생략하면 409 다 — 서버가 대신 고르지 않는다")
    fun `PDF 는 형식 생략이 409 다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, Seed(easyText = "본문", sourceFormat = SourceFormat.PDF))

        assertThatThrownBy { world.export(conversionId, null) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(EXPORT_FORMAT_CHOICE_REQUIRED_MESSAGE)
        assertThat(world.exporter.calls).isEmpty()
    }

    @Test
    @DisplayName("PDF 원본에서 선택지 밖의 값(값 집합 안이어도)은 409 다")
    fun `PDF 는 선택지 밖 요청이 409 다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, Seed(easyText = "본문", sourceFormat = SourceFormat.PDF))

        val outsiders = ExportFormat.entries.filterNot { it in ExportFormat.choicesFor(SourceFormat.PDF) }
        assertThat(outsiders).describedAs("PDF 의 선택지 밖 값이 하나도 없다 — 이 대조가 공허해진다").isNotEmpty()

        outsiders.forEach { outsider ->
            assertThatThrownBy { world.export(conversionId, outsider) }
                .describedAs("PDF 에 ${outsider.extension} 를 요청했는데 통과했다")
                .isInstanceOf(ConflictException::class.java)
                .hasMessage(EXPORT_FORMAT_CHOICE_MISMATCH_MESSAGE)
        }
        assertThat(world.exporter.calls).isEmpty()
    }

    @Test
    @DisplayName("PDF 원본은 고른 형식으로 **신문서를 조립한다** — 원본이 저장돼 있어도 열어 반영하지 않는다")
    fun `PDF 는 선택지로 신문서를 만든다`() {
        ExportFormat.choicesFor(SourceFormat.PDF).forEach { choice ->
            val world = World()
            val conversionId = UUID.randomUUID()
            world.seedDone(conversionId, Seed(easyText = "쉬운 글", sourceFormat = SourceFormat.PDF))
            world.seedOriginal(conversionId)

            val file = world.export(conversionId, choice)

            assertThat(world.reflector.reflected)
                .describedAs("PDF 원본을 열어 반영하려 했다 — §6.5 재결정은 원본을 열지 않는다고 정했다")
                .isEmpty()
            assertThat(world.exporter.calls.map { it.format }).containsExactly(choice)
            assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("쉬운 글")
        }
    }

    @Test
    @DisplayName("형식 판정이 **완료 판정보다 먼저**다 — 기다려도 바뀌지 않는 사실을 먼저 말한다")
    fun `형식이 완료보다 먼저다`() {
        val pending = ConversionStatus.entries.first { !it.exposesResult }
        val world = World()
        val mismatched = UUID.randomUUID()
        val choiceRequired = UUID.randomUUID()
        world.seedDone(mismatched, Seed(easyText = "본문", sourceFormat = SourceFormat.DOCX, status = pending))
        world.seedDone(choiceRequired, Seed(easyText = "본문", sourceFormat = SourceFormat.PDF, status = pending))

        assertThatThrownBy { world.export(mismatched, ExportFormat.TXT) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(EXPORT_FORMAT_MISMATCH_MESSAGE)
        assertThatThrownBy { world.export(choiceRequired, null) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(EXPORT_FORMAT_CHOICE_REQUIRED_MESSAGE)
    }

    @Test
    @DisplayName("남의 변환에서는 **404 가 먼저다** — 형식 불일치가 남의 문서 형식을 알려 주지 않는다")
    fun `소유 판정이 형식 판정보다 먼저다`() {
        val world = World()
        val theirs = UUID.randomUUID()
        world.seedDone(theirs, Seed(easyText = "본문", sourceFormat = SourceFormat.DOCX), owner = STRANGER)

        assertThatThrownBy { world.export(theirs, ExportFormat.TXT) }
            .describedAs("409 가 나가면 「남의 문서는 DOCX 가 아니다」가 형식 축으로 샌다")
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)
    }

    @Test
    @DisplayName("원본이 남아 있으면 **원본 구조에 반영한** 파일이 나간다 — 새 문서를 만들지 않는다")
    fun `원본이 있으면 반영한다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, Seed(easyText = "쉬운 글", sourceFormat = SourceFormat.DOCX))
        world.seedOriginal(conversionId)
        world.reflector.file = exportFileOf("안내문", ExportFormat.DOCX, "반영된 원본".toByteArray())

        val file = world.export(conversionId)

        assertThat(world.reflector.reflected).containsExactly(SourceFormat.DOCX)
        assertThat(world.exporter.calls).describedAs("원본이 있는데 새 문서를 만들었다").isEmpty()
        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("반영된 원본")
    }

    /**
     * 원본이 있으면 `segmentMapDerivation` 이 유도한 지도를 실제로 반영 포트에 실어 보내야
     * 한다(계획 §10.2 결정 2, 2026-09-06 리뷰 F3) — 유도만 하고 부르는 쪽이 넘기지 않으면
     * 어댑터가 영원히 `null` 만 받는다.
     */
    @Test
    @DisplayName("원본이 있으면 반영기에 유도한 지도를 실어 보낸다")
    fun `원본이 있으면 반영기가 지도를 받는다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, Seed(easyText = "쉬운 글\n둘째 문단", sourceFormat = SourceFormat.DOCX))
        world.seedOriginal(conversionId)
        val documentId =
            world.conversions.owned
                .getValue(OWNER to conversionId)
                .documentId
        world.documents.seed(OWNER, documentId, "원본 문단 하나\n원본 문단 둘", SourceFormat.DOCX)
        world.reflector.file = exportFileOf("안내문", ExportFormat.DOCX, "반영된 원본".toByteArray())

        world.export(conversionId)

        assertThat(world.reflector.maps).hasSize(1)
        assertThat(world.reflector.maps.single()).isNotNull()
    }

    @Test
    @DisplayName("반영에는 검수본이 간다 — 검수본이 있으면 초안 대신 그것을 반영한다")
    fun `반영에 검수본이 간다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(
            conversionId,
            Seed(
                easyText = "버려질 초안",
                editedText = "검수본입니다.",
                sourceFormat = SourceFormat.HWPX,
            ),
        )
        world.seedOriginal(conversionId)
        world.reflector.file = exportFileOf("안내문", ExportFormat.HWPX, ByteArray(0))

        world.export(conversionId)

        assertThat(world.reflector.bodies).containsExactly("검수본입니다.")
    }

    @Test
    @DisplayName("원본을 열 수 없으면 **500** 이다 — 텍스트 전용 파일로 조용히 대체하지 않는다")
    fun `열 수 없는 원본은 오류다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, Seed(easyText = "쉬운 글", sourceFormat = SourceFormat.DOCX))
        world.seedOriginal(conversionId)
        world.reflector.file = null

        assertThatThrownBy { world.export(conversionId) }
            .describedAs("§6.5: 같은 형식으로 다시 만들 수 없는 이유를 보여 주고 대체하지 않는다")
            .isInstanceOf(StorageException::class.java)
        assertThat(world.exporter.calls).describedAs("반영에 실패하고 새 문서로 접었다").isEmpty()
    }

    @Test
    @DisplayName("원본이 없는 문서는 예전처럼 새 문서를 만든다 — 붙여넣기와 옛 업로드가 그 갈래다")
    fun `원본이 없으면 새 문서를 만든다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, Seed(easyText = "쉬운 글", sourceFormat = SourceFormat.DOCX))

        val file = world.export(conversionId)

        assertThat(world.reflector.reflected).isEmpty()
        assertThat(world.exporter.calls.map { it.format }).containsExactly(ExportFormat.DOCX)
        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("쉬운 글")
    }

    private class World {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        val exporter = RecordingDocumentExporter(transaction)
        val originals = FakeDocumentOriginalRepository(transaction)
        val conversions = FakeConversionRepository(transaction, originals)
        val reflector = FakeOriginalStructureReflector()
        val documents = FakeQueryDocumentRepository(transaction)
        var documentTitle: String = "안내문"
        val service =
            ConversionExportService(
                conversions = conversions,
                cipher = cipher,
                rendering =
                    ExportRendering(
                        OriginalReflection(StoredOriginalReader(originals, cipher), reflector),
                        exporter,
                    ),
                documents = documents,
                segmentMapDerivation = DefaultSegmentMapDerivation(cipher),
                transaction = transaction,
            )

        fun seedDone(
            conversionId: UUID,
            body: Seed = Seed(),
            owner: UUID = OWNER,
        ) {
            fun seal(
                value: String?,
                field: EncryptedField,
            ) = value?.let { cipher.encrypt(PlainBody(it), conversionId, field) }

            conversions.titles[conversionId] = documentTitle
            conversions.owned[owner to conversionId] =
                StoredConversion(
                    id = conversionId,
                    documentId = UUID.randomUUID(),
                    status = body.status,
                    sourceFormat = body.sourceFormat,
                    hasStoredOriginal = false,
                    ciphertexts =
                        ConversionCiphertexts(
                            easyText = seal(body.easyText, EncryptedField.CONVERSION_EASY_TEXT),
                            editedText = seal(body.editedText, EncryptedField.CONVERSION_EDITED_TEXT),
                        ),
                    reviewedAt = if (body.editedText == null) null else Instant.EPOCH,
                    feedbackSubmittedAt = null,
                    model = "test-model",
                    providerName = "fake",
                    inputTokens = 1,
                    outputTokens = 1,
                    failureCode = null,
                )
        }

        /**
         * 이미 심은 변환의 문서에 **업로드 원본**을 붙인다. 「행이 있다」와 「바이트가 열린다」를
         * 함께 세운다 — 둘이 갈리면 내보내기가 실제로 겪는 상태가 아니다.
         */
        fun seedOriginal(
            conversionId: UUID,
            owner: UUID = OWNER,
        ) {
            val stored = conversions.owned.getValue(owner to conversionId)
            conversions.owned[owner to conversionId] = stored.copy(hasStoredOriginal = true)
            originals.insert(
                owner,
                stored.documentId,
                StoredOriginal(
                    bytes =
                        cipher.encryptBytes(
                            PlainBytes(ORIGINAL_BYTES),
                            stored.documentId,
                            EncryptedField.DOCUMENT_ORIGINAL_BYTES,
                        ),
                    byteSize = ORIGINAL_BYTES.size,
                ),
            )
        }

        /** [requested] 의 기본값이 `null` 인 것이 요점이다 — **생략이 기본 경로**다. */
        fun export(
            conversionId: UUID,
            requested: ExportFormat? = null,
            owner: UUID = OWNER,
        ): ExportFile = service.export(owner, conversionId, requested)
    }

    private data class Seed(
        val easyText: String? = null,
        val editedText: String? = null,
        val status: ConversionStatus = ConversionStatus.DONE,
        val sourceFormat: SourceFormat = SourceFormat.TEXT,
    )

    private class RecordingDocumentExporter(private val transaction: RecordingTransactionRunner) : DocumentExporter {
        data class Call(
            val title: String,
            val body: String,
            val format: ExportFormat,
        )

        val calls = mutableListOf<Call>()
        val depthWhenCalled = mutableListOf<Int>()

        override fun export(
            title: String,
            body: String,
            format: ExportFormat,
        ): ExportFile {
            calls += Call(title, body, format)
            depthWhenCalled += transaction.depth
            return exportFileOf(title, format, body.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

        /** 원본 자리에 둘 바이트. 대역 반영기가 열지 않으므로 내용은 아무래도 좋다. */
        val ORIGINAL_BYTES: ByteArray = "원본 바이트".toByteArray()
    }
}

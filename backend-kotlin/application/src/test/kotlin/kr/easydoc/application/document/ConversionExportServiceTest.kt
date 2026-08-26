package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** 내보내기 유스케이스 — HTTP·zip 없이 판정과 복원만 잰다. */
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
    @DisplayName("검수본이 없고 자리표시자가 빠졌으면 409 — 초안을 그대로 배포하지 않는다")
    fun `유실된 초안은 막는다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(
            conversionId,
            Seed(easyText = "주민번호는 생략합니다", masked = listOf(item())),
        )

        assertThatThrownBy { world.export(conversionId) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(EXPORT_MISSING_PLACEHOLDERS_MESSAGE)
        assertThat(world.exporter.calls).isEmpty()
    }

    @Test
    @DisplayName("검수 없는 초안은 자리표시자를 **복원하지 않는다** — 위조 주입을 막는다")
    fun `검수 전에는 원문을 넣지 않는다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(
            conversionId,
            Seed(easyText = "등록번호는 $PLACEHOLDER 입니다.", masked = listOf(item())),
        )

        val file = world.export(conversionId)

        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("등록번호는 $PLACEHOLDER 입니다.")
        assertThat(String(file.content, Charsets.UTF_8)).doesNotContain(ORIGINAL)
    }

    @Test
    @DisplayName("검수본이 있으면 자리표시자를 원문으로 되돌린다 — 이 경로만 복원한다")
    fun `검수본은 복원한다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(
            conversionId,
            Seed(
                easyText = "버려질 초안 $PLACEHOLDER",
                editedText = "검수본 $PLACEHOLDER 입니다.",
                masked = listOf(item()),
            ),
        )

        val file = world.export(conversionId)

        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("검수본 $ORIGINAL 입니다.")
    }

    @Test
    @DisplayName("검수본이 있으면 자리표시자가 빠져도 막지 않는다 — 최종 판단은 담당자 몫이다")
    fun `검수본의 유실은 막지 않는다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(
            conversionId,
            Seed(
                easyText = "초안 $PLACEHOLDER",
                editedText = "담당자가 개인정보를 빼고 다듬은 글입니다.",
                masked = listOf(item()),
            ),
        )

        val file = world.export(conversionId)

        assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("담당자가 개인정보를 빼고 다듬은 글입니다.")
    }

    @Test
    @DisplayName("파일명에 문서 제목을 쓴다")
    fun `제목이 파일명이다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.documentTitle = "기초연금 신청 안내"
        world.seedDone(conversionId, Seed(easyText = "본문"))

        val file = world.export(conversionId, ExportFormat.TXT)

        assertThat(file.filename).isEqualTo("기초연금 신청 안내.txt")
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
    @DisplayName("내보낼 형식이 없는 원본은 **어떤 값도·생략도** 409 다 — 대체 형식으로 접지 않는다")
    fun `내보낼 수단이 없으면 409 다`() {
        val unexportable = SourceFormat.entries.filter { ExportFormat.ofSource(it) == null }
        assertThat(unexportable)
            .describedAs("상이 `null` 인 원본이 하나도 없다 — 이 대조가 공허해진다")
            .isNotEmpty()

        unexportable.forEach { source ->
            (ExportFormat.entries + null).forEach { requested ->
                val world = World()
                val conversionId = UUID.randomUUID()
                world.seedDone(conversionId, Seed(easyText = "본문", sourceFormat = source))

                assertThatThrownBy { world.export(conversionId, requested) }
                    .describedAs("원본 ${source.wireName} · 요청 $requested 가 통과했다")
                    .isInstanceOf(ConflictException::class.java)
                    .hasMessage(EXPORT_FORMAT_UNAVAILABLE_MESSAGE)
                assertThat(world.exporter.calls).isEmpty()
            }
        }
    }

    @Test
    @DisplayName("형식 판정이 **완료 판정보다 먼저**다 — 기다려도 바뀌지 않는 사실을 먼저 말한다")
    fun `형식이 완료보다 먼저다`() {
        val pending = ConversionStatus.entries.first { !it.exposesResult }
        val world = World()
        val mismatched = UUID.randomUUID()
        val unexportable = UUID.randomUUID()
        world.seedDone(mismatched, Seed(easyText = "본문", sourceFormat = SourceFormat.DOCX, status = pending))
        world.seedDone(unexportable, Seed(easyText = "본문", sourceFormat = SourceFormat.PDF, status = pending))

        assertThatThrownBy { world.export(mismatched, ExportFormat.TXT) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(EXPORT_FORMAT_MISMATCH_MESSAGE)
        assertThatThrownBy { world.export(unexportable, null) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(EXPORT_FORMAT_UNAVAILABLE_MESSAGE)
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

    private class World {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        val conversions = FakeConversionRepository(transaction)
        val maskedItems = RecordingMaskedItemReader()
        val exporter = RecordingDocumentExporter(transaction)
        var documentTitle: String = "안내문"
        val service =
            ConversionExportService(
                conversions = conversions,
                cipher = cipher,
                maskedItems = maskedItems,
                exporter = exporter,
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
                            maskedItems =
                                seal(
                                    body.masked.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.placeholder },
                                    EncryptedField.CONVERSION_MASKED_ITEMS,
                                ),
                            editedText = seal(body.editedText, EncryptedField.CONVERSION_EDITED_TEXT),
                        ),
                    reviewedAt = if (body.editedText == null) null else Instant.EPOCH,
                    missingPlaceholders = emptyList(),
                    model = "test-model",
                    providerName = "fake",
                    inputTokens = 1,
                    outputTokens = 1,
                    failureCode = null,
                )
            maskedItems.byPlaceholder = body.masked.associateBy { it.placeholder }
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
        val masked: List<MaskedItemView> = emptyList(),
        val status: ConversionStatus = ConversionStatus.DONE,
        val sourceFormat: SourceFormat = SourceFormat.TEXT,
    )

    /** 자리표시자 한 줄을 항목으로 되살린다. 원값은 [byPlaceholder] 가 정한다. */
    private class RecordingMaskedItemReader : MaskedItemReader {
        var byPlaceholder: Map<String, MaskedItemView> = emptyMap()

        override fun decode(body: PlainBody): List<MaskedItemView> =
            body.value
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { placeholder ->
                    byPlaceholder[placeholder]
                        ?: MaskedItemView(MaskCategory.RRN, placeholder, Secret("가린값"))
                }.toList()
    }

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
        const val PLACEHOLDER: String = "[[주민등록번호1]]"
        const val ORIGINAL: String = "900101-1234567"

        fun item(): MaskedItemView = MaskedItemView(MaskCategory.RRN, PLACEHOLDER, Secret(ORIGINAL))
    }
}

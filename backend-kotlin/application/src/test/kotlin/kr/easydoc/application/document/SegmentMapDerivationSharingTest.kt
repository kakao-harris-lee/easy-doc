package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.core.segment.SegmentMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 계획 §10.2 결정 1 「지도의 원천은 하나다」 — 조회(`ConversionQueryService`)와
 * 내보내기(`ConversionExportService`)가 **같은 `SegmentMapDerivation` 인스턴스**를
 * 주입받고, 같은 변환을 볼 때 그 호출 인자(원문·본문)가 같음을 잰다(§10.4 마지막 항목).
 *
 * 두 서비스가 각자 지도를 계산하면 조회 화면이 보여 준 지도와 내보내기가 실제로 적용할
 * 지도가 갈릴 수 있다(계획 §10.1) — 이 테스트는 그 회귀를 막는다.
 */
class SegmentMapDerivationSharingTest {
    @Test
    @DisplayName("조회와 내보내기가 같은 SegmentMapDerivation 을 같은 인자로 부른다")
    fun `조회와 내보내기가 지도 유도를 같은 인자로 부른다`() {
        val world = World()
        world.seed()
        world.reflector.file = exportFileOf("안내문", ExportFormat.DOCX, "반영된 원본".toByteArray())

        world.query.read(OWNER, world.conversionId)
        world.export.export(OWNER, world.conversionId, ExportFormat.DOCX)

        assertThat(world.derivation.calls).hasSize(2)
        val (queryCall, exportCall) = world.derivation.calls
        assertThat(exportCall.documentId)
            .describedAs("조회가 읽은 원문 행과 내보내기가 읽은 원문 행이 같은 문서를 가리켜야 한다")
            .isEqualTo(queryCall.documentId)
        assertThat(exportCall.bodyLength)
            .describedAs("두 호출부 모두 edited_text ?: easy_text — 복원 전 본문 길이가 같아야 한다")
            .isEqualTo(queryCall.bodyLength)
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
        val ORIGINAL_BYTES: ByteArray = "원본 바이트".toByteArray()
    }

    /** 한 케이스가 쓰는 대역 묶음 — 조회·내보내기가 **같은** `derivation` 을 주입받는다. */
    private class World {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        val originals = FakeDocumentOriginalRepository(transaction)
        val conversions = FakeConversionRepository(transaction, originals)
        val documents = FakeQueryDocumentRepository(transaction)
        val reflector = FakeOriginalStructureReflector()
        val derivation = RecordingSegmentMapDerivation(MaskedSegmentMapDerivation(cipher))

        val conversionId: UUID = UUID.randomUUID()
        val documentId: UUID = UUID.randomUUID()

        val query =
            ConversionQueryService(
                conversions = conversions,
                cipher = cipher,
                maskedItems = FakeMaskedItemReader(),
                original = OriginalReflection(StoredOriginalReader(originals, cipher), reflector),
                documents = documents,
                segmentMapDerivation = derivation,
                transaction = transaction,
            )

        val export =
            ConversionExportService(
                conversions = conversions,
                cipher = cipher,
                maskedItems = FakeMaskedItemReader(),
                rendering =
                    ExportRendering(
                        OriginalReflection(StoredOriginalReader(originals, cipher), reflector),
                        DocumentExporter { title, body, format -> exportFileOf(title, format, body.toByteArray()) },
                    ),
                documents = documents,
                segmentMapDerivation = derivation,
                transaction = transaction,
            )

        /**
         * 완료 변환 한 건(원본 있는 DOCX) + 그 문서의 원문 한 줄 — 조회·내보내기 둘 다
         * 이 한 쌍을 본다. 원본이 있어야 내보내기도 지도를 유도한다(계획 §10.2 F9).
         */
        fun seed() {
            val easyText =
                cipher.encrypt(PlainBody("쉬운 글 초안"), conversionId, EncryptedField.CONVERSION_EASY_TEXT)
            conversions.owned[OWNER to conversionId] =
                StoredConversion(
                    id = conversionId,
                    documentId = documentId,
                    status = ConversionStatus.DONE,
                    sourceFormat = SourceFormat.DOCX,
                    hasStoredOriginal = true,
                    ciphertexts = ConversionCiphertexts(easyText = easyText, maskedItems = null, editedText = null),
                    reviewedAt = null,
                    feedbackSubmittedAt = null,
                    missingPlaceholders = emptyList(),
                    model = null,
                    providerName = null,
                    inputTokens = null,
                    outputTokens = null,
                    failureCode = null,
                )
            originals.insert(
                OWNER,
                documentId,
                StoredOriginal(
                    bytes =
                        cipher.encryptBytes(
                            PlainBytes(ORIGINAL_BYTES),
                            documentId,
                            EncryptedField.DOCUMENT_ORIGINAL_BYTES,
                        ),
                    byteSize = ORIGINAL_BYTES.size,
                ),
            )
            documents.seed(OWNER, documentId, "원본 문단 하나", SourceFormat.DOCX)
        }
    }
}

/**
 * [SegmentMapDerivation] 을 감싸 호출 인자를 기록하는 데코레이터 — [DocumentExporter] 처럼
 * 포트를 새 구현으로 바꾸지 않고 실물([MaskedSegmentMapDerivation])에 위임한다.
 *
 * **본문 원문은 기록하지 않는다**(길이만) — 사용자 문서 조각이 시험 실패 메시지·리포트에
 * 그대로 찍히지 않게 하는 저장소 관례다(`FormatPreservation.details`·`PreparedExport.toString`
 * 과 같은 규칙).
 */
private class RecordingSegmentMapDerivation(private val delegate: SegmentMapDerivation) : SegmentMapDerivation {
    val calls = mutableListOf<DerivationCall>()

    override fun deriveOrNull(
        source: StoredSourceText?,
        body: PlainBody?,
    ): SegmentMap? {
        calls += DerivationCall(source?.documentId, body?.value?.length)
        return delegate.deriveOrNull(source, body)
    }
}

/** 호출 인자 한 번 — 본문은 **길이만** 남긴다. */
private data class DerivationCall(
    val documentId: UUID?,
    val bodyLength: Int?,
)

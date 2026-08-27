package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.FormatPreservationStatus
import kr.easydoc.core.document.ReflectionOutcome
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.exceptions.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** 변환 조회 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다. */
class ConversionQueryServiceTest {
    @Test
    @DisplayName("소유자를 **저장소 인자로** 넘긴다 — 읽고 나서 비교하는 형태가 아니다")
    fun `조회가 소유자를 질의에 넘긴다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "쉬운 글 초안")

        world.service.read(OWNER, conversionId)

        assertThat(world.conversions.reads).containsExactly(OWNER to conversionId)
    }

    @Test
    @DisplayName("남의 변환과 없는 변환이 **같은 404·같은 문구**다 — 두 갈래를 구분하지 않는다")
    fun `남의 변환은 404 다`() {
        val world = World()
        val theirs = UUID.randomUUID()
        world.seedResults(theirs, easyText = "쉬운 글 초안", owner = STRANGER)

        assertThatThrownBy { world.service.read(OWNER, theirs) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)

        assertThatThrownBy { world.service.read(OWNER, UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)
    }

    @Test
    @DisplayName("완료 변환은 세 열을 **복호화해서** 돌려준다 — 결속이 변환 행 식별자다")
    fun `완료 변환의 본문이 복호화된다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(
            conversionId,
            easyText = "쉬운 글 초안",
            editedText = "검수본",
            maskedLabels = listOf("[[주민등록번호1]]"),
        )

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.easyText?.value).isEqualTo("쉬운 글 초안")
        assertThat(view.editedText?.value).isEqualTo("검수본")
        assertThat(view.maskedItems.map { it.placeholder }).containsExactly("[[주민등록번호1]]")

        assertThat(world.cipher.decryptions.map { it.first }).containsOnly(conversionId)
        assertThat(world.cipher.decryptions.map { it.second })
            .containsExactlyInAnyOrder(
                EncryptedField.CONVERSION_EASY_TEXT,
                EncryptedField.CONVERSION_EDITED_TEXT,
                EncryptedField.CONVERSION_MASKED_ITEMS,
            )
    }

    @Test
    @DisplayName("대기 중 변환은 본문이 `null` 이고 배열 둘이 **빈 목록**이다 — `null` 이 아니다 (X-E3)")
    fun `대기 중 변환은 빈 배열을 준다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedPending(conversionId)

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.status).isEqualTo(ConversionStatus.PENDING)
        assertThat(view.easyText).isNull()
        assertThat(view.editedText).isNull()
        assertThat(view.maskedItems).isEmpty()
        assertThat(view.missingPlaceholders).isEmpty()

        assertThat(world.cipher.decryptions).isEmpty()
    }

    @Test
    @DisplayName("완료 전 변환이 결과 열을 들고 있어도 **결과 필드 아홉이 전부** 비고 복호화조차 하지 않는다")
    fun `완료 전 변환은 저장된 결과를 내보내지 않는다`() {
        // 분모를 `exposesResult` 로 잡으면 잘못 준 상태가 **빠진다** — 이름으로 잡고 값은 따로 잰다.
        val beforeDone = ConversionStatus.entries - ConversionStatus.DONE
        assertThat(beforeDone).describedAs("완료 아닌 상태가 하나도 없다 — 이 케이스가 공허해진다").isNotEmpty()
        assertThat(ConversionStatus.entries.filter { it.exposesResult })
            .describedAs("결과를 내보내는 상태가 `done` 하나가 아니다 — 노출 범위 규칙이 넓어졌다")
            .containsExactly(ConversionStatus.DONE)

        beforeDone.forEach { status ->
            val world = World()
            val conversionId = UUID.randomUUID()
            world.seedResults(
                conversionId,
                easyText = "완료 전인데 저장돼 있던 초안",
                editedText = "완료 전인데 저장돼 있던 검수본",
                maskedLabels = listOf("[[주민등록번호1]]"),
            )
            world.demoteTo(conversionId, status)

            val view = world.service.read(OWNER, conversionId)

            assertThat(view.status).isEqualTo(status)
            assertThat(view.carriesResult)
                .describedAs("상태 %s 인데 결과 필드가 값을 들었다: %s", status, view)
                .isFalse()
            assertThat(view.failureCode).describedAs("완료 전에도 나가야 하는 넷 중 하나가 지워졌다").isNotNull()
            assertThat(view.reviewedAt).describedAs("상태 %s 인데 검수 시각이 실렸다", status).isNull()
            assertThat(view.model).isNull()
            assertThat(view.providerName).isNull()
            assertThat(view.inputTokens).isNull()
            assertThat(view.outputTokens).isNull()
            assertThat(view.missingPlaceholders).describedAs("배열은 `null` 이 아니라 **빈 목록**이다").isEmpty()
            assertThat(view.easyText).describedAs("상태 %s 인데 초안이 실렸다", status).isNull()
            assertThat(view.editedText).describedAs("상태 %s 인데 검수본이 실렸다", status).isNull()
            assertThat(view.maskedItems).describedAs("상태 %s 인데 마스킹 대응표가 실렸다", status).isEmpty()
            assertThat(world.cipher.decryptions)
                .describedAs("상태 %s 에서 복호화가 돌았다 — 버릴 값을 평문으로 만들었다", status)
                .isEmpty()
        }
    }

    @Test
    @DisplayName("원본이 남지 않은 문서는 `not_applicable` 이다 — 영구히 참인 판정이다")
    fun `원본이 없으면 판정이 없다고 말한다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "쉬운 글 초안")
        world.seedOrigin(conversionId, SeededOrigin(SourceFormat.DOCX, hasStoredOriginal = false))

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.exportFormat).describedAs("들어온 형식 그대로 나간다").isEqualTo(ExportFormat.DOCX)
        assertThat(view.formatPreservation?.status).isEqualTo(FormatPreservationStatus.NOT_APPLICABLE)
        assertThat(view.formatPreservation?.details).isEmpty()
        assertThat(world.reflector.outlined).describedAs("열 원본이 없는데 반영기를 불렀다").isEmpty()
    }

    @Test
    @DisplayName("원본이 남은 완료 변환은 **반영 결과를 미리 재서** 판정한다")
    fun `원본이 있으면 반영 결과로 판정한다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "쉬운 글 초안")
        world.seedOrigin(conversionId, SeededOrigin(SourceFormat.DOCX, hasStoredOriginal = true))
        world.reflector.outcome =
            ReflectionOutcome(headerFooterUnits = 2, emptiedUnits = 1, appendedLines = 0, displacedLines = 0)

        val view = world.service.read(OWNER, conversionId)

        assertThat(world.reflector.outlined)
            .describedAs("판정은 저장된 원본을 실제로 열어 재야 한다 — 형식만 보고 지어내지 않는다")
            .containsExactly(SourceFormat.DOCX)
        assertThat(view.formatPreservation?.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(view.formatPreservation?.details).hasSize(3)
    }

    @Test
    @DisplayName("짝이 하나도 어긋나지 않으면 `available` 이다")
    fun `짝이 맞으면 유지 가능이다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "쉬운 글 초안")
        world.seedOrigin(conversionId, SeededOrigin(SourceFormat.HWPX, hasStoredOriginal = true))
        world.reflector.outcome =
            ReflectionOutcome(headerFooterUnits = 0, emptiedUnits = 0, appendedLines = 0, displacedLines = 0)

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.formatPreservation?.status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(view.formatPreservation?.details).isEmpty()
    }

    @Test
    @DisplayName("원본을 열 수 없으면 `failed` 다 — 사유를 항목으로 준다")
    fun `열 수 없는 원본은 실패로 나간다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "쉬운 글 초안")
        world.seedOrigin(conversionId, SeededOrigin(SourceFormat.DOCX, hasStoredOriginal = true))
        world.reflector.outcome = null

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.formatPreservation?.status).isEqualTo(FormatPreservationStatus.FAILED)
        assertThat(view.formatPreservation?.details).hasSize(1)
    }

    @Test
    @DisplayName("검수본이 있으면 **그것을** 판정의 한쪽으로 쓴다 — 초안이 아니다")
    fun `검수본이 판정의 한쪽이다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "초안 한 줄", editedText = "검수 한 줄\n검수 두 줄")
        world.seedOrigin(conversionId, SeededOrigin(SourceFormat.DOCX, hasStoredOriginal = true))

        world.service.read(OWNER, conversionId)

        assertThat(world.reflector.outlined).containsExactly(SourceFormat.DOCX)
    }

    @Test
    @DisplayName("완료 전에는 원본이 있어도 **판정하지 않는다** — 짝지을 검수본이 아직 없다")
    fun `완료 전에는 판정하지 않는다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "쉬운 글 초안")
        world.seedOrigin(conversionId, SeededOrigin(SourceFormat.DOCX, hasStoredOriginal = true))
        world.demoteTo(conversionId, ConversionStatus.PROCESSING)

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.formatPreservation)
            .describedAs("`checking` 으로 접지 않는다 — 이 판정은 조회 한 번 안에서 동기로 끝난다")
            .isNull()
        assertThat(world.reflector.outlined).describedAs("완료 전에 10MB 원본을 열었다").isEmpty()
    }

    @Test
    @DisplayName("원본이 PDF 면 `exportFormat` 이 `null` 이다 — 대체 형식으로 접지 않는다")
    fun `PDF 는 내보내기 형식이 없다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "쉬운 글 초안")
        world.seedOrigin(conversionId, SeededOrigin(SourceFormat.PDF, hasStoredOriginal = true))

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.sourceFormat).isEqualTo(SourceFormat.PDF)
        assertThat(view.exportFormat)
            .describedAs("PDF 렌더러가 없다 — TXT·DOCX 로 접으면 계약이 우회 다운로드를 권하는 것이 된다")
            .isNull()
        assertThat(view.formatPreservation)
            .describedAs("반영할 대상 형식이 없다 — 「유지 불가」가 아니라 판정하지 않는다")
            .isNull()
        assertThat(world.reflector.outlined).describedAs("PDF 원본을 열려고 했다").isEmpty()
    }

    @Test
    @DisplayName("형식 셋은 **완료 전에도** 실린다 — 결과 필드가 아니므로 `carriesResult` 가 세지 않는다")
    fun `완료 전에도 형식 셋이 실린다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedPending(conversionId)
        world.seedOrigin(conversionId, SeededOrigin(SourceFormat.HWPX, hasStoredOriginal = true))

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.status).isEqualTo(ConversionStatus.PENDING)
        assertThat(view.sourceFormat).isEqualTo(SourceFormat.HWPX)
        assertThat(view.exportFormat).isEqualTo(ExportFormat.HWPX)
        assertThat(view.carriesResult)
            .describedAs("형식 셋이 「결과 필드」로 세어졌다 — 응답 조립이 완료 전에 막힌다")
            .isFalse()
    }

    @Test
    @DisplayName("읽기는 트랜잭션 **안**, 복호화는 **밖**이다 — 커넥션을 쥔 채 열지 않는다")
    fun `복호화가 트랜잭션 밖이다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "쉬운 글 초안")

        world.service.read(OWNER, conversionId)

        assertThat(world.conversions.depthWhenRead).containsExactly(1)
        assertThat(world.cipher.depthWhenDecrypted).describedAs("복호화가 경계 안에서 돌았다").containsOnly(0)
    }

    @Test
    @DisplayName("조회 결과의 `toString` 이 **본문도 가린 값도 담지 않는다** — 개수까지다")
    fun `조회 결과의 toString 이 본문을 담지 않는다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedResults(conversionId, easyText = "민감한 초안 본문", maskedLabels = listOf("[[카드번호1]]"))

        val rendered = world.service.read(OWNER, conversionId).toString()

        assertThat(rendered).doesNotContain("민감한 초안 본문").doesNotContain("가린값")
        assertThat(rendered).contains(conversionId.toString()).contains("masked=1")
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val STRANGER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a2")

        /** 원본 자리에 둘 바이트. 대역 반영기가 열지 않으므로 내용은 아무래도 좋다. */
        val ORIGINAL_BYTES: ByteArray = "원본 바이트".toByteArray()
    }

    /**
     * 문서가 어디서 왔고 그 원본이 남아 있는가 — **둘을 함께 준다.**
     *
     * 「형식은 DOCX 인데 원본은 없다」가 실재하는 조합이라(표가 서기 전 업로드) 하나로
     * 접을 수 없고, 두 인자를 따로 늘어놓으면 시드 함수가 detekt `LongParameterList`
     * 문턱을 넘는다. 함께 다니는 값이므로 함께 묶는다.
     */
    private data class SeededOrigin(
        val sourceFormat: SourceFormat = SourceFormat.TEXT,
        val hasStoredOriginal: Boolean = false,
    )

    /** 한 케이스가 쓰는 대역 묶음. 케이스마다 새로 만든다 — 대역이 상태를 들고 있다. */
    private class World {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        val conversions = FakeConversionRepository(transaction)
        val maskedItems = FakeMaskedItemReader()
        val originals = FakeDocumentOriginalRepository(transaction)
        val reflector = FakeOriginalStructureReflector()

        val service =
            ConversionQueryService(
                conversions = conversions,
                cipher = cipher,
                maskedItems = maskedItems,
                original = OriginalReflection(StoredOriginalReader(originals, cipher), reflector),
                transaction = transaction,
            )

        /** 대기 중 변환 한 건 — 암호문 세 열이 전부 `null` 이다(실물 `insertPending` 과 같다). */
        fun seedPending(
            conversionId: UUID,
            owner: UUID = OWNER,
        ) {
            conversions.owned[owner to conversionId] =
                StoredConversion(
                    id = conversionId,
                    documentId = UUID.randomUUID(),
                    status = ConversionStatus.PENDING,
                    sourceFormat = SeededOrigin().sourceFormat,
                    hasStoredOriginal = SeededOrigin().hasStoredOriginal,
                    ciphertexts = ConversionCiphertexts(easyText = null, maskedItems = null, editedText = null),
                    reviewedAt = null,
                    missingPlaceholders = emptyList(),
                    model = null,
                    providerName = null,
                    inputTokens = null,
                    outputTokens = null,
                    failureCode = null,
                )
        }

        /** 결과 열을 채운 뒤 상태만 되돌린다 — HTTP 팔의 `forceStatus` 와 같다. */
        fun demoteTo(
            conversionId: UUID,
            status: ConversionStatus,
            owner: UUID = OWNER,
        ) {
            val key = owner to conversionId
            conversions.owned[key] = conversions.owned.getValue(key).copy(status = status)
        }

        /**
         * 이미 심은 행의 **출처**만 갈아 끼운다 — `demoteTo` 와 같은 형태다.
         *
         * [seedResults] 의 인자로 받지 않는 이유는 detekt `LongParameterList` 문턱(6)이다.
         * 출처는 결과 열과 함께 다니는 값이 아니라 **문서 쪽 사실**이라, 따로 세우는 편이
         * 그 사실을 더 잘 드러내기도 한다.
         */
        fun seedOrigin(
            conversionId: UUID,
            origin: SeededOrigin,
            owner: UUID = OWNER,
        ) {
            val key = owner to conversionId
            val stored = conversions.owned.getValue(key)
            conversions.owned[key] =
                stored.copy(sourceFormat = origin.sourceFormat, hasStoredOriginal = origin.hasStoredOriginal)
            // 「행이 있다」와 「바이트가 열린다」가 실제로 같은 사실이어야 한다 — 표에도 함께 심는다.
            if (origin.hasStoredOriginal) {
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
        }

        /**
         * 결과 열을 채운 완료 변환 한 건. 암호문은 [cipher] 를 거쳐 만든다 — 대역이라도 평문을
         * 컬럼 자리에 두면 「복호화가 실제로 돌았는가」를 잴 수 없다.
         */
        fun seedResults(
            conversionId: UUID,
            easyText: String? = null,
            editedText: String? = null,
            maskedLabels: List<String> = emptyList(),
            owner: UUID = OWNER,
        ) {
            fun seal(
                value: String?,
                field: EncryptedField,
            ) = value?.let { cipher.encrypt(PlainBody(it), conversionId, field) }

            conversions.owned[owner to conversionId] =
                StoredConversion(
                    id = conversionId,
                    documentId = UUID.randomUUID(),
                    status = ConversionStatus.DONE,
                    sourceFormat = SeededOrigin().sourceFormat,
                    hasStoredOriginal = SeededOrigin().hasStoredOriginal,
                    ciphertexts =
                        ConversionCiphertexts(
                            easyText = seal(easyText, EncryptedField.CONVERSION_EASY_TEXT),
                            maskedItems =
                                seal(
                                    maskedLabels.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                                    EncryptedField.CONVERSION_MASKED_ITEMS,
                                ),
                            editedText = seal(editedText, EncryptedField.CONVERSION_EDITED_TEXT),
                        ),
                    // 결과 필드 **아홉 전부**를 채운다 — 비워 두면 그 필드가 공허하게 통과한다.
                    reviewedAt = Instant.EPOCH,
                    missingPlaceholders = maskedLabels,
                    model = "claude-test",
                    providerName = "anthropic",
                    inputTokens = 11,
                    outputTokens = 22,
                    failureCode = "ProviderUnavailable",
                )
        }
    }
}

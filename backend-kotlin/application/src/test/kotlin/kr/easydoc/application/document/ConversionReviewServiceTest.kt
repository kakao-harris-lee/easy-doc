package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.FormatPreservationStatus
import kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.privacy.ReviewedBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** 검수 저장 유스케이스 — Spring·DB 없이 대역으로 돈다. 실물 SQL 은 별도 테스트가 잰다. */
class ConversionReviewServiceTest {
    @Test
    @DisplayName("판정 순서 — 정규화·길이가 **소유권보다 앞이다**: 없는 자원에도 422 가 먼저 나간다")
    fun `입력 판정이 소유권보다 앞선다`() {
        val world = World()

        assertThatThrownBy { world.save(UUID.randomUUID(), " \u0001 ") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(EMPTY_REVIEW_MESSAGE)

        assertThat(world.conversions.depthWhenLocked)
            .withFailMessage("입력이 거절됐는데 자원을 조회했다 — 판정 순서가 뒤집혔고 거절 비용이 존재에 좌우된다")
            .isEmpty()
    }

    @Test
    @DisplayName("빈 값 판정만 앞뒤 공백을 턴다 — **저장되는 값은 털지 않는다**")
    fun `공백은 판정에만 쓰이고 저장 값에는 남는다`() {
        val world = World()
        val conversionId = world.seedDone()

        world.save(conversionId, "  다듬은 문장  ")

        assertThat(world.savedPlaintext(EncryptedField.CONVERSION_EDITED_TEXT))
            .withFailMessage("저장 값에서 앞뒤 공백이 사라졌다 — 계약은 이 필드의 정규화를 제어문자 제거로만 정의했다")
            .isEqualTo("  다듬은 문장  ")
    }

    @Test
    @DisplayName("길이는 **정규화 후**에 재고 코드 포인트로 센다 — 경계 양쪽을 함께 고정한다")
    fun `길이 판정이 정규화 후 코드 포인트다`() {
        val world = World()
        val padding = "\u0001".repeat(NOISE)

        world.save(world.seedDone(), "가".repeat(MAX_CONVERTIBLE_CHARS) + padding)

        assertThatThrownBy { world.save(world.seedDone(), "가".repeat(MAX_CONVERTIBLE_CHARS + 1)) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(REVIEW_TOO_LONG_MESSAGE)
    }

    @Test
    @DisplayName("**BMP 밖 문자**로 잰다 — 코드 단위로 세면 상한 이내인 값이 거절된다")
    fun `코드 포인트와 코드 단위가 갈리는 자리`() {
        val world = World()
        // 서로게이트 **쌍**이라 저장 정의역은 통과한다. 코드 포인트로는 상한, 코드 단위로는 두 배다.
        val atLimit = SUPPLEMENTARY.repeat(MAX_CONVERTIBLE_CHARS)
        assertThat(atLimit.length).isEqualTo(MAX_CONVERTIBLE_CHARS * 2)
        assertThat(atLimit.codePointCount(0, atLimit.length)).isEqualTo(MAX_CONVERTIBLE_CHARS)

        // 코드 단위로 세는 구현이면 여기서 거절한다 — 그것이 이 케이스가 잡는 변이다.
        world.save(world.seedDone(), atLimit)

        assertThatThrownBy { world.save(world.seedDone(), SUPPLEMENTARY.repeat(MAX_CONVERTIBLE_CHARS + 1)) }
            .withFailMessage("코드 포인트로 상한을 넘었는데 통과했다")
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(REVIEW_TOO_LONG_MESSAGE)
    }

    @Test
    @DisplayName("없거나 남의 변환은 404 다 — **두 경우를 구분하지 않는다**")
    fun `없는 것과 남의 것이 같은 404 다`() {
        val world = World()
        val mine = world.seedDone()

        assertThatThrownBy { world.save(UUID.randomUUID(), VALID) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)
        assertThatThrownBy { world.save(mine, VALID, owner = STRANGER) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)
    }

    @Test
    @DisplayName("완료 전 상태 **전부**가 409 다 — 분모를 `exposesResult` 가 아니라 enum 전체로 잡는다")
    fun `완료 전 상태는 전부 409 다`() {
        val beforeDone = ConversionStatus.entries - ConversionStatus.DONE
        assertThat(beforeDone).isNotEmpty()

        beforeDone.forEach { status ->
            val world = World()
            val conversionId = world.seedDone(status = status)

            assertThatThrownBy { world.save(conversionId, VALID) }
                .describedAs("상태 %s", status)
                .isInstanceOf(ConflictException::class.java)
                .hasMessage(CONVERSION_NOT_DONE_MESSAGE)
        }
    }

    @Test
    @DisplayName("조건부 UPDATE 가 0행이면 **500 이다(409 가 아니다)** — 잠금 전제가 깨졌다는 신호다")
    fun `0행은 상태 충돌이 아니라 서버 결함이다`() {
        val world = World()
        val conversionId = world.seedDone()
        world.conversions.saveReviewSucceeds = false

        assertThatThrownBy { world.save(conversionId, VALID) }
            .withFailMessage("0행을 409 로 접으면 사용자에게 거짓 안내가 나가고 서버 결함이 정상 흐름으로 위장된다")
            .isInstanceOf(StorageException::class.java)
            .hasMessage(REVIEW_NOT_SAVED_MESSAGE)
    }

    @Test
    @DisplayName("저장이 **읽은 행 그 자체**를 조건으로 걸고 `done` 을 함께 요구한다 — 조건을 좁게 쓰는 갈래가 없다")
    fun `저장 조건이 읽은 행과 상태다`() {
        val world = World()
        val conversionId = world.seedDone()
        // **저장 전에** 집어 둔다 — 저장이 실물처럼 그 행을 고치므로(대역도 그렇다) 저장 뒤에
        // 읽으면 방금 쓴 봉투가 잡힌다.
        val locked =
            world.conversions.lockedForReview
                .getValue(OWNER to conversionId)
                .envelope

        world.save(conversionId, VALID)

        val call = world.conversions.savedReviews.single()
        assertThat(call.expected)
            .withFailMessage("조건으로 넘긴 것이 잠그고 읽은 행이 아니다 — 다른 값을 조건으로 고를 자유가 남아 있다")
            .isSameAs(locked)
        assertThat(call.requiredStatus).isEqualTo(ConversionStatus.DONE)
        assertThat(call.depth)
            .withFailMessage("검수 저장이 트랜잭션 밖에서 돌았다 — 잠금과 UPDATE 가 갈리면 잠금이 아무것도 막지 못한다")
            .isGreaterThan(0)
    }

    @Test
    @DisplayName("행이 **이미 쓰기 세대**면 초안·대응표는 읽은 바이트 그대로 되쓴다 — 그래야 초안 보존을 바이트로 잰다")
    fun `같은 세대면 나머지 두 열이 그대로다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT, maskedTable = TABLE)
        val locked =
            world.conversions.lockedForReview
                .getValue(OWNER to conversionId)
                .envelope

        world.save(conversionId, VALID)

        val written =
            world.conversions.savedReviews
                .single()
                .updated.ciphertexts
        assertThat(written.easyText)
            .withFailMessage("행이 이미 쓰기 세대인데 초안을 다시 봉인했다 — 암호문이 바뀌면 「덮어쓰지 않았다」를 바이트로 못 잰다")
            .isSameAs(locked.ciphertexts.easyText)
        assertThat(written.maskedItems).isSameAs(locked.ciphertexts.maskedItems)
        assertThat(written.editedText).isNotNull()
    }

    @Test
    @DisplayName("행 세대가 **뒤처져 있으면** 세 열을 전부 쓰기 세대로 올리고 평문 셋이 보존된다 — 옛 키로 쓰지 않는다")
    fun `세대가 뒤처지면 행 전체를 올린다`() {
        val world = World(writeKeyVersion = NEXT_KEY_VERSION)
        val conversionId = world.seedDone(draft = DRAFT, maskedTable = TABLE, keyVersion = 1)

        world.save(conversionId, VALID)

        val call = world.conversions.savedReviews.single()
        assertThat(call.updated.keyVersion)
            .withFailMessage("행 라벨이 쓰기 세대로 올라가지 않았다 — 라벨과 열 내용이 어긋나면 그 행은 영영 열리지 않는다")
            .isEqualTo(NEXT_KEY_VERSION)
        listOf(
            call.updated.ciphertexts.easyText,
            call.updated.ciphertexts.maskedItems,
            call.updated.ciphertexts.editedText,
        ).forEach {
            assertThat(it?.keyVersion)
                .withFailMessage("세 열 중 하나가 옛 세대로 남았다 — 봉투는 행 단위라 셋이 같은 세대여야 한다")
                .isEqualTo(NEXT_KEY_VERSION)
        }
        // 평문이 그대로여야 한다 — 재봉인이 값을 바꾸면 조용한 손상이다.
        assertThat(world.savedPlaintext(EncryptedField.CONVERSION_EASY_TEXT)).isEqualTo(DRAFT)
        assertThat(world.savedPlaintext(EncryptedField.CONVERSION_MASKED_ITEMS)).isEqualTo(TABLE)
    }

    @Test
    @DisplayName("저장 정의역은 **길이와 다른 축**이다 — 짝 없는 서로게이트는 길이와 무관하게 422 다")
    fun `저장할 수 없는 문자는 거절된다`() {
        val world = World()
        val conversionId = world.seedDone()

        assertThatThrownBy { world.save(conversionId, "안내\uD800문") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PlainBody.UNPAIRED_SURROGATE_MESSAGE)
        assertThat(world.conversions.savedReviews)
            .withFailMessage("거절됐는데 저장이 일어났다")
            .isEmpty()
    }

    @Test
    @DisplayName("저장 응답의 서식 유지 판정은 **방금 저장한 검수본**으로 다시 잰다 — 조회 때 값을 되풀이하지 않는다")
    fun `저장 응답이 새 검수본으로 판정한다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)
        world.seedStoredOriginal(conversionId, SourceFormat.DOCX)
        world.reflector.originalUnits = ORIGINAL_UNITS

        val aligned = world.save(conversionId, ALIGNED)

        assertThat(world.reflector.outlinedBodies)
            .withFailMessage("판정이 방금 저장한 글을 보지 않았다 — 초안이나 저장 전 값으로 짝을 세면 응답이 거짓말이 된다")
            .containsExactly(ALIGNED)
        assertThat(aligned.formatPreservation?.status).isEqualTo(FormatPreservationStatus.AVAILABLE)

        // 담당자가 검수하며 문단 하나를 둘로 나눈다 — 원본 단위와 짝이 어긋난다.
        val split = world.save(conversionId, SPLIT)

        assertThat(split.formatPreservation?.status)
            .withFailMessage("문단을 나눠 저장했는데 판정이 그대로다 — 화면이 「유지 가능」을 약속한 채 다른 파일이 내려간다")
            .isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(split.formatPreservation?.details).isNotEmpty()
    }

    @Test
    @DisplayName("판정에 원본이 필요한 갈래에서도 원본은 **저장과 같은 트랜잭션 안에서** 열린다")
    fun `판정이 여는 원본도 같은 트랜잭션이다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)
        world.seedStoredOriginal(conversionId, SourceFormat.DOCX)
        world.reflector.originalUnits = ORIGINAL_UNITS
        val documentId =
            world.conversions.owned
                .getValue(OWNER to conversionId)
                .documentId

        world.save(conversionId, ALIGNED)

        // **그 열이 실제로 열렸는가**부터 잰다 — 깊이만 훑으면 초안·검수본 복호화가 조건을
        // 대신 채워 주고, 원본을 한 번도 열지 않는 구현에서도 통과한다.
        val opened = world.cipher.decryptions.indexOf(documentId to EncryptedField.DOCUMENT_ORIGINAL_BYTES)
        assertThat(opened)
            .withFailMessage("판정이 저장된 원본을 열지 않았다 — 열지 않고 낸 판정은 형식만 보고 지어낸 값이다")
            .isNotNegative()
        assertThat(world.cipher.depthWhenDecrypted[opened])
            .withFailMessage("판정이 트랜잭션 밖에서 원본을 열었다 — 저장과 판정 사이에 원본이 지워질 수 있다")
            .isGreaterThan(0)
        // 깊이만으로는 모자란다: 저장 경계가 닫힌 뒤 조회가 자기 경계를 새로 열어도 깊이는
        // 다시 1 이다. **같은 바깥 경계**여야 저장과 판정 사이에 남이 끼어들 틈이 없다.
        assertThat(world.cipher.epochWhenDecrypted[opened])
            .withFailMessage("원본을 연 것이 저장과 다른 트랜잭션이다 — 저장 뒤 판정 전에 문서가 지워질 수 있다")
            .isEqualTo(
                world.conversions.savedReviews
                    .single()
                    .epoch,
            )
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
        val STRANGER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b2")

        const val VALID = "담당자가 다듬은 문장입니다."
        const val DRAFT = "쉬운 글 초안입니다."
        const val TABLE = "대응표 자리"
        const val NOISE = 5
        const val NEXT_KEY_VERSION = 2

        /** BMP 밖 문자(코드 단위 2 · 코드 포인트 1). 서로게이트 **쌍**이라 정의역은 통과한다. */
        const val SUPPLEMENTARY = "\uD83D\uDE00"

        /**
         * 원본 자리에 둘 바이트. **비어 있지만 않으면 된다**(V3 의
         * `ck_document_originals_byte_size_positive`) — 이 계층의 대역 반영기는 파일을 열지
         * 않는다.
         *
         * 진짜 zip 을 두지 않아 여기서 **못 재는 것**은 「저장된 바이트가 실제로 열리는가」와
         * 자리 맞춤 규칙 자체다. 둘 다 재는 자리가 따로 있다 — `PackagedOriginalReflectorTest`
         * 가 진짜 DOCX·HWPX fixture 로 추출 차례·판정·압축 예산을 검증한다. 이 케이스가 재는
         * 것은 그것이 아니라 **유스케이스가 어느 갈래에서 무엇을 열고 그 결과를 응답으로
         * 어떻게 옮기는가**이고, 그 축은 실물 파일 없이 재는 편이 정확하다.
         */
        val ORIGINAL_BYTES: ByteArray = "원본 바이트".toByteArray()

        /** 원본 본문 단위 수. 검수본 문단이 이만큼이면 짝이 맞고, 더 나뉘면 어긋난다. */
        const val ORIGINAL_UNITS = 2

        const val ALIGNED = "첫 문단\n둘째 문단"
        const val SPLIT = "첫 문단\n둘째 문단\n담당자가 나눈 셋째 문단"
    }

    /** 케이스마다 새로 만드는 대역 묶음 — 대역이 상태를 든다. */
    private class World(writeKeyVersion: Int = 1) {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = writeKeyVersion, transaction = transaction)
        val originals = FakeDocumentOriginalRepository(transaction)
        val conversions = FakeConversionRepository(transaction, originals)
        val reflector = FakeOriginalStructureReflector()

        val service =
            ConversionReviewService(
                conversions = conversions,
                cipher = cipher,
                query =
                    ConversionQueryService(
                        conversions = conversions,
                        cipher = cipher,
                        maskedItems = FakeMaskedItemReader(),
                        original = OriginalReflection(StoredOriginalReader(originals, cipher), reflector),
                        documents = FakeQueryDocumentRepository(transaction),
                        transaction = transaction,
                    ),
                transaction = transaction,
            )

        fun save(
            conversionId: UUID,
            text: String,
            owner: UUID = OWNER,
        ) = service.save(owner, conversionId, ReviewedBody(text))

        /** 변환 한 건을 심는다. 암호문은 [cipher] 를 거친다. */
        fun seedDone(
            status: ConversionStatus = ConversionStatus.DONE,
            draft: String? = null,
            maskedTable: String? = null,
            keyVersion: Int = cipher.writeKeyVersion,
        ): UUID {
            val conversionId = UUID.randomUUID()

            fun seal(value: String?) = value?.let { cipher.encryptAs(PlainBody(it), keyVersion) }

            val ciphertexts =
                ConversionCiphertexts(
                    seal(draft),
                    seal(maskedTable),
                    null,
                )
            conversions.lockedForReview[OWNER to conversionId] =
                LockedConversion(
                    status = status,
                    envelope = ConversionEnvelope(conversionId, cipher.writeScheme, keyVersion, ciphertexts),
                )
            conversions.owned[OWNER to conversionId] =
                StoredConversion(
                    id = conversionId,
                    documentId = UUID.randomUUID(),
                    status = status,
                    sourceFormat = SourceFormat.TEXT,
                    // 조회가 읽는 값이 아니다 — 대역이 `document_originals` 에서 다시 센다.
                    hasStoredOriginal = false,
                    ciphertexts = ciphertexts,
                    // **아직 검수가 없으므로 `null` 이다.** `reviewed_at` 을 쓰는 문장은 검수
                    // 저장 하나뿐이고(`JdbcConversionRepository.SAVE_REVIEW_SQL`) 그 문장은
                    // `edited_text_encrypted` 를 언제나 함께 쓴다 — 「검수 시각은 있는데
                    // 검수본이 없는」 행은 실물에 존재할 수 없다. 시각은 저장이 찍는다.
                    reviewedAt = null,
                    feedbackSubmittedAt = null,
                    missingPlaceholders = emptyList(),
                    model = null,
                    providerName = null,
                    inputTokens = null,
                    outputTokens = null,
                    failureCode = null,
                )
            return conversionId
        }

        /**
         * 이미 심은 행의 **출처**만 갈아 끼운다 — 「행이 있다」와 「바이트가 열린다」를 함께 심는다.
         *
         * [seedDone] 의 인자로 받지 않는 것은 `ConversionQueryServiceTest` 의 `seedOrigin` 과 같은
         * 판단이다: 출처는 결과 열과 함께 다니는 값이 아니라 **문서 쪽 사실**이고, 인자를 늘리면
         * 시드 함수가 detekt `LongParameterList` 문턱에 닿는다.
         *
         * 「원본이 있다」를 변환 행에 따로 적지 않는다 — 대역이 `document_originals` 에서 다시
         * 세므로 여기서 심는 행 하나가 그 사실의 유일한 출처다. 붙여넣기 형식으로 부르면
         * 대역이 끊는다(실물에서 그 조합을 만드는 경로가 없다).
         */
        fun seedStoredOriginal(
            conversionId: UUID,
            sourceFormat: SourceFormat,
        ) {
            val key = OWNER to conversionId
            val stored = conversions.owned.getValue(key)
            conversions.owned[key] = stored.copy(sourceFormat = sourceFormat)
            originals.insert(
                OWNER,
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

        /** 그 열에 실제로 쓴 **평문** — 대역이라도 봉인을 거쳐 되읽는다. */
        fun savedPlaintext(field: EncryptedField): String? {
            val call = conversions.savedReviews.single()
            val column =
                when (field) {
                    EncryptedField.CONVERSION_EASY_TEXT -> call.updated.ciphertexts.easyText
                    EncryptedField.CONVERSION_MASKED_ITEMS -> call.updated.ciphertexts.maskedItems
                    EncryptedField.CONVERSION_EDITED_TEXT -> call.updated.ciphertexts.editedText
                    EncryptedField.DOCUMENT_SOURCE_TEXT -> error("검수 저장이 원문 열을 쓰지 않는다")
                    EncryptedField.DOCUMENT_ORIGINAL_BYTES -> error("검수 저장이 원본 파일 열을 쓰지 않는다")
                    EncryptedField.CONVERSION_FEEDBACK_COMMENT -> error("검수 저장이 피드백 열을 쓰지 않는다")
                }
            return column?.let { cipher.decrypt(it, call.expected.conversionId, field).value }
        }
    }
}

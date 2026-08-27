package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
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
import java.time.Instant
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

        world.save(conversionId, VALID)

        val call = world.conversions.savedReviews.single()
        assertThat(call.expected)
            .withFailMessage("조건으로 넘긴 것이 잠그고 읽은 행이 아니다 — 다른 값을 조건으로 고를 자유가 남아 있다")
            .isSameAs(
                world.conversions.lockedForReview
                    .getValue(OWNER to conversionId)
                    .envelope,
            )
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
    }

    /** 케이스마다 새로 만드는 대역 묶음 — 대역이 상태를 든다. */
    private class World(writeKeyVersion: Int = 1) {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = writeKeyVersion, transaction = transaction)
        val conversions = FakeConversionRepository(transaction)

        val service =
            ConversionReviewService(
                conversions = conversions,
                cipher = cipher,
                query =
                    ConversionQueryService(
                        conversions = conversions,
                        cipher = cipher,
                        maskedItems = FakeMaskedItemReader(),
                        original =
                            OriginalReflection(
                                StoredOriginalReader(FakeDocumentOriginalRepository(transaction), cipher),
                                FakeOriginalStructureReflector(),
                            ),
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
                    hasStoredOriginal = false,
                    ciphertexts = ciphertexts,
                    reviewedAt = Instant.EPOCH,
                    missingPlaceholders = emptyList(),
                    model = null,
                    providerName = null,
                    inputTokens = null,
                    outputTokens = null,
                    failureCode = null,
                )
            return conversionId
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

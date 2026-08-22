package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** 변환 조회 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다. */
class ConversionQueryServiceTest {
    @Test
    @DisplayName("소유자를 **저장소 인자로** 넘긴다 — 읽고 나서 비교하는 형태가 아니다")
    fun `조회가 소유자를 질의에 넘긴다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, easyText = "쉬운 글 초안")

        world.service.read(OWNER, conversionId)

        assertThat(world.conversions.reads).containsExactly(OWNER to conversionId)
    }

    @Test
    @DisplayName("남의 변환과 없는 변환이 **같은 404·같은 문구**다 — 두 갈래를 구분하지 않는다")
    fun `남의 변환은 404 다`() {
        val world = World()
        val theirs = UUID.randomUUID()
        world.seedDone(theirs, easyText = "쉬운 글 초안", owner = STRANGER)

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
        world.seedDone(
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
    @DisplayName("읽기는 트랜잭션 **안**, 복호화는 **밖**이다 — 커넥션을 쥔 채 열지 않는다")
    fun `복호화가 트랜잭션 밖이다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, easyText = "쉬운 글 초안")

        world.service.read(OWNER, conversionId)

        assertThat(world.conversions.depthWhenRead).containsExactly(1)
        assertThat(world.cipher.depthWhenDecrypted).describedAs("복호화가 경계 안에서 돌았다").containsOnly(0)
    }

    @Test
    @DisplayName("조회 결과의 `toString` 이 **본문도 가린 값도 담지 않는다** — 개수까지다")
    fun `조회 결과의 toString 이 본문을 담지 않는다`() {
        val world = World()
        val conversionId = UUID.randomUUID()
        world.seedDone(conversionId, easyText = "민감한 초안 본문", maskedLabels = listOf("[[카드번호1]]"))

        val rendered = world.service.read(OWNER, conversionId).toString()

        assertThat(rendered).doesNotContain("민감한 초안 본문").doesNotContain("가린값")
        assertThat(rendered).contains(conversionId.toString()).contains("masked=1")
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val STRANGER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
    }

    /** 한 케이스가 쓰는 대역 묶음. 케이스마다 새로 만든다 — 대역이 상태를 들고 있다. */
    private class World {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        val conversions = FakeConversionRepository(transaction)
        val maskedItems = FakeMaskedItemReader()

        val service =
            ConversionQueryService(
                conversions = conversions,
                cipher = cipher,
                maskedItems = maskedItems,
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

        /**
         * 완료 변환 한 건. 암호문은 [cipher] 를 거쳐 만든다 — 대역이라도 평문을 컬럼 자리에
         * 그대로 두면 「복호화가 실제로 돌았는가」를 잴 수 없다.
         */
        fun seedDone(
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
                    reviewedAt = null,
                    missingPlaceholders = emptyList(),
                    model = "claude-test",
                    providerName = "anthropic",
                    inputTokens = 11,
                    outputTokens = 22,
                    failureCode = null,
                )
        }
    }
}

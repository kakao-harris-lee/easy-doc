package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.pilot.MinutesSpent
import kr.easydoc.core.pilot.PublishIntent
import kr.easydoc.core.pilot.QualityScore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 파일럿 피드백 저장 유스케이스 — Spring·DB 없이 대역으로 돈다. 실물 upsert SQL 은 별도
 * 테스트가 잰다.
 */
class ConversionFeedbackServiceTest {
    @Test
    @DisplayName("없거나 남의 변환은 404 다 — **두 경우를 구분하지 않는다**")
    fun `없는 것과 남의 것이 같은 404 다`() {
        val world = World()
        val mine = world.seedDone()

        assertThatThrownBy { world.save(UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)
        assertThatThrownBy { world.save(mine, owner = STRANGER) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(CONVERSION_NOT_FOUND_MESSAGE)
        assertThat(world.feedback.upserts)
            .withFailMessage("거절됐는데 저장이 일어났다")
            .isEmpty()
    }

    @Test
    @DisplayName("완료 전 상태 **전부**가 409 다 — 결과를 보지 않은 채 품질을 답할 수 없다")
    fun `완료 전 상태는 전부 409 다`() {
        val beforeDone = ConversionStatus.entries - ConversionStatus.DONE
        assertThat(beforeDone).isNotEmpty()

        beforeDone.forEach { status ->
            val world = World()
            val conversionId = world.seedDone(status = status)

            assertThatThrownBy { world.save(conversionId) }
                .describedAs("상태 %s", status)
                .isInstanceOf(ConflictException::class.java)
                .hasMessage(CONVERSION_NOT_DONE_MESSAGE)
            assertThat(world.feedback.upserts).isEmpty()
        }
    }

    @Test
    @DisplayName("판정 순서 — **범위 밖 값이 소유권보다 앞이다**: 남의 식별자에도 404 가 아니라 422 가 나간다")
    fun `값 판정이 소유권보다 앞선다`() {
        val world = World()

        assertThatThrownBy { world.save(UUID.randomUUID(), score = QualityScore.RANGE.last + 1) }
            .withFailMessage("존재 여부로 응답이 갈리면 남의 식별자를 두드려 자원의 존재를 물을 수 있다")
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(QualityScore.OUT_OF_RANGE_MESSAGE)
        assertThatThrownBy { world.save(UUID.randomUUID(), minutes = MinutesSpent.RANGE.last + 1) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(MinutesSpent.OUT_OF_RANGE_MESSAGE)
        assertThatThrownBy { world.save(UUID.randomUUID(), intent = "그럭저럭") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PublishIntent.UNKNOWN_INTENT_MESSAGE)

        assertThat(world.conversions.reads)
            .withFailMessage("입력이 거절됐는데 자원을 조회했다 — 판정 순서가 뒤집혔고 거절 비용이 존재에 좌우된다")
            .isEmpty()
    }

    @Test
    @DisplayName("저장된 값 그대로를 돌려주고 `user_id` 는 제출자다")
    fun `저장한 값을 그대로 돌려준다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)

        val view = world.save(conversionId, intent = "with_edits", score = 4, minutes = 25, comment = COMMENT)

        assertThat(view.conversionId).isEqualTo(conversionId)
        assertThat(view.publishIntent).isEqualTo(PublishIntent.WITH_EDITS)
        assertThat(view.qualityScore).isEqualTo(QualityScore(4))
        assertThat(view.minutesSpent).isEqualTo(MinutesSpent(25))
        assertThat(view.comment).isEqualTo(PlainBody(COMMENT))
        assertThat(view.submittedAt).isNotNull()
        assertThat(world.feedback.owners.getValue(conversionId)).isEqualTo(OWNER)
        assertThat(world.feedback.depthWhenUpserted)
            .withFailMessage("저장이 트랜잭션 밖에서 돌았다")
            .containsExactly(1)
    }

    @Test
    @DisplayName("재제출은 **덮어쓴다** — 행이 하나로 남고 나중 값이 이긴다")
    fun `재제출이 멱등 upsert 다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)

        val first = world.save(conversionId, intent = "not_usable", score = 1, minutes = 5)
        val second = world.save(conversionId, intent = "as_is", score = 5, minutes = 40)

        assertThat(world.feedback.upserts).hasSize(2)
        assertThat(world.feedback.rows)
            .withFailMessage("한 변환에 두 행이 쌓이면 게이트 ① 판정의 분모(10건)가 조용히 부푼다")
            .hasSize(1)
        val row = world.feedback.rows.getValue(conversionId)
        assertThat(row.publishIntent).isEqualTo(PublishIntent.AS_IS)
        assertThat(row.qualityScore).isEqualTo(QualityScore(5))
        assertThat(row.minutesSpent).isEqualTo(MinutesSpent(40))
        assertThat(second.submittedAt)
            .withFailMessage("덮어쓴 시각이 밀리지 않았다 — `submitted_at` 은 **마지막으로 저장한** 시각이다")
            .isAfter(first.submittedAt)
    }

    @Test
    @DisplayName("검수본이 없으면 편집 거리·검수본 글자 수가 **`null` 이다** — 「수정률 0%」가 아니다")
    fun `검수본이 없으면 두 지표가 null 이다`() {
        val world = World()
        val conversionId = world.seedDone(draft = "가나다")

        world.save(conversionId)

        val row = world.feedback.rows.getValue(conversionId)
        assertThat(row.easyCharCount).isEqualTo(3)
        assertThat(row.editedCharCount)
            .withFailMessage("검수본이 없는데 글자 수가 채워졌다 — 집계가 「하나도 고치지 않았다」로 읽는다")
            .isNull()
        assertThat(row.editDistance).isNull()
    }

    @Test
    @DisplayName("검수본이 있으면 편집 거리가 **실제 거리**다 — 코드 포인트 단위다")
    fun `검수본이 있으면 편집 거리를 잰다`() {
        val world = World()
        val conversionId = world.seedDone(draft = "가나다", edited = "가라다라")

        world.save(conversionId)

        val row = world.feedback.rows.getValue(conversionId)
        assertThat(row.easyCharCount).isEqualTo(3)
        assertThat(row.editedCharCount).isEqualTo(4)
        // 「나」→「라」 치환 하나 + 「라」 삽입 하나.
        assertThat(row.editDistance).isEqualTo(2)
    }

    @Test
    @DisplayName("초안이 없으면 **세 지표가 함께 비워진다** — 분모 없는 분자를 남기지 않는다")
    fun `초안이 없으면 세 지표가 함께 null 이다`() {
        val world = World()
        val conversionId = world.seedDone(draft = null, edited = "검수본만 있다")

        world.save(conversionId)

        val row = world.feedback.rows.getValue(conversionId)
        assertThat(listOf(row.easyCharCount, row.editedCharCount, row.editDistance))
            .withFailMessage("초안이 없는데 지표가 남았다 — 무엇에 견준 값인지 말할 수 없는 숫자다")
            .containsOnlyNulls()
    }

    @Test
    @DisplayName("의견이 공백·제어문자뿐이면 **암호문이 `null` 이다** — 봉투 세 열이 함께 비어야 한다")
    fun `제어문자뿐인 의견은 없음으로 접힌다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)

        val view = world.save(conversionId, comment = " $CONTROL\t ")

        assertThat(view.comment).isNull()
        assertThat(
            world.feedback.rows
                .getValue(conversionId)
                .comment,
        ).withFailMessage("빈 의견에 암호문이 생기면 스키마의 「셋이 함께 있거나 함께 없다」와 어긋난다")
            .isNull()
        assertThat(world.cipher.sealed)
            .withFailMessage("접힌 의견을 봉인했다")
            .isEmpty()
    }

    @Test
    @DisplayName("의견을 주지 않으면 암호문이 `null` 이다")
    fun `의견이 없으면 암호문도 없다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)

        val view = world.save(conversionId, comment = null)

        assertThat(view.comment).isNull()
        assertThat(
            world.feedback.rows
                .getValue(conversionId)
                .comment,
        ).isNull()
    }

    @Test
    @DisplayName("의견 길이는 **정규화 후** 코드 포인트다 — 경계 양쪽을 함께 고정한다")
    fun `의견 길이 판정이 정규화 후 코드 포인트다`() {
        val world = World()

        // 상한 + 제어문자 잡음. 걷어내면 상한이라 통과한다.
        val padded = "가".repeat(MAX_FEEDBACK_COMMENT_LENGTH) + CONTROL.repeat(NOISE)
        world.save(world.seedDone(draft = DRAFT), comment = padded)

        assertThatThrownBy {
            world.save(world.seedDone(draft = DRAFT), comment = "가".repeat(MAX_FEEDBACK_COMMENT_LENGTH + 1))
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(FEEDBACK_COMMENT_TOO_LONG_MESSAGE)
    }

    @Test
    @DisplayName("**BMP 밖 문자**로 잰다 — 코드 단위로 세면 상한 이내인 의견이 거절된다")
    fun `코드 포인트와 코드 단위가 갈리는 자리`() {
        val world = World()
        val atLimit = SUPPLEMENTARY.repeat(MAX_FEEDBACK_COMMENT_LENGTH)
        assertThat(atLimit.length).isEqualTo(MAX_FEEDBACK_COMMENT_LENGTH * 2)

        world.save(world.seedDone(draft = DRAFT), comment = atLimit)

        assertThatThrownBy {
            world.save(world.seedDone(draft = DRAFT), comment = SUPPLEMENTARY.repeat(MAX_FEEDBACK_COMMENT_LENGTH + 1))
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(FEEDBACK_COMMENT_TOO_LONG_MESSAGE)
    }

    @Test
    @DisplayName("의견 길이 판정도 **소유권보다 앞이다**")
    fun `의견 길이 판정이 소유권보다 앞선다`() {
        val world = World()

        assertThatThrownBy { world.save(UUID.randomUUID(), comment = "가".repeat(MAX_FEEDBACK_COMMENT_LENGTH + 1)) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(FEEDBACK_COMMENT_TOO_LONG_MESSAGE)
        assertThat(world.conversions.reads).isEmpty()
    }

    @Test
    @DisplayName("저장 정의역은 **길이와 다른 축**이다 — 짝 없는 서로게이트는 길이와 무관하게 422 다")
    fun `저장할 수 없는 문자는 거절된다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)

        assertThatThrownBy { world.save(conversionId, comment = "의견\uD800입니다") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PlainBody.UNPAIRED_SURROGATE_MESSAGE)
        assertThat(world.feedback.upserts).isEmpty()
    }

    @Test
    @DisplayName("봉인된 의견이 **`conversion_feedback.comment_encrypted` 에 결속된다**")
    fun `의견이 피드백 열에 결속된다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)

        world.save(conversionId, comment = COMMENT)

        assertThat(world.cipher.sealed)
            .withFailMessage("결속 인자가 갈리면 실물에서 태그 검증이 실패하거나, 다른 열의 암호문을 옮겨 심을 수 있다")
            .containsExactly(Triple(COMMENT, conversionId, EncryptedField.CONVERSION_FEEDBACK_COMMENT))
        val stored =
            world.feedback.rows
                .getValue(conversionId)
                .comment
        assertThat(stored?.scheme).isEqualTo(world.cipher.writeScheme)
        assertThat(stored?.keyVersion).isEqualTo(world.cipher.writeKeyVersion)
    }

    @Test
    @DisplayName("저장 값과 응답의 `toString` 이 의견·점수를 담지 않는다")
    fun `toString 이 의견과 점수를 담지 않는다`() {
        val world = World()
        val conversionId = world.seedDone(draft = DRAFT)

        val view = world.save(conversionId, score = 2, minutes = 123, comment = COMMENT)

        listOf(
            view.toString(),
            world.feedback.rows
                .getValue(conversionId)
                .toString(),
        ).forEach { rendered ->
            assertThat(rendered).doesNotContain(COMMENT).doesNotContain("123")
        }
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c1")
        val STRANGER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c2")

        const val DRAFT = "쉬운 글 초안입니다."
        const val COMMENT = "○○동 안내 문장이 조금 어색합니다."
        const val NOISE = 5

        /** BMP 밖 문자(코드 단위 2 · 코드 포인트 1). 서로게이트 **쌍**이라 정의역은 통과한다. */
        const val SUPPLEMENTARY = "\uD83D\uDE00"

        /** 제어문자 하나. XML 1.0 이 받지 못하는 것으로 고른다 — 탭·개행은 걸러지지 않는다. */
        const val CONTROL = "\u0001"
    }

    /** 케이스마다 새로 만드는 대역 묶음 — 대역이 상태를 든다. */
    private class World {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        val conversions = FakeConversionRepository(transaction)
        val feedback = FakeConversionFeedbackRepository(transaction)

        val service =
            ConversionFeedbackService(
                feedback = feedback,
                cipher = cipher,
                query =
                    ConversionQueryService(
                        conversions = conversions,
                        cipher = cipher,
                        maskedItems = FakeMaskedItemReader(),
                        transaction = transaction,
                    ),
                transaction = transaction,
            )

        @Suppress("LongParameterList")
        fun save(
            conversionId: UUID,
            intent: String = "as_is",
            score: Int = 4,
            minutes: Int = 30,
            comment: String? = null,
            owner: UUID = OWNER,
        ): ConversionFeedbackView =
            service.save(
                owner,
                conversionId,
                FeedbackSubmission(
                    publishIntent = intent,
                    qualityScore = score,
                    minutesSpent = minutes,
                    comment = comment,
                ),
            )

        /** 변환 한 건을 심는다. 암호문은 [cipher] 를 거친다. */
        fun seedDone(
            status: ConversionStatus = ConversionStatus.DONE,
            draft: String? = null,
            edited: String? = null,
        ): UUID {
            val conversionId = UUID.randomUUID()

            fun seal(value: String?) = value?.let { cipher.encryptAs(PlainBody(it), cipher.writeKeyVersion) }

            conversions.owned[OWNER to conversionId] =
                StoredConversion(
                    id = conversionId,
                    documentId = UUID.randomUUID(),
                    status = status,
                    ciphertexts = ConversionCiphertexts(seal(draft), null, seal(edited)),
                    reviewedAt = edited?.let { Instant.EPOCH },
                    missingPlaceholders = emptyList(),
                    model = null,
                    providerName = null,
                    inputTokens = null,
                    outputTokens = null,
                    failureCode = null,
                )
            return conversionId
        }
    }
}

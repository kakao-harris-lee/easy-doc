package kr.easydoc.application.illustration.suggestion

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSet
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionStorageCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** 읽는 시점에 stale 을 판정한다(명세 §2·§6). 서버가 저장된 결과를 고치지 않는다는 뜻이다. */
class IllustrationSuggestionResultServiceTest {
    private val jobs = FakeSuggestionJobs()
    private val results = RecordingSuggestionResults()
    private val cipher = PassThroughCipher()

    private fun service(
        enabled: Boolean = true,
        rate: BigDecimal? = BigDecimal("0.1"),
    ) = IllustrationSuggestionResultService(enabled, rate, jobs, results, cipher, DirectSuggestionTransaction())

    private fun storeResult(
        set: IllustrationSuggestionSet,
        basedOn: Long,
    ) {
        val id = UUID.randomUUID()
        results.latest =
            StoredIllustrationSuggestionResult(
                id,
                SUGGESTION_JOB,
                SUGGESTION_CONVERSION,
                basedOn,
                EncryptedContent(
                    IllustrationSuggestionStorageCodec.encode(set).toByteArray(),
                    cipher.writeScheme,
                    cipher.writeKeyVersion,
                ),
            )
    }

    @Test
    @DisplayName("결과가 없으면 not_analyzed 이고 제안은 빈 목록이다")
    fun `결과가 없으면 not_analyzed 다`() {
        val view = service().get(SUGGESTION_OWNER, SUGGESTION_CONVERSION)

        assertThat(view.status).isEqualTo(IllustrationSuggestionResultStatus.NOT_ANALYZED)
        assertThat(view.basedOnContentRevision).isNull()
        assertThat(view.suggestions).isEmpty()
        assertThat(view.droppedCount).isZero()
        assertThat(view.contentRevision).isEqualTo(3)
    }

    @Test
    @DisplayName("현재 본문 기준 결과에 제안이 있으면 ready 다")
    fun `현재 본문 결과는 ready 다`() {
        storeResult(suggestionSet(count = 1), basedOn = 3)

        val view = service().get(SUGGESTION_OWNER, SUGGESTION_CONVERSION)

        assertThat(view.status).isEqualTo(IllustrationSuggestionResultStatus.READY)
        assertThat(view.suggestions).hasSize(1)
        assertThat(view.basedOnContentRevision).isEqualTo(3)
    }

    @Test
    @DisplayName("제안 0건은 no_suggestions 다 — 분석 실패가 아니다")
    fun `제안 0건은 no_suggestions 다`() {
        storeResult(suggestionSet(count = 0), basedOn = 3)

        assertThat(service().get(SUGGESTION_OWNER, SUGGESTION_CONVERSION).status)
            .isEqualTo(IllustrationSuggestionResultStatus.NO_SUGGESTIONS)
    }

    @Test
    @DisplayName("본문이 그 뒤 바뀌었으면 stale 이고 제안은 그대로 돌려준다")
    fun `본문이 바뀌면 stale 이다`() {
        storeResult(suggestionSet(count = 1), basedOn = 2)

        val view = service().get(SUGGESTION_OWNER, SUGGESTION_CONVERSION)

        assertThat(view.status).isEqualTo(IllustrationSuggestionResultStatus.STALE)
        assertThat(view.basedOnContentRevision).isEqualTo(2)
        assertThat(view.contentRevision).isEqualTo(3)
        assertThat(view.suggestions).hasSize(1)
    }

    @Test
    @DisplayName("버린 제안 수는 결과와 함께 나온다 — 내용은 저장하지 않는다")
    fun `버린 제안 수를 낸다`() {
        storeResult(suggestionSet(count = 1).copy(droppedCount = 4), basedOn = 3)

        assertThat(service().get(SUGGESTION_OWNER, SUGGESTION_CONVERSION).droppedCount).isEqualTo(4)
    }

    @Test
    @DisplayName("이용량 단가가 없으면 required_credits 는 null 이다 — 0과 구분된다")
    fun `단가 미설정이면 required_credits 가 null 이다`() {
        assertThat(service(rate = null).get(SUGGESTION_OWNER, SUGGESTION_CONVERSION).requiredCredits).isNull()
        assertThat(service(rate = BigDecimal.ZERO).get(SUGGESTION_OWNER, SUGGESTION_CONVERSION).requiredCredits)
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("기능이 꺼져 있거나 남의 변환이면 404다")
    fun `꺼져 있거나 남의 것이면 404다`() {
        assertThatThrownBy { service(enabled = false).get(SUGGESTION_OWNER, SUGGESTION_CONVERSION) }
            .isInstanceOf(NotFoundException::class.java)

        jobs.context = null
        assertThatThrownBy { service().get(SUGGESTION_OWNER, SUGGESTION_CONVERSION) }
            .isInstanceOf(NotFoundException::class.java)
    }
}

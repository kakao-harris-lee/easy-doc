package kr.easydoc.core.pilot

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.StorageException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 파일럿 게이트 ① 피드백의 도메인 타입. 범위 상수의 정본이 `ConversionFeedback.kt` 이므로
 * 이 테스트도 리터럴을 다시 적지 않고 그 상수를 통해 경계를 잰다 — 상수를 고치면 경계
 * 케이스가 함께 움직여야 하고, 여기에 숫자를 박으면 그 연동이 끊긴다.
 */
class ConversionFeedbackTest {
    @Test
    @DisplayName("`PublishIntent` 의 wireName 이 계약이 적은 세 값이다")
    fun `배포 의향의 와이어 이름이 계약과 같다`() {
        assertThat(PublishIntent.entries.map { it.wireName })
            .containsExactly("as_is", "with_edits", "not_usable")
    }

    @Test
    @DisplayName("저장된 컬럼 값을 되읽으면 같은 항목으로 돌아온다")
    fun `배포 의향이 왕복한다`() {
        PublishIntent.entries.forEach { intent ->
            assertThat(PublishIntent.ofWireName(intent.wireName)).isEqualTo(intent)
        }
    }

    @Test
    @DisplayName("컬럼에 모르는 값이 들어 있으면 저장 계층 오류다 — 사용자 입력 문제가 아니다")
    fun `모르는 컬럼 값은 StorageException 이다`() {
        assertThatThrownBy { PublishIntent.ofWireName("maybe") }
            .isInstanceOf(StorageException::class.java)
    }

    @Test
    @DisplayName("요청 본문의 모르는 값은 사용자 오류이고, 거부 문구가 입력값을 되비추지 않는다")
    fun `모르는 요청 값은 InvalidInputException 이다`() {
        val probe = "publish-intent-probe"

        assertThatThrownBy { PublishIntent.ofRequestValue(probe) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PublishIntent.UNKNOWN_INTENT_MESSAGE)
            .hasMessageNotContaining(probe)
    }

    @Test
    @DisplayName("요청에 값이 아예 없어도 같은 갈래로 거절한다 — 필수 항목이다")
    fun `빈 요청 값도 거절한다`() {
        assertThatThrownBy { PublishIntent.ofRequestValue(null) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PublishIntent.UNKNOWN_INTENT_MESSAGE)
    }

    @Test
    @DisplayName("품질 만족도 경계값(1·5)은 통과한다")
    fun `품질 만족도 경계값이 통과한다`() {
        assertThat(QualityScore(QualityScore.RANGE.first).value).isEqualTo(QualityScore.RANGE.first)
        assertThat(QualityScore(QualityScore.RANGE.last).value).isEqualTo(QualityScore.RANGE.last)
    }

    @Test
    @DisplayName("품질 만족도 범위가 계약이 적은 1~5 다")
    fun `품질 만족도 범위가 계약과 같다`() {
        assertThat(QualityScore.RANGE).isEqualTo(1..5)
    }

    @ParameterizedTest(name = "품질 만족도 {0} 은 거절한다")
    @ValueSource(ints = [0, 6, -1, 100])
    fun `범위 밖 품질 만족도를 거절한다`(score: Int) {
        assertThatThrownBy { QualityScore(score) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(QualityScore.OUT_OF_RANGE_MESSAGE)
    }

    @Test
    @DisplayName("품질 만족도 거부 문구에 입력값이 없다 — 문구가 응답 detail 로 그대로 나간다")
    fun `품질 만족도 거부 문구가 입력을 되비추지 않는다`() {
        val probe = 4242

        assertThatThrownBy { QualityScore(probe) }
            .hasMessageNotContaining(probe.toString())
    }

    @Test
    @DisplayName("소요 시간 경계값(0·600)은 통과한다")
    fun `소요 시간 경계값이 통과한다`() {
        assertThat(MinutesSpent(MinutesSpent.RANGE.first).value).isEqualTo(MinutesSpent.RANGE.first)
        assertThat(MinutesSpent(MinutesSpent.RANGE.last).value).isEqualTo(MinutesSpent.RANGE.last)
    }

    @Test
    @DisplayName("소요 시간 범위가 계약이 적은 0~600 이다")
    fun `소요 시간 범위가 계약과 같다`() {
        assertThat(MinutesSpent.RANGE).isEqualTo(0..600)
    }

    @ParameterizedTest(name = "소요 시간 {0} 분은 거절한다")
    @ValueSource(ints = [-1, 601, -600, 1_440])
    fun `범위 밖 소요 시간을 거절한다`(minutes: Int) {
        assertThatThrownBy { MinutesSpent(minutes) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(MinutesSpent.OUT_OF_RANGE_MESSAGE)
    }

    @Test
    @DisplayName("소요 시간 거부 문구에 입력값이 없다")
    fun `소요 시간 거부 문구가 입력을 되비추지 않는다`() {
        val probe = 9_999

        assertThatThrownBy { MinutesSpent(probe) }
            .hasMessageNotContaining(probe.toString())
    }

    @Test
    @DisplayName("`EditDistanceSkipReason` 의 wireName 이 V4 CHECK 가 적은 두 값이다")
    fun `편집 거리 건너뜀 사유의 와이어 이름이 스키마와 같다`() {
        assertThat(EditDistanceSkipReason.entries.map { it.wireName })
            .containsExactly("no_review", "budget_exceeded")
    }

    @Test
    @DisplayName("저장된 컬럼 값을 되읽으면 같은 항목으로 돌아온다")
    fun `편집 거리 건너뜀 사유가 왕복한다`() {
        EditDistanceSkipReason.entries.forEach { reason ->
            assertThat(EditDistanceSkipReason.ofWireName(reason.wireName)).isEqualTo(reason)
        }
    }

    @Test
    @DisplayName("컬럼에 모르는 값이 들어 있으면 저장 계층 오류다")
    fun `모르는 건너뜀 사유는 StorageException 이다`() {
        assertThatThrownBy { EditDistanceSkipReason.ofWireName("maybe") }
            .isInstanceOf(StorageException::class.java)
    }
}

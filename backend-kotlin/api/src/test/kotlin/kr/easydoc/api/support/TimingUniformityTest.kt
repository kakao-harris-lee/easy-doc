package kr.easydoc.api.support

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** [TimingUniformity] 단위 테스트 — DB·Spring 없이 순수 로직만 스텁으로 재현한다. */
class TimingUniformityTest {
    @Test
    @DisplayName("표본 수만큼 교차 호출하고 각 팔의 첫 호출(워밍업)을 버린 중앙값을 낸다")
    fun `교차 호출과 워밍업 버림이 맞다`() {
        val calls = mutableListOf<String>()
        val queues =
            mapOf(
                ARM_A to ArrayDeque(listOf(100.0, 10.0, 20.0, 30.0)),
                ARM_B to ArrayDeque(listOf(200.0, 40.0, 50.0, 60.0)),
            )

        val medians =
            TimingUniformity.interleavedMedians(listOf(ARM_A, ARM_B), samplesPerArm = 3, seed = SEED) { arm ->
                calls += arm
                queues.getValue(arm).removeFirst()
            }

        assertThat(calls).hasSize(8)
        assertThat(calls.count { it == ARM_A }).isEqualTo(4)
        assertThat(calls.count { it == ARM_B }).isEqualTo(4)
        assertThat(medians[ARM_A]).describedAs("워밍업 100 을 버리면 [10,20,30] 의 중앙값은 20").isEqualTo(20.0)
        assertThat(medians[ARM_B]).describedAs("워밍업 200 을 버리면 [40,50,60] 의 중앙값은 50").isEqualTo(50.0)
    }

    @Test
    @DisplayName("같은 시드는 같은 호출 순서를 내고, 그 순서는 한 팔로 몰려 있지 않다")
    fun `시드가 순서를 고정하고 두 팔이 섞인다`() {
        fun sequenceFor(seed: Long): List<String> {
            val calls = mutableListOf<String>()
            TimingUniformity.interleavedMedians(listOf(ARM_A, ARM_B), samplesPerArm = 3, seed = seed) { arm ->
                calls += arm
                0.0
            }
            return calls
        }

        val first = sequenceFor(SEED)
        val second = sequenceFor(SEED)

        assertThat(first).describedAs("같은 시드인데 호출 순서가 다르다 — 재현이 안 된다").isEqualTo(second)
        assertThat(first.distinct()).containsExactlyInAnyOrder(ARM_A, ARM_B)
        val transitions = first.zipWithNext().count { (a, b) -> a != b }
        assertThat(transitions)
            .describedAs("전환이 1번뿐이다 — 한 팔이 먼저 몰리고 그 다음에 다른 팔이 온다: %s", first)
            .isGreaterThan(1)
    }

    @Test
    @DisplayName("표본 수가 짝수면 거부한다 — 중앙값이 표본 하나로 정해지지 않는다")
    fun `표본 수는 홀수여야 한다`() {
        assertThatThrownBy {
            TimingUniformity.interleavedMedians(listOf(ARM_A, ARM_B), samplesPerArm = 4, seed = SEED) { 0.0 }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("최소 중앙값이 분해능 바닥 아래면 비가 실측 대신 바닥을 쓴다")
    fun `비가 바닥을 쓴다`() {
        val ratio = TimingUniformity.ratio(mapOf(ARM_A to 0.01, ARM_B to 1.0), minMeasurableMillis = 0.05)

        assertThat(ratio).isCloseTo(1.0 / 0.05, within(0.0001))
    }

    @Test
    @DisplayName("1차가 문턱 아래면 확인 표본을 아예 재지 않는다")
    fun `1차가 문턱 아래면 확인을 재지 않는다`() {
        var confirmationCalls = 0

        val outcome =
            TimingUniformity.measureWithConfirmation(
                threshold = THRESHOLD,
                minMeasurableMillis = FLOOR,
                firstPass = { mapOf(ARM_A to 1.0, ARM_B to 1.1) },
                confirmationPass = {
                    confirmationCalls++
                    mapOf(ARM_A to 1.0, ARM_B to 1.0)
                },
            )

        assertThat(confirmationCalls).describedAs("1차만으로 충분한데 확인 표본을 재비용을 썼다").isZero()
        assertThat(outcome.confirmation).isNull()
        assertThat(outcome.ratio).isCloseTo(1.1, within(0.0001))
    }

    @Test
    @DisplayName("1차가 문턱을 넘고 확인이 문턱 아래면 확인 결과가 최종 판정이 된다")
    fun `확인이 판정한다`() {
        var confirmationCalls = 0

        val outcome =
            TimingUniformity.measureWithConfirmation(
                threshold = THRESHOLD,
                minMeasurableMillis = FLOOR,
                firstPass = { mapOf(ARM_A to 1.0, ARM_B to 2.0) },
                confirmationPass = {
                    confirmationCalls++
                    mapOf(ARM_A to 1.0, ARM_B to 1.2)
                },
            )

        assertThat(confirmationCalls).describedAs("확인 표본이 정확히 한 번 돌아야 한다").isEqualTo(1)
        assertThat(outcome.confirmation).isNotNull()
        assertThat(outcome.ratio)
            .describedAs("확인 표본의 비가 판정이어야 하는데 1차 비(2.0)가 그대로 남았다")
            .isLessThan(THRESHOLD)
    }

    @Test
    @DisplayName("1차·확인 모두 문턱을 넘으면 최종 비도 문턱 이상이고 두 회차가 요약에 남는다")
    fun `둘 다 넘으면 최종 판정도 넘는다`() {
        val outcome =
            TimingUniformity.measureWithConfirmation(
                threshold = THRESHOLD,
                minMeasurableMillis = FLOOR,
                firstPass = { mapOf(ARM_A to 1.0, ARM_B to 2.0) },
                confirmationPass = { mapOf(ARM_A to 1.0, ARM_B to 3.0) },
            )

        assertThat(outcome.ratio).isGreaterThanOrEqualTo(THRESHOLD)
        assertThat(outcome.confirmation).isNotNull()

        val description = TimingUniformity.describe(outcome, LABEL_A to LABEL_B, FLOOR)

        assertThat(description)
            .contains("1차")
            .contains("확인")
            .contains(LABEL_A)
            .contains(LABEL_B)
    }

    @Test
    @DisplayName("확인 없이 끝났을 때는 요약에 1차만 남고 확인 표시가 없다")
    fun `확인이 없으면 요약도 1차뿐이다`() {
        val outcome =
            TimingUniformity.measureWithConfirmation(
                threshold = THRESHOLD,
                minMeasurableMillis = FLOOR,
                firstPass = { mapOf(ARM_A to 1.0, ARM_B to 1.05) },
                confirmationPass = { error("확인은 불려서는 안 된다") },
            )

        val description = TimingUniformity.describe(outcome, LABEL_A to LABEL_B, FLOOR)

        assertThat(description).contains("1차").doesNotContain("확인")
    }

    private companion object {
        const val ARM_A = "a"
        const val ARM_B = "b"
        const val LABEL_A = "없음"
        const val LABEL_B = "타인"
        const val SEED = 20260821L
        const val THRESHOLD = 1.5
        const val FLOOR = 0.05
    }
}

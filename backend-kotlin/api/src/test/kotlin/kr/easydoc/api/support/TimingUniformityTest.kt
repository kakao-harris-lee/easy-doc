package kr.easydoc.api.support

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** [TimingUniformity] 단위 테스트 — DB·Spring 없이 순수 로직만 스텁으로 재현한다. */
class TimingUniformityTest {
    @Test
    @DisplayName("표본 수만큼 교차 호출하고 각 팔의 첫 호출(워밍업)을 버린 원표본을 낸다")
    fun `교차 호출과 워밍업 버림이 맞다`() {
        val calls = mutableListOf<String>()
        val measure =
            scripted(
                ABSENT to listOf(100.0, 10.0, 20.0, 30.0),
                OTHERS to listOf(200.0, 40.0, 50.0, 60.0),
            ) { calls += it.key }

        val samples = TimingUniformity.interleavedSamples(ARMS, samplesPerArm = 3, seed = SEED, measure)

        assertThat(calls).hasSize(8)
        assertThat(calls.count { it == ABSENT.key }).isEqualTo(4)
        assertThat(calls.count { it == OTHERS.key }).isEqualTo(4)
        assertThat(samples[ABSENT.key]).describedAs("워밍업 100 만 버려야 한다").containsExactly(10.0, 20.0, 30.0)
        assertThat(samples[OTHERS.key]).describedAs("워밍업 200 만 버려야 한다").containsExactly(40.0, 50.0, 60.0)
        assertThat(TimingUniformity.median(samples.getValue(ABSENT.key))).isEqualTo(20.0)
        assertThat(TimingUniformity.median(samples.getValue(OTHERS.key))).isEqualTo(50.0)
    }

    @Test
    @DisplayName("버리는 것이 **첫** 측정이다 — 표본 하나짜리에서 마지막을 버리면 워밍업 값이 남는다")
    fun `버리는 것은 첫 측정이다`() {
        val measure =
            scripted(
                ABSENT to listOf(WARMUP_SPIKE, SETTLED),
                OTHERS to listOf(WARMUP_SPIKE, SETTLED),
            )

        val samples = TimingUniformity.interleavedSamples(ARMS, samplesPerArm = 1, seed = SEED, measure)

        assertThat(samples.getValue(ABSENT.key))
            .describedAs("마지막을 버리면 워밍업 스파이크 %s 가 표본으로 남는다", WARMUP_SPIKE)
            .containsExactly(SETTLED)
        assertThat(samples.getValue(OTHERS.key)).containsExactly(SETTLED)
    }

    @Test
    @DisplayName("같은 시드는 같은 호출 순서를 내고, 그 순서는 한 팔로 몰려 있지 않다")
    fun `시드가 순서를 고정하고 두 팔이 섞인다`() {
        fun sequenceFor(seed: Long): List<String> {
            val calls = mutableListOf<String>()
            TimingUniformity.interleavedSamples(ARMS, samplesPerArm = 3, seed = seed) { arm ->
                calls += arm.key
                0.0
            }
            return calls
        }

        val first = sequenceFor(SEED)
        val second = sequenceFor(SEED)

        assertThat(first).describedAs("같은 시드인데 호출 순서가 다르다 — 재현이 안 된다").isEqualTo(second)
        assertThat(first.distinct()).containsExactlyInAnyOrder(ABSENT.key, OTHERS.key)
        val transitions = first.zipWithNext().count { (a, b) -> a != b }
        assertThat(transitions)
            .describedAs("전환이 1번뿐이다 — 한 팔이 먼저 몰리고 그 다음에 다른 팔이 온다: %s", first)
            .isGreaterThan(1)
    }

    @Test
    @DisplayName("1차 표본 수가 짝수면 거부한다 — 확인 없이 끝날 때 중앙값이 표본 하나로 정해지지 않는다")
    fun `1차 표본 수는 홀수여야 한다`() {
        assertThatThrownBy { spec(samples = 4, confirmationSamples = 2) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("1차 표본 수")
    }

    @Test
    @DisplayName("합산 표본 수가 짝수면 거부한다 — 판정 중앙값이 표본 하나로 정해지지 않는다")
    fun `합산 표본 수도 홀수여야 한다`() {
        assertThatThrownBy { spec(samples = 3, confirmationSamples = 3) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("합산 표본 수")
    }

    @Test
    @DisplayName("최소 중앙값이 분해능 바닥 아래면 비가 실측 대신 바닥을 쓴다")
    fun `비가 바닥을 쓴다`() {
        val ratio = TimingUniformity.ratio(mapOf(ABSENT.key to 0.01, OTHERS.key to 1.0), minMeasurableMillis = FLOOR)

        assertThat(ratio).isCloseTo(1.0 / FLOOR, within(0.0001))
    }

    @Test
    @DisplayName("1차가 문턱 아래면 확인 표본도 간격도 없다")
    fun `1차가 문턱 아래면 확인을 재지 않는다`() {
        val naps = mutableListOf<Long>()
        val measure = scripted(ABSENT to listOf(0.0, 1.0), OTHERS to listOf(0.0, 1.1))

        val outcome = TimingUniformity.judge(spec(), sleep = { naps += it }, measure = measure)

        assertThat(outcome.confirmation).describedAs("1차만으로 충분한데 확인 표본을 재 비용을 썼다").isNull()
        assertThat(naps).describedAs("확인을 재지 않았는데 간격만큼 잤다").isEmpty()
        assertThat(outcome.pooled).isEqualTo(outcome.first)
        assertThat(outcome.ratio).isCloseTo(1.1, within(0.0001))
    }

    @Test
    @DisplayName("1차 비가 문턱과 **같으면** 확인을 잰다 — 경계는 확인 쪽이다")
    fun `경계값이 확인을 부른다`() {
        val naps = mutableListOf<Long>()
        val measure =
            scripted(
                ABSENT to listOf(0.0, 1.0, 0.0, 1.0, 1.0),
                OTHERS to listOf(0.0, THRESHOLD, 0.0, 1.0, 1.0),
            )

        val outcome = TimingUniformity.judge(spec(), sleep = { naps += it }, measure = measure)

        assertThat(outcome.confirmation).describedAs("1차 비가 정확히 문턱인데 확인을 건너뛰었다").isNotNull()
        assertThat(naps).describedAs("확인 전에 간격을 정확히 한 번 둬야 한다").containsExactly(GAP)
    }

    @Test
    @DisplayName("판정 비는 확인 표본만이 아니라 **1차와 확인을 합친** 표본의 중앙값이 낸다")
    fun `판정은 합산 표본이 한다`() {
        val measure =
            scripted(
                ABSENT to listOf(0.0, 10.0, 10.0, 10.0, 0.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0),
                OTHERS to listOf(0.0, 40.0, 40.0, 40.0, 0.0, 10.0, 10.0, 10.0, 10.0, 20.0, 20.0),
            )

        val outcome = TimingUniformity.judge(spec(samples = 3, confirmationSamples = 6), sleep = {}, measure = measure)

        assertThat(outcome.first).isEqualTo(mapOf(ABSENT.key to 10.0, OTHERS.key to 40.0))
        assertThat(outcome.confirmation).isEqualTo(mapOf(ABSENT.key to 10.0, OTHERS.key to 10.0))
        assertThat(outcome.pooled)
            .describedAs("합산 9 표본의 중앙값이어야 한다 — 확인 6 표본만 보면 10.0 이 된다")
            .isEqualTo(mapOf(ABSENT.key to 10.0, OTHERS.key to 20.0))
        assertThat(outcome.ratio)
            .describedAs("확인만으로 판정하면 1.0 이라 통과한다 — 합산 판정이면 2.0 으로 걸린다")
            .isCloseTo(2.0, within(0.0001))
    }

    @Test
    @DisplayName("요약 한 줄이 1차·확인·합산을 팔 이름과 짝지어 그대로 적는다")
    fun `요약이 세 회차를 모두 적는다`() {
        val measure =
            scripted(
                ABSENT to listOf(0.0, 10.0, 10.0, 10.0, 0.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0),
                OTHERS to listOf(0.0, 40.0, 40.0, 40.0, 0.0, 10.0, 10.0, 10.0, 10.0, 20.0, 20.0),
            )

        val outcome = TimingUniformity.judge(spec(samples = 3, confirmationSamples = 6), sleep = {}, measure = measure)

        assertThat(TimingUniformity.describe(outcome))
            .isEqualTo(
                "1차 없음=10.000ms 타인=40.000ms 비=4.000 → " +
                    "확인 없음=10.000ms 타인=10.000ms · 합산 없음=10.000ms 타인=20.000ms 비=2.000",
            )
    }

    @Test
    @DisplayName("요약은 팔 이름을 key 로 찾는다 — 중앙값 맵의 순서가 뒤집혀도 이름이 바뀌지 않는다")
    fun `요약이 자리로 이름을 붙이지 않는다`() {
        val reversed = linkedMapOf(OTHERS.key to 2.0, ABSENT.key to 1.0)
        val outcome =
            TimingUniformity.Outcome(
                spec = spec(),
                first = reversed,
                confirmation = null,
                pooled = reversed,
                ratio = 2.0,
            )

        assertThat(TimingUniformity.describe(outcome))
            .describedAs("맵 순서로 이름을 붙이면 없음·타인이 뒤바뀐다")
            .contains("없음=1.000ms")
            .contains("타인=2.000ms")
    }

    @Test
    @DisplayName("확인 없이 끝났을 때는 요약에 1차만 남고 확인·합산 표시가 없다")
    fun `확인이 없으면 요약도 1차뿐이다`() {
        val measure = scripted(ABSENT to listOf(0.0, 1.0), OTHERS to listOf(0.0, 1.05))

        val outcome = TimingUniformity.judge(spec(), sleep = {}, measure = measure)

        assertThat(TimingUniformity.describe(outcome)).contains("1차").doesNotContain("확인").doesNotContain("합산")
    }

    /** 팔마다 정해진 값을 차례로 돌려주는 측정 스텁. 각 회차의 첫 값이 그 팔의 워밍업이다. */
    private fun scripted(
        vararg script: Pair<TimingUniformity.Arm, List<Double>>,
        onCall: (TimingUniformity.Arm) -> Unit = {},
    ): (TimingUniformity.Arm) -> Double {
        val queues = script.associate { (arm, values) -> arm.key to ArrayDeque(values) }
        return { arm ->
            onCall(arm)
            queues.getValue(arm.key).removeFirst()
        }
    }

    private fun spec(
        samples: Int = 1,
        confirmationSamples: Int = 2,
    ) = TimingUniformity.TimingSpec(
        arms = ARMS,
        samples = samples,
        confirmationSamples = confirmationSamples,
        seed = SEED,
        confirmationSeed = SEED + 1,
        gapMillis = GAP,
        threshold = THRESHOLD,
        minMeasurableMillis = FLOOR,
    )

    private companion object {
        val ABSENT = TimingUniformity.Arm("absent", "없음")
        val OTHERS = TimingUniformity.Arm("others", "타인")
        val ARMS = listOf(ABSENT, OTHERS)

        const val SEED = 20260821L
        const val GAP = 50L
        const val THRESHOLD = 1.5
        const val FLOOR = 0.05

        /** 워밍업 자리에만 놓는 값. 표본으로 남으면 중앙값이 눈에 띄게 튀어 버림이 틀렸음을 드러낸다. */
        const val WARMUP_SPIKE = 999.0
        const val SETTLED = 7.0
    }
}

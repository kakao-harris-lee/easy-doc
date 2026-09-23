package kr.easydoc.api.support

import kotlin.random.Random

/**
 * 소유권 은닉 셋째 축(응답 시간) 판정의 표본 설계 — 교차 순서 + 워밍업 버림 + 중앙값 +
 * 합산 확인 회차.
 *
 * **이 축은 보조다.** 문서·변환의 소유 판정은 SQL 술어에 있고 그 전수는
 * `OwnershipPredicateGuardTest` 가 센다 — 다만 그 인구조사는 문서 레인이고 작업 공간 경로는
 * 그 밖이다. 작업 공간 이름 변경은 `JdbcWorkspaceRepositoryTest` 의 **구조 축**
 * —「이름 변경 요청 하나가 소유 결과와 무관하게 같은 수의 SQL 문을 낸다」— 이 지킨다(b37012c4).
 * 시간 축의 **알려진 탐지 바닥은 「배 단위」**다: 같은 커밋이 목표 변이(소유 조건을 SQL WHERE
 * 에서 빼고 읽은 뒤 Kotlin 에서 비교)를 실제로 재자 비가 1.013 · 1.090 · 1.051 로, 한 회차
 * 판정으로도 문턱을 넘지 못했다. 그 변이는 구조 축이 잡고, 여기서 잡는 것은 「일하는 양이
 * 배로 갈리는 회귀」이지 실제 정보 누출이 아니다.
 *
 * **왜 두 회차인가.** CI 러너가 붐빌 때 한 자릿수 밀리초 중앙값은 [TimingSpec.samples] 한
 * 회차로 판정하기에 시끄럽다(2026-09-23 GitHub Actions 사례 — 비 1.726 이 문턱을 한 번 넘었다가
 * 같은 커밋 재실행·로컬 3,325 건 모두 통과). 그래서 1차 비가 [TimingSpec.threshold] **이상일
 * 때만** [TimingSpec.gapMillis] 만큼 재우고 [TimingSpec.confirmationSamples] 를 더 재되, 판정은
 * **1차와 확인을 합친** 표본의 중앙값이 낸다. 확인이 1차를 덮어쓰지 않는다 — 1차의 측정은
 * 판정에 그대로 남고, 표본이 늘어 한 회차 잡음의 지분만 줄어든다.
 *
 * **무엇을 약화하는가.** 합산이 낮추는 민감도는 문턱 **바로 위**에서 1차에만 나타나고 확인에서
 * 재현되지 않는 격차, 즉 잡음성 초과에 한한다 — 그런 1차 초과는 합산에서 희석돼 통과할 수 있고,
 * 이 표본 크기에서 그 구간은 대략 비 1.5~1.7 이다. **지속하는** 격차는 그 구간에서도 살아남는다:
 * OTHERS 팔에만 3ms 를 더한 음성 대조가
 * `1차 없음=5.911ms 타인=8.932ms 비=1.511 → 확인 없음=5.599ms 타인=8.578ms ·
 * 합산 없음=5.670ms 타인=8.659ms 비=1.527` 로, 1차가 겨우 문턱을 넘긴 크기의 격차조차 확인에서
 * 그대로 재현돼 합산 비가 오히려 올라갔다. 배 단위 회귀는 말할 것도 없다. 반대로 문턱을 1.0 으로
 * 낮춰 깨끗한 기계에서 확인 경로를 강제로 태우면
 * `1차 없음=5.654ms 타인=5.717ms 비=1.011 → 확인 없음=5.029ms 타인=5.101ms ·
 * 합산 없음=5.221ms 타인=5.258ms 비=1.007` 로, 확인 회차 자체가 어느 팔에도 치우침을 넣지 않는다.
 *
 * **`docs/master-plan.md` 의 「경계값에서의 재실행·표본 확대 판단은 사람이 하며, 자동 재시도로
 * 가리지 않는다」와 어긋나지 않는다.** 그 조항은 골든셋 통과율 게이트(n=20 단일 실행)를 말한다.
 * 여기는 CI 회귀 검사이고, 재측정은 열린 재시도가 아니라 **고정된 한 번의 더 큰 표본**이며,
 * 1차·확인·합산 세 값이 모두 로그에 남는다(api 기본 `test` 의 `standard_out`). 통과한 실행에서도
 * 사람이 그 요약을 그대로 본다.
 *
 * 확인 표본 수가 짝수인 것은 의도다 — 1차가 홀수라 합산이 홀수가 되려면 확인이 짝수여야 하고,
 * 판정을 내는 합산 중앙값이 실제 표본 하나로 정해져야 하기 때문이다. 확인 회차만의 중앙값은
 * 요약에만 쓰이므로 짝수 표본의 위쪽 가운데 값이어도 판정에 영향이 없다.
 */
object TimingUniformity {
    /** 판정이 비교하는 한 갈래. [key] 로 측정 함수가 갈래를 가르고, [label] 은 요약에 찍히는 이름이다. */
    data class Arm(
        val key: String,
        val label: String,
    )

    /** 한 판정의 표본 설계 전부. 호출부는 자기 companion 상수로 이 값을 만들어 [judge] 에 넘긴다. */
    data class TimingSpec(
        val arms: List<Arm>,
        val samples: Int,
        val confirmationSamples: Int,
        val seed: Long,
        val confirmationSeed: Long,
        val gapMillis: Long,
        val threshold: Double,
        val minMeasurableMillis: Double,
    ) {
        init {
            require(arms.size >= 2) { "팔이 ${arms.size} 개다 — 비를 내려면 둘 이상이어야 한다" }
            require(arms.distinctBy { it.key }.size == arms.size) { "팔 key 가 겹친다: ${arms.map { it.key }}" }
            require(samples % 2 == 1) { "1차 표본 수는 홀수여야 한다: $samples" }
            require(confirmationSamples > 0) { "확인 표본 수는 0 보다 커야 한다: $confirmationSamples" }
            require((samples + confirmationSamples) % 2 == 1) {
                "합산 표본 수는 홀수여야 한다: $samples + $confirmationSamples = ${samples + confirmationSamples}"
            }
        }
    }

    /** [judge] 의 결과. 확인 회차를 돌지 않았으면 [confirmation] 이 `null` 이고 [pooled] 는 [first] 와 같다. */
    data class Outcome(
        val spec: TimingSpec,
        val first: Map<String, Double>,
        val confirmation: Map<String, Double>?,
        val pooled: Map<String, Double>,
        val ratio: Double,
    )

    /**
     * [arms] 각각을 [samplesPerArm] + 1(워밍업 하나)회씩 섞어 [measureMillis] 로 재고, 각 팔의
     * **첫** 측정을 버린 나머지 원표본을 팔 key 별로 돌려준다. 순서는 [seed] 로 고정한
     * [shuffled] 다 — 특정 팔에 CPU 클럭 드리프트·GC 정지가 몰리지 않게 한다.
     */
    fun interleavedSamples(
        arms: List<Arm>,
        samplesPerArm: Int,
        seed: Long,
        measureMillis: (arm: Arm) -> Double,
    ): Map<String, List<Double>> {
        require(samplesPerArm > 0) { "표본 수는 0 보다 커야 한다: $samplesPerArm" }

        val order = arms.flatMap { arm -> List(samplesPerArm + 1) { arm } }.shuffled(Random(seed))
        val warmed = mutableSetOf<String>()
        val samples = arms.associate { it.key to mutableListOf<Double>() }

        order.forEach { arm ->
            val elapsed = measureMillis(arm)
            if (warmed.add(arm.key)) return@forEach
            samples.getValue(arm.key) += elapsed
        }

        return arms.associate { it.key to samples.getValue(it.key).toList() }
    }

    /** 정렬 후 가운데 표본. 표본 수가 홀수면 중앙값이 실제 표본 하나로 정해진다. */
    fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /** 가장 느린 팔과 가장 빠른 팔의 중앙값 비. [minMeasurableMillis] 는 0 으로 나누지 않는 바닥이다. */
    fun ratio(
        medians: Map<String, Double>,
        minMeasurableMillis: Double,
    ): Double = medians.values.max() / medians.values.min().coerceAtLeast(minMeasurableMillis)

    /**
     * 이 판정의 유일한 입구. [spec] 대로 1차를 재고, 그 비가 [TimingSpec.threshold] **미만**이면
     * 거기서 끝낸다. 문턱 이상이면 [sleep] 으로 [TimingSpec.gapMillis] 만큼 띄운 뒤 **새** 표본을
     * 더 재고, 최종 [Outcome.ratio] 는 두 회차를 합친 표본의 중앙값이 낸다. [sleep] 은 단위
     * 테스트가 실제로 자지 않게 갈아 끼우는 자리다.
     */
    fun judge(
        spec: TimingSpec,
        sleep: (Long) -> Unit = Thread::sleep,
        measure: (Arm) -> Double,
    ): Outcome {
        val firstSamples = interleavedSamples(spec.arms, spec.samples, spec.seed, measure)
        val first = firstSamples.mapValues { (_, values) -> median(values) }
        val firstRatio = ratio(first, spec.minMeasurableMillis)
        if (firstRatio < spec.threshold) {
            return Outcome(spec, first, confirmation = null, pooled = first, ratio = firstRatio)
        }

        sleep(spec.gapMillis)
        val confirmationSamples =
            interleavedSamples(spec.arms, spec.confirmationSamples, spec.confirmationSeed, measure)
        val confirmation = confirmationSamples.mapValues { (_, values) -> median(values) }
        val pooled =
            spec.arms.associate { arm ->
                arm.key to median(firstSamples.getValue(arm.key) + confirmationSamples.getValue(arm.key))
            }
        return Outcome(spec, first, confirmation, pooled, ratio(pooled, spec.minMeasurableMillis))
    }

    /** 실패 메시지·로그용 한 줄 요약. 팔 이름은 [TimingSpec.arms] 의 key 로 찾는다 — 자리로 찾지 않는다. */
    fun describe(outcome: Outcome): String {
        val arms = outcome.spec.arms
        val firstRatio = ratio(outcome.first, outcome.spec.minMeasurableMillis)
        val first = "1차 ${render(arms, outcome.first)} 비=${format(firstRatio)}"
        val confirmation = outcome.confirmation ?: return first
        return "$first → 확인 ${render(arms, confirmation)} · " +
            "합산 ${render(arms, outcome.pooled)} 비=${format(outcome.ratio)}"
    }

    private fun render(
        arms: List<Arm>,
        medians: Map<String, Double>,
    ): String = arms.joinToString(" ") { arm -> "${arm.label}=${format(medians.getValue(arm.key))}ms" }

    private fun format(value: Double): String = "%.3f".format(value)
}

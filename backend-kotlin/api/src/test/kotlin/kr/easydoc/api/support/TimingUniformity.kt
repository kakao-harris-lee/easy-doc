package kr.easydoc.api.support

import kotlin.random.Random

/**
 * 소유권 은닉 셋째 축(응답 시간) 판정의 표본 설계 — 교차 순서 + 워밍업 버림 + 중앙값 +
 * 확인 회차.
 *
 * CI 러너가 붐빌 때 한 자릿수 밀리초 중앙값은 21표본 한 번으로 판정하기에 너무
 * 시끄럽다(2026-09-23 GitHub Actions 사례 — 비 1.726 이 문턱 1.5 를 한 번 넘었다가
 * 같은 커밋 재실행·로컬 3,325 건 모두 통과). 소유권 자체는 SQL 계층이 강제하므로
 * (`OwnershipPredicateGuardTest`) 이 판정이 잡는 것은 「일하는 양이 갈리는 회귀」이지
 * 실제 정보 누출이 아니다. 그래서 1차 표본이 문턱을 넘을 때만 더 큰 확인 표본으로
 * 한 번 더 재 그 결과로 판정한다 — 짧은 잡음 하나로는 빌드가 깨지지 않지만, 진짜
 * 작업량 차이는 확인 표본에서도 그대로 남는다.
 */
object TimingUniformity {
    /**
     * [arms] 각각을 [samplesPerArm] + 1(워밍업 하나)회씩 섞어 [measureMillis] 로 재고,
     * 각 팔의 첫 측정을 버린 뒤 나머지의 중앙값을 돌려준다. 순서는 [seed] 로 고정한
     * [arms.flatMap].[shuffled] 다 — 특정 팔에 CPU 클럭 드리프트·GC 정지가 몰리지
     * 않게 한다. [samplesPerArm] 은 홀수여야 한다 — 그래야 중앙값이 표본 하나로 정해진다.
     */
    fun interleavedMedians(
        arms: List<String>,
        samplesPerArm: Int,
        seed: Long,
        measureMillis: (arm: String) -> Double,
    ): Map<String, Double> {
        require(samplesPerArm % 2 == 1) { "표본 수는 홀수여야 한다: $samplesPerArm" }

        val order = arms.flatMap { arm -> List(samplesPerArm + 1) { arm } }.shuffled(Random(seed))
        val warmed = mutableSetOf<String>()
        val samples = arms.associateWith { mutableListOf<Double>() }

        order.forEach { arm ->
            val elapsed = measureMillis(arm)
            if (warmed.add(arm)) return@forEach
            samples.getValue(arm) += elapsed
        }

        return arms.associateWith { arm -> median(samples.getValue(arm)) }
    }

    /** 가장 느린 팔과 가장 빠른 팔의 중앙값 비. [minMeasurableMillis] 는 0 으로 나누지 않는 바닥이다. */
    fun ratio(
        medians: Map<String, Double>,
        minMeasurableMillis: Double,
    ): Double = medians.values.max() / medians.values.min().coerceAtLeast(minMeasurableMillis)

    /** [measureWithConfirmation] 의 결과 — 확인 회차를 돌렸는지와 그 판정이 낸 최종 비. */
    data class Outcome(
        val first: Map<String, Double>,
        val confirmation: Map<String, Double>?,
        val ratio: Double,
    )

    /**
     * [firstPass] 로 먼저 재고, 그 비가 [threshold] 밑이면 그대로 돌려준다(확인 없음).
     * [threshold] 를 넘으면 [confirmationPass] 를 정확히 한 번 더 돌려 그 결과로 판정한다 —
     * 확인이 결정권을 가지므로 최종 [Outcome.ratio] 는 확인 표본의 비다.
     */
    fun measureWithConfirmation(
        threshold: Double,
        minMeasurableMillis: Double,
        firstPass: () -> Map<String, Double>,
        confirmationPass: () -> Map<String, Double>,
    ): Outcome {
        val first = firstPass()
        val firstRatio = ratio(first, minMeasurableMillis)
        if (firstRatio < threshold) {
            return Outcome(first, confirmation = null, ratio = firstRatio)
        }

        val confirmation = confirmationPass()
        return Outcome(first, confirmation, ratio(confirmation, minMeasurableMillis))
    }

    /**
     * 실패 메시지·로그용 한 줄 요약. [arms] 는 두 팔의 표시 이름이며, [Outcome.first] 와
     * [Outcome.confirmation] 이 순회하는 순서(= [interleavedMedians] 에 넘긴 `arms` 순서)와
     * 대응한다.
     */
    fun describe(
        outcome: Outcome,
        arms: Pair<String, String>,
        minMeasurableMillis: Double,
    ): String {
        val first = "1차 ${render(outcome.first, arms)} 비=%.3f".format(ratio(outcome.first, minMeasurableMillis))
        val confirmation = outcome.confirmation ?: return first
        return "$first → 확인 ${render(confirmation, arms)} 비=%.3f".format(ratio(confirmation, minMeasurableMillis))
    }

    private fun render(
        medians: Map<String, Double>,
        arms: Pair<String, String>,
    ): String {
        val (firstMillis, secondMillis) = medians.values.toList()
        return "${arms.first}=%.3fms ${arms.second}=%.3fms".format(firstMillis, secondMillis)
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]
}

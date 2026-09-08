package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import java.math.BigDecimal
import java.time.Duration

/** 실패 한 건의 기록. 어느 문서에서, 무엇 때문에, 다시 불렀는지. */
internal data class RecordedFault(
    val documentId: String,
    val fault: LaneFault,
    val retried: Boolean,
)

/**
 * 레인 한 번의 호출 기록.
 *
 * 담는 것은 문서 id·원인·상태·호출 수·토큰 수뿐이다 — 본문은 어디에도 없다.
 */
internal class LaneJournal(private val retryBudget: Int = DEFAULT_RETRY_BUDGET) {
    private val faults = mutableListOf<RecordedFault>()

    /** 지금 어느 문서를 재는 중인가. 레인은 문서를 **순차로** 돌므로 커서 하나면 된다. */
    private var stage: String = SETUP_STAGE

    /**
     * 지금이 judge 호출 구간인가.
     *
     * 게이트 ⓪ 이 보는 출력 토큰은 **변환** 호출의 것이다. judge 는 같은 provider 를 쓰지만
     * "yes"/"no" 한 줄을 돌려주므로, 섞으면 [largestConversionCallOutputTokens] 가 무엇의
     * 최댓값인지 말할 수 없게 된다.
     */
    private var judging: Boolean = false

    var calls: Int = 0
        private set

    var retries: Int = 0
        private set

    var inputTokens: Int = 0
        private set

    var outputTokens: Int = 0
        private set

    /**
     * 레인 전체가 쓴 예상 비용 합계. 2026-09-08 결정(backlog §1.5 항목 6) — 변환·judge 구분
     * 없이 **레인 전체 지출**을 더한다(비용은 호출 종류를 가리지 않는다).
     *
     * **단가가 통째로 미설정이면 `null`이다** — 0달러로 속이지 않는다([LlmCompletion.estimatedCostUsd]
     * 와 같은 규칙). 값이 있는 호출과 없는 호출이 섞이면 합계는 값이 있는 만큼만 더해지고,
     * 없는 호출 수는 [costUnknownCalls] 가 따로 센다 — 섞어서 부분합을 전체합처럼 보이게
     * 하지 않기 위해서다.
     */
    var estimatedCostUsd: BigDecimal? = null
        private set

    /** [estimatedCostUsd] 를 계산할 때 단가가 없어 더하지 못한 호출 수. */
    var costUnknownCalls: Int = 0
        private set

    /**
     * [estimatedCostUsd] 를 계산한 입력 토큰 단가 스냅샷. 레인 한 회차는 단가 설정이 하나이므로
     * **처음으로 단가가 있었던(non-null) 호출에서 한 번만** 찍는다 — 이후 호출은 값이 같을
     * 것이라 다시 찍지 않는다. 맨 처음 호출을 무조건 기준으로 삼지 않는 이유는, 단가 없는
     * 호출이 먼저 오고 단가 있는 호출이 뒤에 오면(예: 첫 판정에 실패해 재시도로 값이 채워짐)
     * 스냅샷이 `null` 로 굳어 리포트가 실제로는 있는 단가를 「미설정」으로 오독하기 때문이다.
     */
    var pricingInputUsdPerMtok: BigDecimal? = null
        private set

    /** [pricingInputUsdPerMtok] 과 같은 스냅샷의 출력 토큰 단가. */
    var pricingOutputUsdPerMtok: BigDecimal? = null
        private set

    private var pricingCaptured: Boolean = false

    /**
     * 관측한 **변환** 호출 하나가 낸 출력 토큰의 최댓값.
     *
     * 문서당 합계(`ConversionUsage.outputTokens`)는 최대 2회 호출의 합이라 단일 호출 상한
     * `DEFAULT_MAX_TOKENS` 대비로는 상계치다. 상한을 올릴지 판단하는 데 필요한 것은 **한 번의
     * 호출이 실제로 얼마나 냈는가**이므로 그 값을 여기서 따로 센다.
     *
     * **보정 호출을 포함한다.** 보정은 변환과 같은 상한을 쓰고, 초안 전문에 위반 목록까지
     * 실어 보내므로 오히려 더 큰 출력을 낼 수 있다 — 상한에 가장 가까웠던 호출이 보정 쪽일
     * 수 있다는 뜻이다. 그것을 빼고 재면 「상한이 모자라는가」에 답할 수 없다.
     * judge 만 뺀다: "yes"/"no" 한 줄이라 섞으면 최댓값이 무엇의 최댓값인지 말할 수 없다.
     */
    var largestConversionCallOutputTokens: Int = 0
        private set

    /** 변환·보정 호출 수. 호출 단위 절단률의 분모다. */
    var conversionCalls: Int = 0
        private set

    /**
     * `truncated` 가 참이었던 **변환·보정** 호출 수.
     *
     * 문서 단위 절단(`ConversionFailureKind.TRUNCATED`)과 **다른 값이고, 그 차이가 이 값의
     * 존재 이유다.** `ConvertDocumentUseCase.repairOnce` 는 보정 호출이 절단되면
     * (`as? Outcome.Body` 가 실패하면) 원본 초안을 채택하고 변환을 **성공으로** 끝낸다 —
     * 제품 동작으로는 옳지만(master-plan §3.3 「보정 실패·악화 시 원본 채택」), 그 절단은
     * 문서 단위 집계에 한 건도 남지 않는다. 게이트 ⓪ 이 묻는 것은 「상한에 닿았는가」이므로
     * 여기서 호출 단위로 센다.
     */
    var truncatedConversionCalls: Int = 0
        private set

    /**
     * `truncated` 가 참이었던 judge 호출 수. 게이트 숫자가 아니라 **측정 유효성 경고**다 —
     * judge 응답이 잘리면 `GoldenJudge.isYes` 가 잘린 문자열을 읽어 판정이 뒤집힐 수 있다.
     */
    var truncatedJudgeCalls: Int = 0
        private set

    private val truncatedCallsByDocument = mutableMapOf<String, Int>()

    val budget: Int get() = retryBudget

    /** 재시도 예산을 다 썼는가. 다 썼다는 사실 자체가 「그날 인프라가 흔들렸다」는 관측값이다. */
    val budgetExhausted: Boolean get() = retries >= retryBudget

    fun beginDocument(documentId: String) {
        stage = documentId
        judging = false
    }

    /** 같은 문서의 judge 구간으로 넘어간다. 실패 귀속은 그대로 문서 id 를 따른다. */
    fun beginJudge(documentId: String) {
        stage = documentId
        judging = true
    }

    fun recordCall(completion: LlmCompletion) {
        calls++
        inputTokens += completion.inputTokens
        outputTokens += completion.outputTokens
        recordCost(completion)
        if (judging) {
            if (completion.truncated) {
                truncatedJudgeCalls++
            }
            return
        }
        conversionCalls++
        largestConversionCallOutputTokens = maxOf(largestConversionCallOutputTokens, completion.outputTokens)
        if (completion.truncated) {
            truncatedConversionCalls++
            truncatedCallsByDocument.merge(stage, 1, Int::plus)
        }
    }

    /** 이 문서의 변환·보정 호출 중 절단된 수. 구간별 집계가 장문 쪽 쏠림을 보려면 문서별이어야 한다. */
    fun truncatedCallsFor(documentId: String): Int = truncatedCallsByDocument[documentId] ?: 0

    /**
     * [estimatedCostUsd]·[pricingInputUsdPerMtok]/[pricingOutputUsdPerMtok] 를 채운다.
     *
     * 단가 스냅샷은 **처음으로 단가가 있었던(`pricingInputUsdPerMtok != null`) 호출**에서만
     * 찍는다 — 맨 처음 호출로 찍으면, 그 호출에 하필 단가가 없었을 때([completion.estimatedCostUsd]
     * KDoc과 같은 이유로 `pricingInputUsdPerMtok` 도 함께 `null`) 스냅샷이 영영 `null` 로
     * 굳어 이후 단가가 있는 호출이 와도 리포트가 「단가 미설정」으로 잘못 읽는다. 레인 한
     * 회차는 조립이 하나뿐이라 단가가 한 번 찍히면 이후 호출도 같은 값을 내므로 다시 찍지
     * 않는다. [completion.estimatedCostUsd] 가 `null` 이면(단가 미설정) 합계에 더하지 않고
     * [costUnknownCalls] 만 늘린다 — 없는 값을 0으로 더하면 부분합이 전체합처럼 보인다.
     */
    private fun recordCost(completion: LlmCompletion) {
        if (!pricingCaptured && completion.pricingInputUsdPerMtok != null) {
            pricingInputUsdPerMtok = completion.pricingInputUsdPerMtok
            pricingOutputUsdPerMtok = completion.pricingOutputUsdPerMtok
            pricingCaptured = true
        }
        val cost = completion.estimatedCostUsd
        if (cost == null) {
            costUnknownCalls++
        } else {
            estimatedCostUsd = (estimatedCostUsd ?: BigDecimal.ZERO) + cost
        }
    }

    fun recordFault(
        fault: LaneFault,
        retried: Boolean,
    ) {
        calls++
        faults += RecordedFault(stage, fault, retried)
        if (retried) {
            retries++
        }
    }

    /** 레인 전체 재시도 예산이 남았는가. */
    fun retryAllowed(): Boolean = retries < retryBudget

    fun lastFault(documentId: String): LaneFault? = faults.lastOrNull { it.documentId == documentId }?.fault

    fun retriesFor(documentId: String): Int = faults.count { it.documentId == documentId && it.retried }

    /**
     * 원인별 실패 건수. **재시도로 가려진 실패도 여기 남는다** — 그것이 이 집계의 값어치다.
     * 재시도가 흔들림을 지워 버리면 레인은 다시 도구가 아니게 된다.
     */
    fun distribution(): Map<String, Int> = faults.groupingBy { it.fault.label }.eachCount().toSortedMap()

    private companion object {
        /**
         * 레인 전체가 쓸 수 있는 재시도 총량. 문서 56건에 대해 이만큼이면 산발적 흔들림은 넘기고,
         * 전면 장애는 넘기지 못한다 — 넘기지 **못해야** 한다. 예산 없이 호출당 상한만 두면
         * 56건이 전부 흔들리는 날 호출 수가 3배가 되고, 결과는 여전히 못 쓴다.
         */
        const val DEFAULT_RETRY_BUDGET: Int = 20

        const val SETUP_STAGE: String = "(레인 준비)"
    }
}

/**
 * 레인 재시도 정책. 상한 셋 중 둘(시도 횟수·backoff)이 여기 있고, 나머지 하나인
 * 레인 전체 예산은 [LaneJournal] 에 있다.
 */
internal data class LaneRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val firstBackoff: Duration = DEFAULT_FIRST_BACKOFF,
    val multiplier: Long = DEFAULT_MULTIPLIER,
) {
    /** [attempt] 번째 시도가 실패한 뒤 기다릴 시간. */
    fun backoff(attempt: Int): Duration {
        var wait = firstBackoff
        repeat(attempt - 1) { wait = wait.multipliedBy(multiplier) }
        return wait
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_MULTIPLIER: Long = 3
        val DEFAULT_FIRST_BACKOFF: Duration = Duration.ofSeconds(2)
    }
}

/**
 * 제품 provider 를 레인 계측이 감싼다 — 실패 원인을 붙잡고, 일시적 실패에 한해 다시 부른다.
 *
 * ## 재시도가 제품이 아니라 여기 있는 이유
 *
 * 「SDK/HTTP 계층의 자동 재시도를 쓰지 않는다」는 계약이자 프로젝트 CLAUDE.md 규칙이고, 그 판단은
 * 유지한다 — 어댑터 재시도는 워커 재시도와 겹쳐 호출 수를 조용히 늘린다. 그렇다고 레인이 429 한
 * 번에 무너지면 측정을 못 하므로, 감당은 하되 **레인 안에서** 한다. 상한 셋(호출당 시도 횟수,
 * 지수 backoff, 레인 전체 예산)을 두고 **재시도한 사실과 횟수를 [LaneJournal] 에 남긴다.**
 * 재시도로 가려진 불안정성이 보이지 않게 되면 이 레인은 다시 도구가 아니게 된다.
 *
 * ## 호출 간격을 따로 두지 않는 이유
 *
 * 레인은 문서를 한 건씩 순차로 돌고, 한 문서 안의 변환·보정·judge 호출도 순차다 — 이미 동시
 * 호출이 없으므로 「동시성 때문에 429」라는 설명은 이 레인에 해당하지 않는다. 56건에 고정 지연을
 * 더하면 벽시계 시간만 늘고(2026-08-27 실측은 이미 1시간 11분이었다) 얻는 것이 없다. 서버가 속도를
 * 낮추라고 말할 때의 간격은 아래 backoff 가 그때만 정확히 그만큼 준다.
 */
internal class LaneInstrumentedProvider(
    private val delegate: LlmProvider,
    private val journal: LaneJournal,
    private val policy: LaneRetryPolicy = LaneRetryPolicy(),
    private val pause: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : LlmProvider {
    override val name: String get() = delegate.name

    /** 감싼 사실을 숨긴다 — 「무엇으로 쟀는지」는 제품 provider 의 설명이어야 한다. */
    override fun toString(): String = delegate.toString()

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion {
        var attempt = 1
        while (true) {
            try {
                val completion = delegate.complete(prompt, options)
                journal.recordCall(completion)
                return completion
            } catch (exc: LlmProviderException) {
                val fault = LaneFaults.of(exc)
                val retry = fault.transient && attempt < policy.maxAttempts && journal.retryAllowed()
                journal.recordFault(fault, retried = retry)
                if (!retry) {
                    throw exc
                }
                pause(policy.backoff(attempt))
                attempt++
            }
        }
    }
}

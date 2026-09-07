package kr.easydoc.application.conversion

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.FactIssue
import kr.easydoc.core.easyread.SecureDocumentIds
import kr.easydoc.core.easyread.SentenceIssue
import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.easyread.findMissingFacts
import kr.easydoc.core.easyread.postprocess
import kr.easydoc.core.exceptions.LlmEmptyResultException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.exceptions.LlmTruncatedException
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.privacy.ModelDraft
import java.time.Clock

/** 문서 1건을 쉬운 글로 바꾼다 — 프롬프트 → LLM → 후처리 → (조건부 보정 → 채택 판정). */
class ConvertDocumentUseCase(
    private val provider: LlmProvider,
    private val documentIds: DocumentIdGenerator = SecureDocumentIds,
    /**
     * `convert` 호출에 명시 인자가 없을 때 쓰는 기본 옵션. composition root
     * (`ConversionWorkerConfiguration`)가 `easydoc.llm.max-output-tokens` 구성값으로
     * 채워 넣는다 — 이 자리가 아니면 실제 워커 호출 경로에 구성값이 닿을 곳이 없다.
     *
     * [dictionary] 앞에 둔다 — [dictionary] 가 SAM 변환 대상이라 호출부가 trailing lambda
     * (`ConvertDocumentUseCase(provider, ids) { context }`)로 넘기고, 마지막 자리가 아니면
     * 그 문법이 깨진다.
     */
    private val defaultOptions: LlmOptions = LlmOptions(),
    /**
     * 완성 호출의 `calledAt`(U1 원장)을 캡처할 시계. `dictionary` 보다 앞에 둔다 — 뒤 인자가
     * SAM 변환 대상이라 trailing lambda 문법(`ConvertDocumentUseCase(provider, ids) { context }`)
     * 이 마지막 자리를 가리켜야 한다.
     */
    private val clock: Clock = Clock.systemUTC(),
    private val dictionary: DictionaryContextSource = NoDictionaryContext,
) {
    /** worker 가 변환 유스케이스에 들어가기 전 실패를 기록할 때 쓰는 벤더 이름. */
    val providerName: String
        get() = provider.name

    /**
     * 원문 [source] 를 쉬운 글로 바꾼다. 재변환(`ReconvertUnitService`)도 이 진입점을 그대로
     * 쓴다 — 넘기는 [source] 가 문서 전체냐 단위 하나냐의 차이일 뿐, 프롬프트·보정·채택
     * 판정 경로는 완전히 같다.
     *
     * [dictionaryContext] 는 이 문서에만 해당하는 사전 지침이며 **①차 변환 프롬프트에만** 실린다
     * (계약은 `buildUserPrompt` KDoc). 보정 패스에 함께 넘기지 않는 것이 이 인자의 요점이다 —
     * 사전 있음/없음 A/B 에서 바뀌는 변수가 둘이 되면 통과율 차이가 어느 쪽 때문인지 말할 수 없다.
     *
     * 인자를 주지 않으면 [DictionaryContextSource] 포트에 묻는다. **명시 인자가 이긴다** —
     * 골든 LLM 레인(`GoldenLlmLaneDictionary`)이 문서마다 미리 뽑아 둔 컨텍스트를 실어 A/B 를
     * 재는데, 포트가 그것을 덮어쓰면 레인은 자기가 실은 것과 다른 것을 재고도 실은 것을 쟀다고
     * 적게 된다. 「호출자가 무엇을 실을지 이미 정했으면 그대로」가 두 경로를 함께 성립시킨다.
     *
     * **[purpose] 매개변수 — 원장(U1) 라벨을 유스케이스가 결정한다.** LLM 호출 원장
     * (`llm_calls`)은 목적을 셋(`convert`·`repair`·`reconvert`)만 안다. 일반 문서 변환은
     * 기본값 [LlmCallPurpose.CONVERT] 를 그대로 쓰고, 1차 호출은 `convert`, 조건부 보정 호출은
     * `repair` 로 갈린다. **재변환은 다르다** — `ReconvertUnitService` 가 이 메서드를
     * [LlmCallPurpose.RECONVERT] 로 직접 부르면, 1차 호출과(있었다면) 그 보정 호출 **둘 다**
     * `reconvert` 로 기록된다. 대안(호출자가 반환된 `ConversionUsage.calls` 를 사후에 재라벨링)도
     * 가능했지만, 그러면 「이 호출이 어느 라벨인가」를 판정하는 로직이 `Pass`(호출 순서를 아는
     * 곳)와 호출자(라벨을 아는 곳) 둘로 쪼개진다 — 재변환의 보정 호출까지 `repair` 로 잘못 세는
     * 조용한 버그가 그 이음매에서 생기기 쉽다. 매개변수 하나로 `Pass` 안에서 완결하는 쪽이 더 작다.
     *
     * **2026-09-07 정정: PR #58 로 개인정보 마스킹이 제거됐다.** 이전에는 재변환이 문서 전체를
     * 다시 마스킹하지 않으려고 이미 마스킹된 입력을 받는 `convertMasked` 진입점이 따로 있었다.
     * 마스킹 자체가 사라져 그 구분이 필요 없어졌고, 모든 호출자가 이 메서드 하나로 합쳐졌다.
     */
    fun convert(
        source: String,
        options: LlmOptions = defaultOptions,
        dictionaryContext: String? = null,
        purpose: LlmCallPurpose = LlmCallPurpose.CONVERT,
    ): ConversionResult =
        Pass(provider, documentIds, options, dictionaryContext, dictionary, purpose, clock).run(source)
}

/** 변환 1건의 실행 상태. */
@Suppress("LongParameterList")
private class Pass(
    private val provider: LlmProvider,
    private val documentIds: DocumentIdGenerator,
    private val options: LlmOptions,
    private val dictionaryContext: String?,
    private val dictionary: DictionaryContextSource,
    private val purpose: LlmCallPurpose,
    private val clock: Clock,
) {
    private val budget = CompletionBudget()
    private var inputTokens = 0
    private var outputTokens = 0
    private var lastModel: String? = null
    private val calls = mutableListOf<LlmCallRecord>()

    /**
     * 보정 호출의 원장 라벨 — 일반 변환([purpose] = [LlmCallPurpose.CONVERT])은 `repair`,
     * 재변환 맥락은 [purpose] 를 그대로 쓴다(`ConvertDocumentUseCase.convert` KDoc).
     */
    private val repairPurpose: LlmCallPurpose
        get() = if (purpose == LlmCallPurpose.CONVERT) LlmCallPurpose.REPAIR else purpose

    fun run(source: String): ConversionResult {
        val context = dictionaryContext ?: dictionary.contextFor(source)
        val prompt = LlmPrompt.forConversion(source, documentIds, context)
        val charCount = source.length

        // ① 변환 패스 — 항상 정확히 1회.
        return when (val first = complete(prompt, purpose, charCount)) {
            is Outcome.Rejected -> {
                ConversionResult.Failed(
                    kind = first.kind,
                    usage = usage(),
                    attribution = LlmAttribution(provider.name, lastModel),
                )
            }

            is Outcome.Body -> {
                finish(first.text, source)
            }
        }
    }

    /**
     * 1차 결과를 받은 뒤의 경로. **여기부터는 변환이 실패하지 않는다** — 보정에서 무슨 일이
     * 일어나도 사용자는 1차 결과를 받는다(인벤토리 §3.1 (라) 의 비대칭).
     */
    private fun finish(
        draft: String,
        source: String,
    ): ConversionResult {
        val issues = checkStyle(draft).issues
        val factIssues = findMissingFacts(source, draft)

        // ② 보정 패스 — 기계 검출된 위반(문체 또는 사실 누락)이 있을 때만, 정확히 1회.
        //
        // **이 자리에 루프가 없다는 것이 상한의 실체다.** 보정 결과에 위반이 남아 있어도,
        // 보정을 기각했어도 다시 부르지 않는다. `while (issues.isNotEmpty())` 로 바꾸는 순간
        // 상한은 사라지고 지연·비용의 하한도 없어진다(인벤토리 §3.1 (가) 2).
        val adopted =
            if (issues.isEmpty() && factIssues.isEmpty()) {
                Adoption.keep(draft)
            } else {
                repairOnce(draft, issues, factIssues, source)
            }

        return ConversionResult.Converted(
            easyText = ModelDraft(adopted.text),
            repaired = adopted.repaired,
            usage = usage(),
            attribution = LlmAttribution(provider.name, lastModel),
        )
    }

    /** 보정을 **한 번** 부르고 채택 여부를 판정한다. */
    private fun repairOnce(
        draft: String,
        issues: List<SentenceIssue>,
        factIssues: List<FactIssue>,
        source: String,
    ): Adoption {
        // ModelDraft 로 감싸는 것이 허용되는 자리다 — 값의 출처가 LLM 출력의 후처리 결과다
        // (`DocumentBody.kt` 「provenance 래퍼 사용 규약」).
        val prompt = LlmPrompt.forRepair(ModelDraft(draft), issues, factIssues, documentIds)
        val candidate =
            (complete(prompt, repairPurpose, source.length) as? Outcome.Body)?.text
                ?: return Adoption.keep(draft)

        val decision = decideRepairAdoption(original = draft, candidate = candidate, source = source)
        return if (decision.accepted) Adoption(candidate, repaired = true) else Adoption.keep(draft)
    }

    /**
     * 완성 요청 1건. 예산을 쓰고, 응답을 후처리까지 마친 뒤 결과를 분류한다.
     *
     * **provider 예외가 아니면 원장에 기록한다** — 절단·빈 결과·거절([classify] 이 [Outcome
     * .Rejected] 로 분류하는 것들)도 실제로 완성 응답을 받았고 토큰을 썼으므로 기록 대상이다.
     * 기록하지 않는 것은 [LlmProviderException] 으로 완성 자체가 나지 않은 경우뿐이다
     * (계획 §2 결정 2 「실패한 호출은 기록하지 않는다」— 여기서 「실패」는 이 예외를 뜻한다).
     */
    private fun complete(
        prompt: LlmPrompt,
        callPurpose: LlmCallPurpose,
        charCount: Int,
    ): Outcome {
        val completion =
            try {
                budget.spend { provider.complete(prompt, options) }
            } catch (exc: LlmProviderException) {
                return Outcome.Rejected(failureKind(exc))
            }
        // 호출이 실제로 끝난 시각을 여기서 캡처한다 — 저장은 한참 뒤(트랜잭션 재진입·암호화
        // 이후)에 일어나므로, 그때 시계를 읽으면 호출 시각이 아니라 저장 시각이 찍힌다
        // (`LlmCallRecord.calledAt` KDoc).
        val calledAt = clock.instant()

        lastModel = completion.model
        inputTokens += completion.inputTokens
        outputTokens += completion.outputTokens
        calls +=
            LlmCallRecord(
                purpose = callPurpose,
                provider = provider.name,
                model = completion.model,
                inputTokens = completion.inputTokens,
                outputTokens = completion.outputTokens,
                latencyMs = completion.latencyMs,
                estimatedCostUsd = completion.estimatedCostUsd,
                pricingInputUsdPerMtok = completion.pricingInputUsdPerMtok,
                pricingOutputUsdPerMtok = completion.pricingOutputUsdPerMtok,
                charCount = charCount,
                calledAt = calledAt,
            )
        return classify(completion)
    }

    private fun usage() =
        ConversionUsage(
            llmCalls = budget.spent,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            calls = calls.toList(),
        )
}

/** 완성 요청 1건의 결과. 실패 사유는 **호출 위치와 무관하게** 같은 어휘로 낸다. */
private sealed interface Outcome {
    /** 후처리를 마쳐 쓸 수 있는 본문이 남았다. */
    data class Body(val text: String) : Outcome {
        /**
         * **본문을 찍지 않는다.** 여기 담긴 것은 후처리를 마친 변환 결과 전문이다
         * (`DocumentBody.kt` 「`toString()` 과 본문」 — 본문은 로그 금지다).
         */
        override fun toString(): String = "Body(${text.length}자)"
    }

    /** 쓸 수 없는 응답이다. 이것을 변환 실패로 볼지 삼킬지는 **호출 위치**가 정한다. */
    data class Rejected(val kind: ConversionFailureKind) : Outcome
}

/** 최종 채택 결과. */
private data class Adoption(
    val text: String,
    val repaired: Boolean,
) {
    /**
     * **본문을 찍지 않는다.** [text] 는 사용자에게 나갈 최종 본문이다 — `Body` 와 같은 이유.
     * [repaired] 는 남긴다: 어느 갈래를 채택했는지가 이 타입의 진단 값어치 전부다.
     */
    override fun toString(): String = "Adoption(text=${text.length}자, repaired=$repaired)"

    companion object {
        /** 1차 결과를 그대로 쓴다 — 보정을 부르지 않았거나 기각했다. */
        fun keep(draft: String) = Adoption(draft, repaired = false)
    }
}

/** 응답을 결과 상태로 분류한다. */
private fun classify(completion: LlmCompletion): Outcome =
    when {
        // provider 가 보고하는 것은 "출력 상한에서 잘렸다"는 **사실**뿐이다. 그것을 실패로
        // 볼지는 이 계층의 정책이고, 여기서는 실패다 — 잘린 본문을 성공 결과로 내보내면
        // 조용한 정보 누락이 된다(CNV-03).
        //
        // **빈 결과보다 먼저 본다.** 잘려서 본문이 아예 비어 온 응답이 실제로 있고
        // (`stop_reason=max_tokens` + 빈 content), 그때 사용자가 취할 조치는 "문서를 나눠
        // 올리기"이지 "다시 시도"가 아니다. 어댑터가 빈 본문에서 던지던 시절에는 이 분기가
        // 아예 도달하지 못했다 — 교차 종합 C-08.
        completion.truncated -> {
            Outcome.Rejected(ConversionFailureKind.TRUNCATED)
        }

        // 안전 분류기 거절. HTTP 200 + 빈 본문으로 오므로 값으로 구분하지 않으면 "빈 응답"과
        // 뭉뚱그려진다. 우리 쪽 버그 후보(빈 응답)와 입력 특성(거절)은 취할 조치가 다르다.
        //
        // 요구가 정한 실패 어휘는 셋(절단·빈 결과·호출 실패)뿐이라 새 종류를 만들지 않고
        // **호출 실패**로 접는다 — 우리가 만든 결과가 아니라 provider 가 내주기를 거부한
        // 것이기 때문이다. 어휘가 넓어지면 그때 갈라 낸다.
        completion.finishReason == LlmFinishReason.REFUSAL -> {
            Outcome.Rejected(ConversionFailureKind.PROVIDER_ERROR)
        }

        else -> {
            postprocess(completion.text).let { body ->
                if (body.isEmpty()) Outcome.Rejected(ConversionFailureKind.EMPTY_RESULT) else Outcome.Body(body)
            }
        }
    }

/** provider 예외를 실패 종류로 옮긴다. */
private fun failureKind(exc: LlmProviderException): ConversionFailureKind =
    when (exc) {
        is LlmTruncatedException -> ConversionFailureKind.TRUNCATED
        is LlmEmptyResultException -> ConversionFailureKind.EMPTY_RESULT
        else -> ConversionFailureKind.PROVIDER_ERROR
    }

package kr.easydoc.application.conversion

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.SecureDocumentIds
import kr.easydoc.core.easyread.SentenceIssue
import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.easyread.postprocess
import kr.easydoc.core.exceptions.LlmEmptyResultException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.exceptions.LlmTruncatedException
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.privacy.MaskingResult
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.maskText

/** 문서 1건을 쉬운 글로 바꾼다 — 마스킹 → 프롬프트 → LLM → 후처리 → (조건부 보정 → 채택 판정). */
class ConvertDocumentUseCase(
    private val provider: LlmProvider,
    private val documentIds: DocumentIdGenerator = SecureDocumentIds,
    private val dictionary: DictionaryContextSource = NoDictionaryContext,
) {
    /** worker 가 변환 유스케이스에 들어가기 전 실패를 기록할 때 쓰는 벤더 이름. */
    val providerName: String
        get() = provider.name

    /**
     * 원문 [source] 를 쉬운 글로 바꾼다.
     *
     * [dictionaryContext] 는 이 문서에만 해당하는 사전 지침이며 **①차 변환 프롬프트에만** 실린다
     * (계약은 `buildUserPrompt` KDoc). 보정 패스에 함께 넘기지 않는 것이 이 인자의 요점이다 —
     * 사전 있음/없음 A/B 에서 바뀌는 변수가 둘이 되면 통과율 차이가 어느 쪽 때문인지 말할 수 없다.
     *
     * 인자를 주지 않으면 [DictionaryContextSource] 포트에 묻는다. **명시 인자가 이긴다** —
     * 골든 LLM 레인(`GoldenLlmLaneDictionary`)이 문서마다 미리 뽑아 둔 컨텍스트를 실어 A/B 를
     * 재는데, 포트가 그것을 덮어쓰면 레인은 자기가 실은 것과 다른 것을 재고도 실은 것을 쟀다고
     * 적게 된다. 「호출자가 무엇을 실을지 이미 정했으면 그대로」가 두 경로를 함께 성립시킨다.
     */
    fun convert(
        source: String,
        options: LlmOptions = LlmOptions(),
        dictionaryContext: String? = null,
    ): ConversionResult = Pass(provider, documentIds, options, dictionaryContext, dictionary).run(source)
}

/** 변환 1건의 실행 상태. */
private class Pass(
    private val provider: LlmProvider,
    private val documentIds: DocumentIdGenerator,
    private val options: LlmOptions,
    private val dictionaryContext: String?,
    private val dictionary: DictionaryContextSource,
) {
    private val budget = CompletionBudget()
    private var inputTokens = 0
    private var outputTokens = 0
    private var lastModel: String? = null

    fun run(source: String): ConversionResult {
        val masking = maskText(source)
        // 사전은 **마스킹 직후** 마스킹된 본문으로 묻는다. 프롬프트에 실제로 들어가는 것이
        // 그 본문이고, 원문으로 물으면 배선이 마스킹 규칙을 우회하는 통로가 된다.
        val context = dictionaryContext ?: dictionary.contextFor(masking.maskedText)
        val prompt = LlmPrompt.forConversion(masking.maskedText, documentIds, context)

        // ① 변환 패스 — 항상 정확히 1회.
        return when (val first = complete(prompt)) {
            is Outcome.Rejected -> {
                ConversionResult.Failed(
                    kind = first.kind,
                    usage = usage(),
                    attribution = LlmAttribution(provider.name, lastModel),
                )
            }

            is Outcome.Body -> {
                finish(first.text, masking)
            }
        }
    }

    /**
     * 1차 결과를 받은 뒤의 경로. **여기부터는 변환이 실패하지 않는다** — 보정에서 무슨 일이
     * 일어나도 사용자는 1차 결과를 받는다(인벤토리 §3.1 (라) 의 비대칭).
     */
    private fun finish(
        draft: String,
        masking: MaskingResult,
    ): ConversionResult {
        val issues = checkStyle(draft).issues
        val placeholders = masking.items.map { it.placeholder }

        // ② 보정 패스 — 기계 검출된 위반이 있을 때만, 정확히 1회.
        //
        // **이 자리에 루프가 없다는 것이 상한의 실체다.** 보정 결과에 위반이 남아 있어도,
        // 보정을 기각했어도 다시 부르지 않는다. `while (issues.isNotEmpty())` 로 바꾸는 순간
        // 상한은 사라지고 지연·비용의 하한도 없어진다(인벤토리 §3.1 (가) 2).
        val adopted = if (issues.isEmpty()) Adoption.keep(draft) else repairOnce(draft, issues, placeholders)

        return ConversionResult.Converted(
            easyText = ModelDraft(adopted.text),
            repaired = adopted.repaired,
            // 기준 본문은 **채택된 최종 결과**다. 1차 결과에 대고 산출하면 사용자가 받은
            // 본문에 멀쩡히 있는 라벨을 유실로 신고하게 되고, 목록이 비어 있지 않으면
            // 내보내기가 409 로 막혀 정상 결과를 못 받는다(인벤토리 §3.1 (마)).
            missingPlaceholders = placeholders.filter { it !in adopted.text },
            maskedItems = masking.items,
            usage = usage(),
            attribution = LlmAttribution(provider.name, lastModel),
        )
    }

    /** 보정을 **한 번** 부르고 채택 여부를 판정한다. */
    private fun repairOnce(
        draft: String,
        issues: List<SentenceIssue>,
        placeholders: List<String>,
    ): Adoption {
        // ModelDraft 로 감싸는 것이 허용되는 자리다 — 값의 출처가 LLM 출력의 후처리 결과다
        // (`Masking.kt` 「provenance 래퍼 사용 규약」).
        val prompt = LlmPrompt.forRepair(ModelDraft(draft), issues, documentIds)
        val candidate = (complete(prompt) as? Outcome.Body)?.text ?: return Adoption.keep(draft)

        val decision = decideRepairAdoption(original = draft, candidate = candidate, placeholders = placeholders)
        return if (decision.accepted) Adoption(candidate, repaired = true) else Adoption.keep(draft)
    }

    /** 완성 요청 1건. 예산을 쓰고, 응답을 후처리까지 마친 뒤 결과를 분류한다. */
    private fun complete(prompt: LlmPrompt): Outcome {
        val completion =
            try {
                budget.spend { provider.complete(prompt, options) }
            } catch (exc: LlmProviderException) {
                return Outcome.Rejected(failureKind(exc))
            }

        lastModel = completion.model
        inputTokens += completion.inputTokens
        outputTokens += completion.outputTokens
        return classify(completion)
    }

    private fun usage() =
        ConversionUsage(
            llmCalls = budget.spent,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
}

/** 완성 요청 1건의 결과. 실패 사유는 **호출 위치와 무관하게** 같은 어휘로 낸다. */
private sealed interface Outcome {
    /** 후처리를 마쳐 쓸 수 있는 본문이 남았다. */
    data class Body(val text: String) : Outcome {
        /**
         * **본문을 찍지 않는다.** 여기 담긴 것은 후처리를 마친 변환 결과 전문이다
         * (`Masking.kt` 「`toString()` 과 본문」 — 개인정보가 없어도 본문은 금지).
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

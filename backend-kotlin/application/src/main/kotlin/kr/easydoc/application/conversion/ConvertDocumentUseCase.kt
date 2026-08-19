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

/**
 * 문서 1건을 쉬운 글로 바꾼다 — 마스킹 → 프롬프트 → LLM → 후처리 → (조건부 보정 → 채택 판정).
 *
 * 원본: `app/services/conversion.py::ConversionService.convert`.
 * 요구 정본: 인벤토리 §3.1 (CNV-01·CNV-02·CNV-04) · fixture `repair-adoption` 25건.
 *
 * ## 이 클래스가 지키는 계약 셋
 *
 * 1. **완성 요청은 최대 2회다** — 변환 1 + 위반이 있을 때만 보정 1. **루프가 아니다.**
 * 2. **같은 사건이라도 1차 호출과 보정 호출에서 결과가 정반대다** — 1차의 절단·빈 결과·호출
 *    실패는 변환 실패이고, 보정의 같은 사건은 삼켜 1차 결과를 채택한다.
 * 3. **자리표시자 유실은 막지 않고 보고한다** — 기준은 **채택된 최종 본문**이다.
 *
 * ## 재시도를 하지 않는다
 *
 * 이 계층에도, provider 어댑터에도 재시도가 없다. 재시도 정책 전부를 작업 큐(worker)가
 * 소유한다 — 두 계층이 각자 재시도하면 "문서당 최대 2회"가 메트릭에서 사라지고 계획 §5
 * Phase 7 의 **중복 LLM 호출**에 그대로 걸린다(계획 §4.6).
 *
 * ## 트랜잭션 안에서 부르지 않는다
 *
 * LLM 호출은 수 초가 걸린다. 커넥션을 붙잡은 채 기다리면 풀이 마른다
 * (kotlin-spring-conventions §6.2). 이 클래스가 DB 를 모르는 것이 그 규율의 절반이다.
 *
 * @param provider LLM 벤더 공통 인터페이스. 구현체는 `infrastructure` 가 조립해 넣는다.
 * @param documentIds 프롬프트 구분자 id 생성기. 테스트만 고정 생성기를 넘긴다.
 */
class ConvertDocumentUseCase(
    private val provider: LlmProvider,
    private val documentIds: DocumentIdGenerator = SecureDocumentIds,
) {
    /**
     * 원문 [source] 를 쉬운 글로 바꾼다.
     *
     * **[source] 는 이 함수 밖으로 나가지 않는다.** 처음 하는 일이 [maskText] 이고, provider 에
     * 닿는 것은 [LlmPrompt] 를 통과한 마스킹본뿐이다(`CLAUDE.md` 아키텍처 규칙 2).
     */
    fun convert(
        source: String,
        options: LlmOptions = LlmOptions(),
    ): ConversionResult = Pass(provider, documentIds, options).run(source)
}

/**
 * 변환 1건의 실행 상태.
 *
 * [ConvertDocumentUseCase] 를 상태 없는 싱글턴으로 두고 실행 상태를 여기 가두는 이유:
 * 예산·토큰 누계를 유스케이스 필드로 두면 두 변환이 같은 인스턴스를 쓸 때 서로의 호출 수를
 * 셈해 버린다. 그 결함은 부하가 걸려야 드러난다.
 */
private class Pass(
    private val provider: LlmProvider,
    private val documentIds: DocumentIdGenerator,
    private val options: LlmOptions,
) {
    private val budget = CompletionBudget()
    private var inputTokens = 0
    private var outputTokens = 0

    fun run(source: String): ConversionResult {
        val masking = maskText(source)

        // ① 변환 패스 — 항상 정확히 1회.
        return when (val first = complete(LlmPrompt.forConversion(masking.maskedText, documentIds))) {
            is Outcome.Rejected -> ConversionResult.Failed(kind = first.kind, usage = usage())
            is Outcome.Body -> finish(first.text, masking)
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
        )
    }

    /**
     * 보정을 **한 번** 부르고 채택 여부를 판정한다.
     *
     * 보정 호출의 절단·빈 결과·호출 실패는 전부 "1차 결과 유지"로 수렴한다 — 보정은 이미
     * 쓸 만한 결과를 더 낫게 만들려는 **시도**일 뿐이라, 그 실패가 변환을 실패시키면 사용자는
     * 받을 수 있었던 결과마저 잃는다(크레딧은 이미 차감됐다).
     */
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

    /**
     * 완성 요청 1건. 예산을 쓰고, 응답을 후처리까지 마친 뒤 결과를 분류한다.
     *
     * 토큰은 **결과 분류보다 먼저** 누계한다 — 절단·빈 결과도 부른 순간 비용이 발생했고,
     * 빼면 원가가 실제보다 적게 잡힌다(인벤토리 §3.1 (다)).
     */
    private fun complete(prompt: LlmPrompt): Outcome {
        val completion =
            try {
                budget.spend { provider.complete(prompt, options) }
            } catch (exc: LlmProviderException) {
                return Outcome.Rejected(failureKind(exc))
            }

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

/**
 * 최종 채택 결과.
 *
 * `text` 와 `repaired` 를 따로 들지 않고 묶는 이유: 둘은 언제나 같이 정해지는데, 나눠 두면
 * "보정문을 채택했는데 repaired=false" 같은 어긋난 짝이 만들어질 수 있다. 실제로 identity
 * 비교(`adopted !== draft`)로 repaired 를 유도하려다 이 결함을 만들 뻔했다.
 */
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

/**
 * 응답을 결과 상태로 분류한다.
 *
 * 절단을 빈 결과보다 **먼저** 본다. 잘린 응답이 마침 후처리 뒤 비면 두 조건이 함께
 * 성립하는데, 그때 사용자가 취할 조치는 "문서를 나눠 올리기"(절단)이지 "다시 시도"가 아니다.
 */
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

/**
 * provider 예외를 실패 종류로 옮긴다.
 *
 * 예외 **타입**만 본다. 메시지를 파싱하거나 분기 조건으로 쓰지 않는다 — 메시지는 문구를
 * 손대는 순간 조용히 깨지고, 무엇이 담길지 알 수 없는 문자열을 분기에 쓰면 그것이 곧
 * 본문 유출 경로가 된다(`DomainExceptions.kt` 의 메시지 규약).
 */
private fun failureKind(exc: LlmProviderException): ConversionFailureKind =
    when (exc) {
        is LlmTruncatedException -> ConversionFailureKind.TRUNCATED
        is LlmEmptyResultException -> ConversionFailureKind.EMPTY_RESULT
        else -> ConversionFailureKind.PROVIDER_ERROR
    }

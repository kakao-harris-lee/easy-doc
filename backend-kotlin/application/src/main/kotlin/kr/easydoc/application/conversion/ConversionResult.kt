package kr.easydoc.application.conversion

import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.privacy.ModelDraft

/** 변환이 실패한 종류. */
enum class ConversionFailureKind(
    /** `conversions.failure_code` 에 적히는 값. 계약이 예외 클래스명을 요구한다. */
    val failureCode: String,
) {
    /** 출력 상한에서 응답이 잘렸다. 잘린 본문을 성공으로 넘기면 **조용한 정보 누락**이 된다. */
    TRUNCATED("LlmTruncatedException"),

    /** 후처리 뒤 본문이 남지 않았다. 빈 응답이거나 껍데기(코드펜스)만 온 경우다. */
    EMPTY_RESULT("LlmEmptyResultException"),

    /** provider 계층이 실패했다(전송·서버 오류 등). */
    PROVIDER_ERROR("LlmProviderException"),
    ;

    /** 큐가 같은 작업을 다시 집어 볼 실패인가. 절단·빈 결과는 입력이 같아서 반복하지 않는다. */
    val retryable: Boolean
        get() = this == PROVIDER_ERROR
}

/** 변환 1건이 쓴 자원. */
data class ConversionUsage(
    val llmCalls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    /**
     * 호출 단위 원장 항목(U1, 계획 §3) — 어느 호출이 보정이었는지, 각 호출의 비용까지 담는다.
     * **[llmCalls] 와 이 목록의 크기는 같다** — `ConvertDocumentUseCase.Pass.complete` 가
     * 시도할 때마다(`CompletionBudget.spend`) 성공이든 [kr.easydoc.core.exceptions
     * .LlmProviderException] 이든 항목을 정확히 하나 남긴다(백로그 「실패 호출 원장
     * 추적」, 2026-09-08 — 계획 §2 결정 2 「실패한 호출은 기록하지 않는다」는 이 변경으로
     * 뒤집혔다. 벤더가 실패한 요청의 입력 토큰에 과금할 수 있어(계획 §6 리스크 1) 이 행이
     * 없으면 그 청구를 대조할 방법이 없었다). [inputTokens]·[outputTokens] 는 이 목록의
     * **완성 응답을 받은**(`outcome = completed`) 항목만 합산한 값과 같다 — provider
     * 예외 항목은 토큰이 0이다. 이 세 필드는 이 목록이 생기기 전부터 있던 요약값이라
     * 그대로 둔다(기존 호출부가 바뀌지 않게). 기본값 `emptyList()` 는
     * `ConversionAcquire.Exhausted` 처럼 LLM 을 아예 부르지 못한 경로
     * (`ConversionUsage(llmCalls = 0, …)`)를 그대로 통과시킨다.
     */
    val calls: List<LlmCallRecord> = emptyList(),
)

/** 호출한 벤더와 응답 모델. 완성 요청이 예외로 끝나면 [model] 은 `null`. */
data class LlmAttribution(
    val providerName: String,
    val model: String?,
) {
    /** 필드 이름에 `name` 이 들어 민감 토큰으로 잡힌다. 값은 벤더 식별자라 길이와 유무만 남긴다. */
    override fun toString(): String =
        "LlmAttribution(providerName=${providerName.length}자, model=${model?.length ?: 0}자)"
}

/** 변환 유스케이스의 결과. */
sealed interface ConversionResult {
    /** 성공·실패 어느 쪽이든 보고한다. */
    val usage: ConversionUsage

    /** 어느 어댑터·모델을 썼는지. */
    val attribution: LlmAttribution

    /** 변환 성공. */
    class Converted(
        val easyText: ModelDraft,
        val repaired: Boolean,
        override val usage: ConversionUsage,
        override val attribution: LlmAttribution,
    ) : ConversionResult {
        /** 본문은 길이만 남긴다. */
        override fun toString(): String =
            "Converted(easyText=${easyText.value.length}자, repaired=$repaired, " +
                "attribution=$attribution, usage=$usage)"
    }

    /** 변환 실패. 사용자에게 줄 본문이 없다. */
    data class Failed(
        val kind: ConversionFailureKind,
        override val usage: ConversionUsage,
        override val attribution: LlmAttribution,
    ) : ConversionResult
}

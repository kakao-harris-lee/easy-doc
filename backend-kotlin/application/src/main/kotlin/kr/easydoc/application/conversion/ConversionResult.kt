package kr.easydoc.application.conversion

import kr.easydoc.core.privacy.MaskedItem
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
        val missingPlaceholders: List<String>,
        val maskedItems: List<MaskedItem>,
        override val usage: ConversionUsage,
        override val attribution: LlmAttribution,
    ) : ConversionResult {
        /** 본문은 길이만, 대응표는 건수만 남긴다. */
        override fun toString(): String =
            "Converted(easyText=${easyText.value.length}자, repaired=$repaired, " +
                "missingPlaceholders=${missingPlaceholders.size}, maskedItems=${maskedItems.size}, " +
                "attribution=$attribution, usage=$usage)"
    }

    /** 변환 실패. 사용자에게 줄 본문이 없다. */
    data class Failed(
        val kind: ConversionFailureKind,
        override val usage: ConversionUsage,
        override val attribution: LlmAttribution,
    ) : ConversionResult
}

package kr.easydoc.application.conversion

import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.privacy.ModelDraft

/** 변환이 실패한 종류. */
enum class ConversionFailureKind {
    /** 출력 상한에서 응답이 잘렸다. 잘린 본문을 성공으로 넘기면 **조용한 정보 누락**이 된다. */
    TRUNCATED,

    /** 후처리 뒤 본문이 남지 않았다. 빈 응답이거나 껍데기(코드펜스)만 온 경우다. */
    EMPTY_RESULT,

    /** provider 계층이 실패했다(전송·서버 오류 등). */
    PROVIDER_ERROR,
}

/** 변환 1건이 쓴 자원. */
data class ConversionUsage(
    val llmCalls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** 변환 유스케이스의 결과. */
sealed interface ConversionResult {
    /** 성공·실패 어느 쪽이든 보고한다. */
    val usage: ConversionUsage

    /** 변환 성공. */
    class Converted(
        val easyText: ModelDraft,
        val repaired: Boolean,
        val missingPlaceholders: List<String>,
        val maskedItems: List<MaskedItem>,
        override val usage: ConversionUsage,
    ) : ConversionResult {
        /** 본문은 길이만, 대응표는 건수만 남긴다. */
        override fun toString(): String =
            "Converted(easyText=${easyText.value.length}자, repaired=$repaired, " +
                "missingPlaceholders=${missingPlaceholders.size}, maskedItems=${maskedItems.size}, usage=$usage)"
    }

    /** 변환 실패. 사용자에게 줄 본문이 없다. */
    data class Failed(
        val kind: ConversionFailureKind,
        override val usage: ConversionUsage,
    ) : ConversionResult
}

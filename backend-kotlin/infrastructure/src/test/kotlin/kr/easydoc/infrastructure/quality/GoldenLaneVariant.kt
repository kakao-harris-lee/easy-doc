package kr.easydoc.infrastructure.quality

import kr.easydoc.core.easyread.ExplanationPromptVersion
import kr.easydoc.core.exceptions.ConfigurationException

/** 골든 변환 레인이 측정할 명시적 프롬프트·사전 정책 묶음. 기본은 기존 기준선이다. */
internal enum class GoldenLaneVariant(
    val wireName: String,
    val explanationPromptVersion: ExplanationPromptVersion,
) {
    BASELINE("baseline", ExplanationPromptVersion.BASELINE),
    R3("r3", ExplanationPromptVersion.R3),
    ;

    companion object {
        /** 비어 있지 않은 값만 선택을 바꾼다 — 그 밖의 값은 유료 호출 전에 거절한다. */
        const val ENV: String = "EASYDOC_LANE_VARIANT"

        fun from(env: (String) -> String?): GoldenLaneVariant {
            val raw = env(ENV)?.trim()?.takeIf(String::isNotEmpty) ?: return BASELINE
            return entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) }
                ?: throw ConfigurationException(
                    "$ENV='$raw' 은 지원하지 않는 변형이다 — 허용값: ${entries.joinToString { it.wireName }}",
                )
        }
    }
}

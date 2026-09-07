package kr.easydoc.core.llm

import java.math.BigDecimal
import java.time.Instant

/**
 * `llm_calls.purpose` 가 담는 값 — 이 호출이 **왜** 나갔는지.
 *
 * [CONVERT] 는 문서 1차 변환, [REPAIR] 는 조건부 보정 패스, [RECONVERT] 는 문단 재변환
 * (`ReconvertUnitService`)의 1차·보정 호출을 **한 목적으로 묶은 값**이다 — 재변환은 표준
 * 문서 변환 경로가 아니라 별도 예산·과금 축이라, 그 안에서 보정이 있었는지는 이 열이
 * 구분하지 않는다(`ConvertDocumentUseCase.convertMasked` KDoc 「purpose 매개변수」).
 */
enum class LlmCallPurpose(
    /** `llm_calls.purpose` 컬럼에 그대로 들어가는 값(V12 CHECK 제약과 같은 어휘). */
    val wireName: String,
) {
    CONVERT("convert"),
    REPAIR("repair"),
    RECONVERT("reconvert"),
}

/**
 * LLM 호출 원장(`llm_calls`) 행 하나에 실릴 **호출 자체의 값** — 어느 문서·워크스페이스의
 * 호출인지는 이 타입 밖(`kr.easydoc.application.conversion.LlmCallEntry`)이 안다.
 *
 * **본문·프롬프트·마스킹 항목을 담지 않는다** — 숫자와 이름(provider·model 식별자)뿐이라
 * `toString()` 을 손으로 가릴 이유가 없다(`SensitiveToStringReachTest` 의 민감 판정 토큰
 * 어느 것에도 이 타입의 필드 이름이 걸리지 않는다 — `provider`·`model` 은 벤더 식별자이지
 * 사용자 콘텐츠가 아니다).
 *
 * [latencyMs]·[estimatedCostUsd]·[pricingInputUsdPerMtok]·[pricingOutputUsdPerMtok] 는
 * [LlmCompletion] 의 같은 이름 필드에서 그대로 옮겨 온 값이다 — `MetricsLlmProviderDecorator`
 * 가 이미 계산해 채운 스냅샷을 재계산하지 않는다. 단가가 미설정이면 [estimatedCostUsd] 와
 * 단가 스냅샷 둘 다 `null` 이다(0 이 아니다 — 프로젝트 CLAUDE.md 「미설정은 null」).
 */
data class LlmCallRecord(
    val purpose: LlmCallPurpose,
    val provider: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMs: Long?,
    val estimatedCostUsd: BigDecimal?,
    val pricingInputUsdPerMtok: BigDecimal?,
    val pricingOutputUsdPerMtok: BigDecimal?,
    /**
     * 이 호출이 처리한 **마스킹된 입력**의 문자 수 — 변환·보정은 문서 전체 마스킹 본문,
     * 재변환은 그 단위(`maskedUnitOf`)의 길이다(`ConvertDocumentUseCase.Pass` KDoc).
     */
    val charCount: Int,
    /**
     * 이 호출이 **실제로 일어난 시각** — provider 가 완성 응답을 돌려준 직후,
     * `ConvertDocumentUseCase.Pass.complete` 안에서 캡처한다. 한 변환이 호출 두 건(1차·보정)을
     * 쓰면 이 값도 두 건이 서로 다르고 시간순으로 증가한다.
     *
     * **저장 시점이 아니다.** `ProcessConversionJob`·`ReconvertUnitService` 는 LLM 호출이 끝난
     * 한참 뒤(트랜잭션 재진입·후처리·암호화를 거친 뒤)에야 원장을 쓰므로, 그 시점에 시계를
     * 다시 읽으면 실제 호출 시각이 아니라 저장 시각이 찍힌다 — 청구 근거의 시각이 어긋난다.
     * `kr.easydoc.application.conversion.LlmCallEntry.calledAt` 은 이 값을 그대로 옮긴다.
     */
    val calledAt: Instant,
)

package kr.easydoc.core.llm

import java.math.BigDecimal
import java.time.Instant

/**
 * `llm_calls.purpose` 가 담는 값 — 이 호출이 **왜** 나갔는지.
 *
 * [CONVERT] 는 문서 1차 변환, [REPAIR] 는 조건부 보정 패스, [RECONVERT] 는 문단 재변환
 * (`ReconvertUnitService`)의 1차·보정 호출을 **한 목적으로 묶은 값**이다 — 재변환은 표준
 * 문서 변환 경로가 아니라 별도 예산·과금 축이라, 그 안에서 보정이 있었는지는 이 열이
 * 구분하지 않는다(`ConvertDocumentUseCase.convert` KDoc 「purpose 매개변수」).
 */
enum class LlmCallPurpose(
    /** `llm_calls.purpose` 컬럼에 그대로 들어가는 값(V14 CHECK 제약과 같은 어휘). */
    val wireName: String,
) {
    CONVERT("convert"),
    REPAIR("repair"),
    RECONVERT("reconvert"),
}

/**
 * `llm_calls.outcome`(V18) 이 담는 값 — 이 호출이 **완성 응답을 받았는지**.
 *
 * [COMPLETED] 는 응답을 실제로 받은 호출이다(절단·빈 결과·거절처럼 파이프라인이 실패로
 * 분류한 완성도 포함 — `ConvertDocumentUseCase.Pass.complete` KDoc). [PROVIDER_ERROR] 는
 * `LlmProviderException` 으로 완성 자체가 나지 않은 호출이다 — 벤더가 실패한 요청의 입력
 * 토큰에 과금할 수 있어(계획 §6 리스크 1) 실제 사용량 없이도 원장에 남긴다(백로그
 * 「실패 호출 원장 추적」, 2026-09-08). 실패 호출은 토큰이 0이고 비용은 `null` 이다 —
 * 알 수 없는 사용량을 0으로 보고하지 않는다(CLAUDE.md 「미설정은 null」).
 *
 * **오늘 실제로 던져지는 것은 `LlmProviderException`(기반 클래스) 뿐이다.**
 * `LlmTruncatedException`·`LlmEmptyResultException`은 `OpenAiProvider`·`AnthropicProvider`
 * 어느 쪽도 던지지 않는다 — 두 어댑터는 실패를 전부 `LlmProviderException(message)`로
 * 던진다(`failure()` 헬퍼). 절단·빈 결과는 예외가 아니라 **받은 완성 응답**을
 * `ConvertDocumentUseCase`의 `classify(completion: LlmCompletion)`이 값(`finishReason`·
 * 빈 본문)으로 분류한 것이고, 이 갈래는 [PROVIDER_ERROR]가 아니라 [COMPLETED]로
 * 남는다(완성 자체는 받았다). `ConversionFailureKind.failureKind(exc)`의
 * `is LlmTruncatedException`·`is LlmEmptyResultException` 분기는 어댑터가 그 서브타입을
 * 던지기 시작하기 전까지는 도달하지 않는다 — 죽은 코드가 아니라 **아직 쓰이지 않는
 * 확장점**이다(새 provider가 세분화된 실패를 던지고 싶을 때 쓴다).
 */
enum class LlmCallOutcome(
    /** `llm_calls.outcome` 컬럼에 그대로 들어가는 값(V18 CHECK 제약과 같은 어휘). */
    val wireName: String,
) {
    COMPLETED("completed"),
    PROVIDER_ERROR("provider_error"),
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
    /**
     * **응답이 보고한** 모델 이름 — [outcome] 이 [LlmCallOutcome.PROVIDER_ERROR] 면 응답
     * 자체가 없었으므로 `null` 이다(`LlmAttribution.model` 과 같은 규약).
     */
    val model: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMs: Long?,
    val estimatedCostUsd: BigDecimal?,
    val pricingInputUsdPerMtok: BigDecimal?,
    val pricingOutputUsdPerMtok: BigDecimal?,
    /**
     * 이 호출이 처리한 **프롬프트 입력**의 문자 수 — 변환·보정은 문서 전체 본문, 재변환은
     * 그 단위 하나의 길이다(`ConvertDocumentUseCase.Pass` KDoc). **2026-09-07 정정:** PR
     * #58 로 개인정보 마스킹이 제거돼 이 값은 더 이상 마스킹된 입력이 아니라 평문 프롬프트
     * 입력 그대로의 길이다.
     */
    val charCount: Int,
    /**
     * 이 호출이 **실제로 일어난 시각** — provider 가 완성 응답을 돌려준 직후(또는
     * [LlmCallOutcome.PROVIDER_ERROR] 면 예외가 난 직후),
     * `ConvertDocumentUseCase.Pass.complete` 안에서 캡처한다. 한 변환이 호출 두 건(1차·보정)을
     * 쓰면 이 값도 두 건이 서로 다르고 시간순으로 증가한다.
     *
     * **저장 시점이 아니다.** `ProcessConversionJob`·`ReconvertUnitService` 는 LLM 호출이 끝난
     * 한참 뒤(트랜잭션 재진입·후처리·암호화를 거친 뒤)에야 원장을 쓰므로, 그 시점에 시계를
     * 다시 읽으면 실제 호출 시각이 아니라 저장 시각이 찍힌다 — 청구 근거의 시각이 어긋난다.
     * `kr.easydoc.application.conversion.LlmCallEntry.calledAt` 은 이 값을 그대로 옮긴다.
     */
    val calledAt: Instant,
    /**
     * 완성 응답을 받았는지(V18, 백로그 「실패 호출 원장 추적」 2026-09-08). 기본값
     * [LlmCallOutcome.COMPLETED] — 기존 호출부(성공 경로)는 이 필드를 몰라도 그대로
     * 컴파일된다. `ConvertDocumentUseCase.Pass.complete` 의 `catch (exc:
     * LlmProviderException)` 갈래만 [LlmCallOutcome.PROVIDER_ERROR] 로 명시한다.
     */
    val outcome: LlmCallOutcome = LlmCallOutcome.COMPLETED,
    /**
     * [outcome] 이 [LlmCallOutcome.PROVIDER_ERROR] 일 때만 값이 있다 — 예외 클래스의
     * 단순 이름이다. **오늘은 항상 `"LlmProviderException"`** 이다(위 [LlmCallOutcome]
     * KDoc 「오늘 실제로 던져지는 것은…」) — `LlmTruncatedException`·
     * `LlmEmptyResultException` 서브타입은 아직 어느 adapter 도 던지지 않는다. 그래도 값을
     * `exc.javaClass.simpleName`(호출부, `ConvertDocumentUseCase.Pass.recordFailure`)로
     * 그대로 읽어 두는 것은 새 provider 가 세분화된 서브타입을 던지기 시작하면 코드 변경
     * 없이 그 이름이 그대로 실리기 때문이다. **예외 메시지를 담지 않는다** — 벤더 응답
     * 문구가 새어 들 수 있다(CLAUDE.md 「예외 메시지를 로그에 남기지 않는다」와 같은 이유).
     */
    val failureClass: String? = null,
) {
    init {
        // outcome·model·failureClass 셋이 서로 어긋나면(예: PROVIDER_ERROR 인데 model 이
        // 있음) 원장이 「완성 자체가 없었다」와 「응답을 알고 있다」를 동시에 주장하는
        // 모순된 행을 쓴다 — 값 자체는 새지 않게 메시지에서 뺀다(CLAUDE.md 규약).
        require((outcome == LlmCallOutcome.COMPLETED) == (model != null)) {
            "model 은 outcome=COMPLETED 일 때만 있어야 한다"
        }
        require((outcome == LlmCallOutcome.PROVIDER_ERROR) == (failureClass != null)) {
            "failureClass 는 outcome=PROVIDER_ERROR 일 때만 있어야 한다"
        }
    }
}

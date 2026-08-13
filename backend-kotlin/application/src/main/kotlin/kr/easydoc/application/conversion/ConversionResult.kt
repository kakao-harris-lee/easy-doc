package kr.easydoc.application.conversion

import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.privacy.ModelDraft

/**
 * 변환이 실패한 종류.
 *
 * 요구사항 인벤토리 §3.1 (라) 가 "최소 셋을 구분한다"고 못박은 것이다. 구분이 필요한 이유는
 * **사용자가 취할 조치와 운영 집계가 다르기** 때문이다 — 절단은 문서를 나눠 올리면 풀리고,
 * 호출 실패는 다시 시도하면 풀리며, 빈 결과는 우리 쪽 버그 후보다.
 *
 * ## 이 이름들은 계약의 `failure_code` 가 **아니다**
 *
 * 계약(`easy-doc-v1.yaml::ConversionResponse.failure_code`)은 값을 열거하지 않고
 * "예외 클래스명"이라는 **구현을 되짚는 규칙**을 준다. 그 규칙을 따르면 Kotlin 이 Python
 * 클래스 이름(`LLMTruncatedError` 등)을 베껴야 하고, Python 을 지우면 규칙이 가리킬 대상이
 * 없어진다(`02_parity-verifier_conversion-spec.md` §6 갈림 후보 ②, `contract-keeper` 회부).
 *
 * 그래서 여기서는 **요구 수준의 이름**을 쓴다. 계약이 값을 열거하면 그 이름으로 옮기고,
 * 그때 바뀌는 것은 이 enum 과 HTTP 매핑뿐이다 — 판정 로직은 이름을 모른다.
 */
enum class ConversionFailureKind {
    /** 출력 상한에서 응답이 잘렸다. 잘린 본문을 성공으로 넘기면 **조용한 정보 누락**이 된다. */
    TRUNCATED,

    /** 후처리 뒤 본문이 남지 않았다. 빈 응답이거나 껍데기(코드펜스)만 온 경우다. */
    EMPTY_RESULT,

    /** provider 계층이 실패했다(전송·서버 오류 등). */
    PROVIDER_ERROR,
}

/**
 * 변환 1건이 쓴 자원.
 *
 * 세 수를 한 묶음으로 들고 다니는 이유는 **성공·실패 어느 쪽에서도 같이 보고돼야** 하기
 * 때문이다. 실패했다고 호출을 안 한 것이 아니고, 비용은 이미 발생했다.
 *
 * @property llmCalls **완성 요청** 수. 전송 시도가 아니다 — 둘을 합쳐 세면 상한이 어댑터의
 *   재시도 설정에 따라 흔들리고, 모델에게 실제로 몇 번 물었는지도 잃는다(인벤토리 §3.1 (가) 4).
 *   전송 시도 수는 어댑터가 센다. 이 계층은 그 수를 알지 못하고, 알 필요도 없다.
 * @property inputTokens 두 호출의 **합**. 보정을 채택하지 않았어도 뺀다면 원가가 실제보다
 *   적게 잡힌다(인벤토리 §3.1 (다)).
 * @property outputTokens 같다.
 */
data class ConversionUsage(
    val llmCalls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
)

/**
 * 변환 유스케이스의 결과.
 *
 * `sealed` 인 이유: 실패한 변환에 `easyText` 가 있을 수 없고 성공한 변환에 `failureKind` 가
 * 있을 수 없다. 한 클래스에 nullable 필드로 담으면 "성공인데 본문이 null" 같은 상태가
 * 표현 가능해지고, 그 상태를 만들지 않는 것이 호출부의 규율이 된다 — 타입이 할 일이다.
 */
sealed interface ConversionResult {
    /** 성공·실패 어느 쪽이든 보고한다. */
    val usage: ConversionUsage

    /**
     * 변환 성공.
     *
     * `data class` 가 아니다. [easyText] 는 문서 본문이고 [maskedItems] 는 원문 개인정보를
     * (감싼 채로) 들고 있어, 기본 `toString()` 이 로그 한 줄로 둘 다 흘린다 —
     * `LlmPrompt`·`LlmCompletion`·`PlaceholderRestoration` 이 같은 이유로 받은 처리와 같다.
     *
     * @property easyText 최종 채택된 본문. [ModelDraft] 인 것은 **사람 검수를 거치지 않았다**는
     *   선언이다 — 내보내기 경로가 이 값에 개인정보를 복원해 꽂지 않게 하는 근거다
     *   (`Masking.kt::restoreForExport`).
     * @property repaired 보정문을 채택했는가. 보정을 부르지 않았거나 기각했으면 거짓이다.
     * @property missingPlaceholders **채택된 최종 본문**에 남아 있지 않은 자리표시자 라벨.
     *   비어 있지 않아도 변환은 성공이다 — 막지 않고 검수 화면 경고로 넘긴다(인벤토리 §3.1 (마)).
     * @property maskedItems 자리표시자 ↔ 원문 대응. **API 응답으로 그대로 내보내지 않는다.**
     */
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

    /**
     * 변환 실패. 사용자에게 줄 본문이 없다.
     *
     * 실패해도 [usage] 는 채워진다 — 1차 호출에서 실패했어도 그 호출의 비용은 발생했다.
     *
     * `data class` 로 두어도 안전하다. [kind] 는 enum 이고 [usage] 는 수뿐이라 본문·개인정보가
     * 담기지 않는다. 실패 사유에 **문서 본문·모델 응답·파일명을 담지 않는다**는 것이 요구다
     * (인벤토리 §3.1 (라), INV-04).
     */
    data class Failed(
        val kind: ConversionFailureKind,
        override val usage: ConversionUsage,
    ) : ConversionResult
}

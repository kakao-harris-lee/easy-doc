package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.llm.LlmCallPurpose
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **원장 어휘의 전수 대조** — `llm_calls.purpose` 와 `credit_transactions.reason` 은 사용량·이용량
 * 조회 응답에 그대로 나간다. 새 목적·새 사유를 코드에만 더하면 계약이 선언하지 않은 값이 응답에
 * 실리고, 그 값을 모르는 클라이언트는 조용히 빈 칸으로 그린다.
 *
 * 값 목록을 이 파일에 다시 적지 않는다 — Kotlin enum 의 `wireName` 이 정본이고 계약이 그것과
 * 같은지만 본다. 그래서 목적·사유가 늘면 **자동으로** 빨개진다(R7 ER-17 의
 * `illustration_suggestion` 이 계약에 없던 상태가 그랬다).
 */
class LedgerVocabularyContractTest {
    @Test
    @DisplayName("계약 `UsagePurpose` 가 `LlmCallPurpose` 의 wire 값과 정확히 같다")
    fun `사용량 목적 어휘가 코드와 같다`() {
        val declared = ContractSpec.schemaEnum(USAGE_PURPOSE_SCHEMA)

        assertThat(declared)
            .withFailMessage {
                "계약과 코드의 호출 목적이 갈렸다:\n" +
                    "  코드에만 있다: ${LlmCallPurpose.entries.map { it.wireName } - declared.toSet()}\n" +
                    "  계약에만 있다: ${declared - LlmCallPurpose.entries.map { it.wireName }.toSet()}\n" +
                    "  코드에만 있으면 계약이 모르는 값이 사용량 응답에 실린다."
            }.containsExactlyInAnyOrderElementsOf(LlmCallPurpose.entries.map { it.wireName })
    }

    @Test
    @DisplayName("계약 `CreditReason` 이 `CreditReason` 의 wire 값과 정확히 같다")
    fun `이용량 사유 어휘가 코드와 같다`() {
        val declared = ContractSpec.schemaEnum(CREDIT_REASON_SCHEMA)

        assertThat(declared)
            .withFailMessage {
                "계약과 코드의 이용량 사유가 갈렸다:\n" +
                    "  코드에만 있다: ${CreditReason.entries.map { it.wireName } - declared.toSet()}\n" +
                    "  계약에만 있다: ${declared - CreditReason.entries.map { it.wireName }.toSet()}\n" +
                    "  코드에만 있으면 계약이 모르는 값이 이용량 거래 목록에 실린다."
            }.containsExactlyInAnyOrderElementsOf(CreditReason.entries.map { it.wireName })
    }

    @Test
    @DisplayName("R7 ER-17 어휘가 양쪽에 있다 — 대조가 빈 집합을 훑고 통과하지 않는다")
    fun `그림 제안 어휘가 양쪽에 있다`() {
        assertThat(ContractSpec.schemaEnum(USAGE_PURPOSE_SCHEMA)).contains(SUGGESTION_WIRE_NAME)
        assertThat(ContractSpec.schemaEnum(CREDIT_REASON_SCHEMA)).contains(SUGGESTION_WIRE_NAME)
        assertThat(LlmCallPurpose.ILLUSTRATION_SUGGESTION.wireName).isEqualTo(SUGGESTION_WIRE_NAME)
        assertThat(CreditReason.ILLUSTRATION_SUGGESTION.wireName).isEqualTo(SUGGESTION_WIRE_NAME)
    }

    private companion object {
        const val USAGE_PURPOSE_SCHEMA = "UsagePurpose"
        const val CREDIT_REASON_SCHEMA = "CreditReason"
        const val SUGGESTION_WIRE_NAME = "illustration_suggestion"
    }
}

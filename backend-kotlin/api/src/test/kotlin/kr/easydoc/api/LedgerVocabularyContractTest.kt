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
 * Kotlin enum 의 `wireName` 이 정본이다. 아직 작업·계약에 연결하지 않은 provider 전용 목적만
 * 명시적으로 제외하므로 새 공개 목적·사유가 계약에 없으면 **자동으로** 빨개진다. 그림 생성도
 * 후속 작업에서 공개 응답에 연결할 때 provider 전용 예외를 제거하고 계약을 함께 확장해야 한다.
 */
class LedgerVocabularyContractTest {
    @Test
    @DisplayName("계약 `UsagePurpose` 가 공개된 `LlmCallPurpose` 의 wire 값과 정확히 같다")
    fun `사용량 목적 어휘가 코드와 같다`() {
        val declared = ContractSpec.schemaEnum(USAGE_PURPOSE_SCHEMA)
        val exposed = LlmCallPurpose.entries.map { it.wireName } - PROVIDER_ONLY_PURPOSES

        assertThat(declared)
            .withFailMessage {
                "계약과 코드의 공개 호출 목적이 갈렸다:\n" +
                    "  코드에만 있다: ${exposed - declared.toSet()}\n" +
                    "  계약에만 있다: ${declared - exposed.toSet()}\n" +
                    "  코드에만 있으면 계약이 모르는 값이 사용량 응답에 실린다."
            }.containsExactlyInAnyOrderElementsOf(exposed)
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

    @Test
    fun `그림 생성 목적은 작업과 계약을 연결하기 전까지 공개 사용량 어휘에서 제외한다`() {
        assertThat(PROVIDER_ONLY_PURPOSES).containsExactly(GENERATION_WIRE_NAME)
        assertThat(LlmCallPurpose.ILLUSTRATION_GENERATION.wireName).isEqualTo(GENERATION_WIRE_NAME)
        assertThat(ContractSpec.schemaEnum(USAGE_PURPOSE_SCHEMA)).doesNotContain(GENERATION_WIRE_NAME)
    }

    private companion object {
        const val USAGE_PURPOSE_SCHEMA = "UsagePurpose"
        const val CREDIT_REASON_SCHEMA = "CreditReason"
        const val SUGGESTION_WIRE_NAME = "illustration_suggestion"
        const val GENERATION_WIRE_NAME = "illustration_generation"
        val PROVIDER_ONLY_PURPOSES = setOf(GENERATION_WIRE_NAME)
    }
}

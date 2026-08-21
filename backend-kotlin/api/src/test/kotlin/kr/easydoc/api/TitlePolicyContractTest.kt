package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.core.document.FALLBACK_TITLE
import kr.easydoc.core.document.MAX_TITLE_LENGTH
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 제목 정책이 계약에서 나온다 — 계약 `x-title-policy`(2026-08-20 신설). */
class TitlePolicyContractTest {
    @Test
    @DisplayName("`FALLBACK_TITLE` 이 계약 `x-title-policy.fallback_title` 과 같다 — 문구를 코드에 복제하지 않는다")
    fun `대체 제목 문구가 계약에서 나온다`() {
        val fromContract = ContractSpec.text(*FALLBACK_TITLE_PATH)

        assertThat(fromContract)
            .withFailMessage("계약의 대체 제목 문구가 비었다 — 빈 기대값과의 대조는 통과가 아니라 미검사다.")
            .isNotBlank()
        assertThat(FALLBACK_TITLE)
            .withFailMessage {
                "대체 제목 문구가 계약과 다르다.\n" +
                    "  계약(${ContractSpec.file.name}): \"$fromContract\"\n" +
                    "  코드(FALLBACK_TITLE): \"$FALLBACK_TITLE\"\n" +
                    "  이 문구는 목록 화면에 그대로 보인다 — 갈리면 사용자가 보는 값이 계약 밖이 된다."
            }.isEqualTo(fromContract)
    }

    @Test
    @DisplayName("`MAX_TITLE_LENGTH` 가 계약 `x-input-limits.max_title_length` 와 같다")
    fun `제목 상한이 계약에서 나온다`() {
        assertThat(MAX_TITLE_LENGTH).isEqualTo(ContractSpec.inputLimit("max_title_length"))
    }

    @Test
    @DisplayName("제목의 바탕은 계약이 열거한 것뿐이다 — 계약이 `given_title` 하나만 허용한다")
    fun `허용 출처가 하나뿐이다`() {
        val sources = ContractSpec.strings(*SOURCES_PATH)

        assertThat(sources)
            .withFailMessage("계약의 허용 출처 목록이 비었다 — 0건 대조는 통과가 아니라 미검사다.")
            .isNotEmpty()
        assertThat(sources)
            .withFailMessage {
                "계약이 허용하는 제목 출처가 늘었다: $sources\n" +
                    "  구현은 「사용자가 적어 준 제목」 하나만 쓴다(`resolveTitle(given: String?)`).\n" +
                    "  출처가 늘었다면 구현과 이 파일을 함께 고쳐야 한다 — 계약만 늘면 조용히 갈린다."
            }.containsExactly(GIVEN_TITLE)
    }

    @Test
    @DisplayName("금지 출처마다 **그것을 실제로 재는 탐지기**가 있다 — 계약에 금지가 늘면 여기서 드러난다")
    fun `금지 출처 목록이 실행 축과 짝을 이룬다`() {
        val forbidden = ContractSpec.strings(*FORBIDDEN_SOURCES_PATH)

        assertThat(forbidden)
            .withFailMessage("계약의 금지 출처 목록이 비었다 — 0건 대조는 통과가 아니라 미검사다.")
            .isNotEmpty()
        assertThat(forbidden)
            .withFailMessage {
                "계약이 금지한 제목 출처 중 **실행으로 되짚는 축이 없는 것**이 있다.\n" +
                    "  계약: $forbidden\n" +
                    "  짝지어진 탐지기: $DETECTORS_BY_FORBIDDEN_SOURCE\n" +
                    "  금지를 계약에만 적으면 그것은 문장이지 게이트가 아니다 — " +
                    "`JdbcDocumentStoreTest` 에 대응 표식 탐지기를 세우고 여기 등재하라."
            }.containsExactlyInAnyOrderElementsOf(DETECTORS_BY_FORBIDDEN_SOURCE.keys)
    }

    @Test
    @DisplayName("계약 경로가 없으면 **실패한다** — 조항이 사라져도 통과하는 형태를 배제한다")
    fun `계약 경로가 없으면 실패한다`() {
        assertThatThrownBy { ContractSpec.text("x-title-policy", "no_such_key_for_negative_control") }
            .hasMessageContaining("계약에 없는 경로다")
    }

    private companion object {
        val FALLBACK_TITLE_PATH = arrayOf("x-title-policy", "fallback_title")
        val SOURCES_PATH = arrayOf("x-title-policy", "sources")
        val FORBIDDEN_SOURCES_PATH = arrayOf("x-title-policy", "forbidden_sources")

        const val GIVEN_TITLE = "given_title"

        /** 계약의 금지 출처 → 그것이 실제로 새지 않는지 재는 테스트. */
        val DETECTORS_BY_FORBIDDEN_SOURCE =
            mapOf(
                "body_text" to "JdbcDocumentStoreTest.본문 표식이 평문 열에 남지 않는다",
                "upload_filename" to "JdbcDocumentStoreTest.파일 이름 표식이 평문 열에 남지 않는다",
            )
    }
}

package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.core.document.SourceFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 업로드 지원 형식 집합이 계약에서 나온다 — 계획 §5 D-13 의 P-26. */
class UploadFormatContractTest {
    @Test
    @DisplayName("`SourceFormat.UPLOAD_FORMATS` 가 계약 `x-input-limits.supported_upload_formats` 와 같다")
    fun `업로드 형식 집합이 계약에서 나온다`() {
        val fromContract = ContractSpec.list(*SUPPORTED_UPLOAD_FORMATS_PATH).map { it.toString() }

        assertThat(fromContract)
            .withFailMessage("계약의 지원 형식 목록이 비었다 — 0건 대조는 통과가 아니라 미검사다.")
            .isNotEmpty()
        assertThat(SourceFormat.UPLOAD_FORMATS.map { it.wireName })
            .withFailMessage {
                "코드의 업로드 형식 집합이 계약과 다르다.\n" +
                    "  계약(${ContractSpec.file.name}): $fromContract\n" +
                    "  코드(SourceFormat.UPLOAD_FORMATS): ${SourceFormat.UPLOAD_FORMATS.map { it.wireName }}\n" +
                    "  계약이 늘었는데 코드가 안 늘면 새 형식은 **검사 자체를 받지 않는다.**"
            }.isEqualTo(fromContract)
    }

    @Test
    @DisplayName("붙여넣기 형식(`text`)은 업로드 집합에 없다 — 계약이 파일 형식만 열거한다")
    fun `붙여넣기 형식은 업로드 집합 밖이다`() {
        val fromContract = ContractSpec.list(*SUPPORTED_UPLOAD_FORMATS_PATH).map { it.toString() }

        assertThat(fromContract).doesNotContain(SourceFormat.TEXT.wireName)
        assertThat(SourceFormat.UPLOAD_FORMATS).doesNotContain(SourceFormat.TEXT)
    }

    @Test
    @DisplayName("계약 경로가 없으면 **실패한다** — 조항이 사라져도 통과하는 형태를 배제한다")
    fun `계약 경로가 없으면 실패한다`() {
        assertThatThrownBy { ContractSpec.list("x-input-limits", "no_such_key_for_negative_control") }
            .hasMessageContaining("계약에 없는 경로다")
    }

    private companion object {
        val SUPPORTED_UPLOAD_FORMATS_PATH = arrayOf("x-input-limits", "supported_upload_formats")
    }
}

package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.core.document.SourceFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **업로드 지원 형식 집합이 계약에서 나온다** — 계획 §5 D-13 의 P-26.
 *
 * ## 왜 이 파일이 생겼나 (게이트 27 M-1)
 *
 * `SourceFormat` KDoc 과 `DocumentExtractorsTest` 의 `@DisplayName` 은 *"계약과 갈리면
 * 빨개진다"* 고 적었는데, 실제 그 테스트는 **계약 파일을 읽지 않았다** — 손으로 복사한
 * 리터럴(`"docx", "pdf", "hwpx"`)과 대조할 뿐이었다. 저장소 전수 grep 에서
 * `supported_upload_formats` 를 읽는 코드·테스트가 **0건**이었다. 즉 계약에 형식이 하나
 * 늘어도 아무것도 빨개지지 않는데, 코드는 있는 줄 아는 가드를 광고하고 있었다.
 *
 * 계획이 요구한 장치가 「개선 백로그」로 재분류돼 있던 것도 함께 되돌린다 — 미구축을
 * 선택적 개선으로 옮기면 그 미구축은 영영 닫히지 않는다.
 *
 * ## 왜 `infrastructure` 가 아니라 `api` 인가
 *
 * 계약 파일을 읽는 지원 코드([ContractSpec])가 이 모듈의 테스트에만 있다. 형식 집합을 든
 * `SourceFormat` 은 `core` 라 여기서도 보인다. 같은 기능을 `infrastructure` 에 다시 만들면
 * YAML 파서 배선이 둘이 되고, 둘은 반드시 갈린다.
 *
 * ## 음성 대조에 대해 정직하게
 *
 * 계획의 **N-26**("계약에서 원소를 빼면 검사가 줄어야 한다")은 **실행하지 않았다** —
 * 그러려면 계약 파일을 고쳐야 하는데 이 레인은 계약 파일을 고치지 않는다. 대신 그 장치의
 * 기제(계약을 실제로 읽고, 없으면 **실패한다**)를 [`계약 경로가 없으면 실패한다`] 가
 * 실행으로 확인한다. 그 케이스가 초록이면 이 파일의 기대값은 확실히 계약 파일에서 온 것이다.
 */
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
        // 이 케이스가 재는 것은 기대값의 **출처**다. 없는 경로가 조용히 빈 목록이나 기본값을
        // 돌려주면 위 두 케이스는 계약과 무관하게 통과할 수 있다.
        assertThatThrownBy { ContractSpec.list("x-input-limits", "no_such_key_for_negative_control") }
            .hasMessageContaining("계약에 없는 경로다")
    }

    private companion object {
        val SUPPORTED_UPLOAD_FORMATS_PATH = arrayOf("x-input-limits", "supported_upload_formats")
    }
}

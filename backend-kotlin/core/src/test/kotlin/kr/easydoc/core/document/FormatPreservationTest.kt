package kr.easydoc.core.document

import kr.easydoc.core.easyread.exportContentLines
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 서식 유지 판정 — **개수만으로** 서는 규칙과, 그 문구가 본문을 담지 않는다는 규칙. */
class FormatPreservationTest {
    @Test
    @DisplayName("본문 단위와 문단 수가 정확히 같고 머리말이 없으면 `available` 이다")
    fun `짝이 정확히 맞으면 유지 가능이다`() {
        val judged =
            reflectedPreservation(ReflectionOutcome(headerFooterUnits = 0, emptiedUnits = 0, appendedLines = 0))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(judged.details).isEmpty()
    }

    @Test
    @DisplayName("머리말·꼬리말이 있으면 그 문구가 원본으로 남으므로 `available` 이 아니다")
    fun `머리말이 있으면 일부 유지다`() {
        val judged =
            reflectedPreservation(ReflectionOutcome(headerFooterUnits = 2, emptiedUnits = 0, appendedLines = 0))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details).containsExactly("머리말·꼬리말 2곳은 원본 문구를 그대로 둡니다.")
    }

    @Test
    @DisplayName("검수본 문단이 모자라면 남은 원본 문단을 비운다고 말한다")
    fun `문단이 모자라면 비운다고 말한다`() {
        val judged =
            reflectedPreservation(ReflectionOutcome(headerFooterUnits = 0, emptiedUnits = 3, appendedLines = 0))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "원본 문단 3개는 반영할 내용이 없어 빈 문단으로 남습니다.",
                "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다.",
            )
    }

    @Test
    @DisplayName("검수본 문단이 남으면 본문 끝에 덧붙는다고 말한다 — 버리지 않는다")
    fun `문단이 남으면 덧붙인다고 말한다`() {
        val judged =
            reflectedPreservation(ReflectionOutcome(headerFooterUnits = 1, emptiedUnits = 0, appendedLines = 3))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "머리말·꼬리말 1곳은 원본 문구를 그대로 둡니다.",
                "문단 3개는 원본에 자리가 없어 본문 끝에 덧붙습니다.",
                "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다.",
            )
    }

    @Test
    @DisplayName("판정 문구는 **개수만** 담는다 — 어떤 갈래도 문서 본문 조각을 담을 수 없다")
    fun `판정 문구가 본문을 담지 않는다`() {
        val judgments =
            listOf(
                noOriginalPreservation(),
                unreadableOriginalPreservation(),
                reflectedPreservation(ReflectionOutcome(0, 0, 0)),
                reflectedPreservation(ReflectionOutcome(2, 0, 6)),
                reflectedPreservation(ReflectionOutcome(1, 8, 0)),
            )

        assertThat(judgments.flatMap { it.details })
            .allSatisfy { detail ->
                assertThat(detail)
                    .withFailMessage("판정 문구가 숫자와 고정 문장 밖의 값을 담았다: %s", detail)
                    .matches("""[가-힣·, ]*\d*[가-힣·, ]*(\d+[가-힣]+[가-힣·, ]*)*\.""")
            }
    }

    @Test
    @DisplayName("`toString` 은 상태와 개수만 남긴다 — 항목 문구는 로그에 넣지 않는다")
    fun `문자열 표현이 항목을 감춘다`() {
        val judged =
            reflectedPreservation(ReflectionOutcome(headerFooterUnits = 1, emptiedUnits = 0, appendedLines = 3))

        assertThat(judged.toString()).isEqualTo("FormatPreservation(partial, 항목 3건)")
        judged.details.forEach { assertThat(judged.toString()).doesNotContain(it) }
    }

    @Test
    @DisplayName("열 수 없는 원본은 `failed` 이고 사유를 항목으로 준다")
    fun `열 수 없는 원본은 실패다`() {
        val judged = unreadableOriginalPreservation()

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.FAILED)
        assertThat(judged.details).containsExactly("원본 파일을 열 수 없어 같은 형식으로 다시 만들 수 없습니다.")
    }

    @Test
    @DisplayName("판정이 세는 문단과 내보내기가 쓰는 문단은 같은 함수에서 나온다 — 빈 줄은 세지 않는다")
    fun `빈 줄은 문단이 아니다`() {
        val body = "첫 문단\n\n둘째 문단\n   \n셋째 문단\n"

        assertThat(exportContentLines(body))
            .describedAs("빈 줄을 문단으로 세면 원본과 짝이 맞는 문서가 `partial` 로 떨어진다")
            .containsExactly("첫 문단", "둘째 문단", "셋째 문단")
    }
}

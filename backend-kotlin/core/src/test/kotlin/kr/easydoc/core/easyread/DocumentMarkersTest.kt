package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentMarkersTest {
    @Test
    fun `제보 원문의 표식을 문장 안 안내까지 순서대로 추출한다`() {
        val source =
            """
            ○ 대상자 선정 및 선발과정
            - 1차 심사 발표: 2025. 3. 19.(수) ※1차 통과자에 한하여 개별 안내 예정
            - 2차 서류 제출: 2025. 3. 19.(수) ~ 2025. 3. 28.(금) 18:00
             * 접수 전화면접: 2025. 3. 20.(목) ~ 4. 4.(금)
            - 최종 결과 발표: 2024. 4. 11.(금) 예정
             ※지원 여부(선정/탈락) 개별 안내
            """.trimIndent()
        assertThat(documentMarkers(source)).containsExactly("○", "-", "※", "-", "*", "-", "※")
    }

    @Test
    fun `줄을 나눈 주의사항과 바뀐 제목 문장은 허용한다`() {
        assertThat(hasMarkerChanges("○ 선정 과정\n- 결과 발표 ※통과자 안내", "○ 뽑는 과정\n- 결과를 알립니다.\n※ 통과한 사람에게 알립니다."))
            .isFalse()
    }

    @Test
    fun `표식 누락 교체 재정렬 추가를 모두 검출한다`() {
        val source = "○ 안내\n- 결과 발표\n * 전화 면접\n※ 통과자 안내"
        val drafts =
            listOf(
                "안내\n- 결과 발표\n * 전화 면접\n※ 통과자 안내",
                source.replace("*", "-"),
                "○ 안내\n * 전화 면접\n- 결과 발표\n※ 통과자 안내",
                "$source\n- 설명",
            )
        for (draft in drafts) assertThat(hasMarkerChanges(source, draft)).isTrue()
    }

    @Test
    fun `같은 표식이 여러 번 나오면 개수도 검사한다`() {
        assertThat(hasMarkerChanges("- 접수\n- 발표", "- 접수와 발표")).isTrue()
    }

    @Test
    fun `날짜 음수 범위 곱셈 표의 빈 값과 본문 별표는 항목이 아니다`() {
        val text = "2025. 3. 19.\n3. 19.(수)\n-10도\n-\n * \n3 * 4\n전화 02-1234-5678\n9~24세\n**강조**\n끝. 다음입니다."
        assertThat(documentMarkers(text)).isEmpty()
    }

    @Test
    fun `번호와 한글 항목의 원문 표기를 구분한다`() {
        assertThat(documentMarkers("① 준비\n② 신청\n1) 치료\n2. 상담\n가. 대상\n나) 방법"))
            .containsExactly("①", "②", "1)", "2.", "가.", "나)")
        assertThat(hasMarkerChanges("① 준비\n1) 신청", "1. 준비\n2) 신청")).isTrue()
    }

    @Test
    fun `표식이 없는 원문은 새 목록을 작성할 수 있다`() {
        assertThat(hasMarkerChanges("사과와 배를 가져오세요.", "- 사과\n- 배")).isFalse()
    }

    @Test
    fun `문장 후처리에서도 원문 표식을 지우지 않는다`() {
        val text = "○ 결과\n- 발표\n * 면접\n※ 통과자 안내"
        assertThat(postprocess("```text\n$text\n```")).isEqualTo(text)
    }

    @Test
    fun `줄바꿈으로 갈라진 ※ 안내는 잡음 줄 정리 뒤에도 표식 변화로 보지 않는다`() {
        val source = "※ 안내"
        val draft = "※\n안내"
        assertThat(hasMarkerChanges(source, cleanEasyText(draft))).isFalse()
    }
}

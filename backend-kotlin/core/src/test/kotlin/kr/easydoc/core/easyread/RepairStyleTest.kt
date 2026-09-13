package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RepairStyleTest {
    @Test
    fun `뜻풀이를 그대로 끼워 넣은 비문은 자동 보정한다`() {
        assertThat(checkRepairStyle("선발 결과를 알립니다.", "뽑음 결과를 알려 드립니다.").issues)
            .extracting("kind")
            .containsExactly(StyleRuleKind.GLOSS_COLLISION)
    }

    @Test
    fun `길이와 나열 및 낱말 지적만으로 문서 전체를 다시 쓰지 않는다`() {
        val draft = "지원 대상자로 뽑힌 뒤 정당한 이유 없이 2개월 이상 연속으로 서비스를 이용하지 않으면 지원을 멈출 수 있습니다.\n금일 서류, 사진, 도장, 봉투를 가져오세요."
        assertThat(checkStyle(draft).issues)
            .extracting("kind")
            .contains(StyleRuleKind.LENGTH, StyleRuleKind.COMMA, StyleRuleKind.DIFFICULT_WORD)
        assertThat(checkRepairStyle(draft, draft).issues).isEmpty()
        assertThat(checkRepairStyle(draft, draft).totalSentences).isEqualTo(2)
    }

    @Test
    fun `원문 그대로인 파일 이름을 바꾸라는 보정은 요청하지 않는다`() {
        val draft = "도서대출 신청(변경)서.hwp는 도서대출을 신청하는 서류입니다."
        assertThat(checkStyle(draft).issues).isNotEmpty()
        listOf("- 도서대출 신청(변경)서.hwp", "  • 도서대출 신청(변경)서.hwp").forEach { source ->
            assertThat(checkRepairStyle(source, draft).issues).isEmpty()
        }
    }

    @Test
    fun `보존한 이름 바깥의 이중 피동은 같은 문장에도 보정한다`() {
        val name = "도서대출 신청(변경)서.hwp"
        assertThat(checkRepairStyle(name, "$name 내용이 보여지고 있습니다.").issues)
            .extracting("kind")
            .containsExactly(StyleRuleKind.DOUBLE_PASSIVE)
    }

    @Test
    fun `길이 진단은 유지하되 긴 파일 이름과 설명도 자동 보정하지 않는다`() {
        val name = "(서식1-1호) 도서관의 국민행복카드 상담전화를 위한 개인정보 제공과 이용 동의서.hwp"
        assertThat(checkRepairStyle(name, "$name\n동의하는 내용을 적는 서류입니다.").issues).isEmpty()
        val draft = "$name 파일에 " + "필요한 내용을 모두 확인하고 ".repeat(5)
        assertThat(checkStyle(draft).issues)
            .extracting("kind")
            .contains(StyleRuleKind.LENGTH)
        assertThat(checkRepairStyle(name, draft).issues).isEmpty()
    }
}

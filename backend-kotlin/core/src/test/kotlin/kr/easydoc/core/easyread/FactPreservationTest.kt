package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 사실 보존 기계 검사 — backlog §1.3. `findMissingFacts` 는 결정적이고 보수적이어야 한다. */
class FactPreservationTest {
    @Test
    @DisplayName("숫자 — 천 단위 구분자가 있어도 같은 값이면 보존으로 본다")
    fun `숫자 구분자를 무시하고 비교한다`() {
        val source = "참가자는 10,000명입니다."
        val kept = "참가자 수는 10000명이에요."
        val dropped = "많은 사람이 참여했습니다."

        assertThat(findMissingFacts(source, kept)).isEmpty()
        assertThat(findMissingFacts(source, dropped))
            .extracting("kind")
            .containsExactly(FactKind.NUMBER)
    }

    @Test
    @DisplayName("전화번호 — 그대로 남으면 보존, 지워지면 누락")
    fun `전화번호 보존을 본다`() {
        val source = "02-1234-5678로 문의하세요."
        val kept = "문의는 02-1234-5678로 하세요."
        val dropped = "문의하세요."

        assertThat(findMissingFacts(source, kept)).isEmpty()
        assertThat(findMissingFacts(source, dropped))
            .extracting("kind")
            .containsExactly(FactKind.PHONE)
    }

    @Test
    @DisplayName("시각 — '오후 2시' 같은 표기가 남았는지 본다")
    fun `시각 보존을 본다`() {
        val source = "오후 2시에 방문하세요."
        val kept = "방문 시간은 오후 2시입니다."
        val dropped = "방문하세요."

        assertThat(findMissingFacts(source, kept)).isEmpty()
        assertThat(findMissingFacts(source, dropped))
            .extracting("kind")
            .containsExactly(FactKind.TIME)
    }

    @Test
    @DisplayName("날짜 — 'N월 N일' 표기가 남았는지 본다")
    fun `날짜 보존을 본다`() {
        val source = "9월 4일까지 접수합니다."
        val kept = "접수 마감은 9월 4일입니다."
        val dropped = "접수합니다."

        assertThat(findMissingFacts(source, kept)).isEmpty()
        assertThat(findMissingFacts(source, dropped))
            .extracting("kind")
            .containsExactly(FactKind.DATE)
    }

    @Test
    @DisplayName("금액 — 10,000원과 1만 원은 같은 사실로 본다")
    fun `금액 표기가 달라도 같은 값이면 보존으로 본다`() {
        val source = "10,000원을 냅니다."
        val keptDifferentForm = "1만 원을 내세요."
        val dropped = "돈을 냅니다."

        assertThat(findMissingFacts(source, keptDifferentForm)).isEmpty()
        assertThat(findMissingFacts(source, dropped))
            .extracting("kind")
            .containsExactly(FactKind.AMOUNT)
    }

    @Test
    @DisplayName("백분율 — 공백 유무와 무관하게 같은 값이면 보존으로 본다")
    fun `백분율 보존을 본다`() {
        val source = "50% 할인됩니다."
        val kept = "50 % 를 깎아 드립니다."
        val dropped = "할인됩니다."

        assertThat(findMissingFacts(source, kept)).isEmpty()
        assertThat(findMissingFacts(source, dropped))
            .extracting("kind")
            .containsExactly(FactKind.PERCENT)
    }

    @Test
    @DisplayName("마스킹 자리표시자는 사실이 아니다 — 스킵한다")
    fun `자리표시자를 사실로 세지 않는다`() {
        val source = "[[주민등록번호1]]을 확인하세요."
        val dropped = "확인하세요."

        assertThat(findMissingFacts(source, dropped)).isEmpty()
    }

    @Test
    @DisplayName("단위 없는 한 자리 숫자는 사실로 세지 않는다")
    fun `단위 없는 한 자리 숫자는 무시한다`() {
        val source = "가 3 있습니다."
        val dropped = "있습니다."

        assertThat(findMissingFacts(source, dropped)).isEmpty()
    }

    @Test
    @DisplayName("전각 숫자는 반각으로 정규화해 비교한다")
    fun `전각 숫자를 정규화한다`() {
        val source = "３명이 신청했습니다."
        val kept = "3명이 신청했어요."

        assertThat(findMissingFacts(source, kept)).isEmpty()
    }

    @Test
    @DisplayName("원문 사실이 모두 남아 있으면 빈 목록이다")
    fun `모두 보존되면 빈 목록이다`() {
        val source = "9월 4일 오후 2시까지 02-1234-5678로 신청하세요. 참가비는 10,000원입니다."
        val draft =
            "신청 기간은 9월 4일까지이고, 시간은 오후 2시까지입니다. " +
                "문의는 02-1234-5678로 하세요. 참가비는 10000원이에요."

        assertThat(findMissingFacts(source, draft)).isEmpty()
    }

    @Test
    @DisplayName("여러 종류가 함께 빠지면 각각 보고한다")
    fun `여러 종류를 함께 보고한다`() {
        val source = "9월 4일까지 10,000원을 내고 02-1234-5678로 문의하세요."
        val draft = "돈을 내고 문의하세요."

        val missing = findMissingFacts(source, draft)

        assertThat(missing)
            .extracting("kind")
            .containsExactlyInAnyOrder(FactKind.DATE, FactKind.AMOUNT, FactKind.PHONE)
    }

    @Test
    @DisplayName("toString 은 값을 찍지 않는다")
    fun `toString 이 값을 가린다`() {
        val issue = FactIssue(FactKind.PHONE, "02-1234-5678")

        assertThat(issue.toString()).doesNotContain("02-1234-5678")
    }

    @Test
    @DisplayName("ExtractedFact 도 toString 에 값을 찍지 않는다")
    fun `ExtractedFact toString 이 값을 가린다`() {
        val rendered = findMissingFacts("문의: 02-1234-5678", "문의하세요.").toString()

        assertThat(rendered).doesNotContain("02-1234-5678")
    }

    @Nested
    @DisplayName("날짜 — 구성요소 비교 (리뷰 HIGH-1)")
    inner class DateComponentComparison {
        @Test
        @DisplayName("ISO 표기와 한글 표기가 같은 날이면 보존으로 본다 — 단순 숫자 이어붙이기가 아니다")
        fun `ISO와 한글 표기가 같은 날이면 보존이다`() {
            val source = "접수 마감은 2026.09.04입니다."
            val kept = "접수 마감은 2026년 9월 4일입니다."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("연도가 한쪽에만 있으면 월·일만 맞으면 보존으로 본다")
        fun `연도가 없는 쪽과도 월일이 맞으면 보존이다`() {
            val source = "접수 마감은 2026.09.04입니다."
            val kept = "접수 마감은 9월 4일입니다." // 변환문이 연도를 생략했다.

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("월·일이 다르면 연도 유무와 무관하게 누락이다")
        fun `월일이 다르면 누락이다`() {
            val source = "접수 마감은 2026.09.04입니다."
            val different = "접수 마감은 9월 5일입니다."

            assertThat(findMissingFacts(source, different))
                .extracting("kind")
                .containsExactly(FactKind.DATE)
        }

        @Test
        @DisplayName("양쪽 다 연도가 있는데 다르면 누락이다")
        fun `연도가 둘 다 있고 다르면 누락이다`() {
            val source = "접수 마감은 2026.09.04입니다."
            val wrongYear = "접수 마감은 2025년 9월 4일입니다."

            assertThat(findMissingFacts(source, wrongYear))
                .extracting("kind")
                .containsExactly(FactKind.DATE)
        }
    }

    @Nested
    @DisplayName("시각 — 분 단위 정규화 (리뷰 HIGH-1)")
    inner class TimeMinuteNormalization {
        @Test
        @DisplayName("'HH:MM'과 '오전 N시'가 같은 시각이면 보존으로 본다")
        fun `콜론 표기와 오전 표기가 같은 시각이면 보존이다`() {
            val source = "10:00에 시작합니다."
            val kept = "오전 10시에 시작해요."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("'오후 3시'와 24시간제 '15시'는 같은 시각이다")
        fun `오후 표기와 24시간제 표기가 같은 시각이면 보존이다`() {
            val source = "오후 3시에 마감합니다."
            val kept = "15시에 마감해요."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("시각이 달라지면 누락이다")
        fun `시각이 다르면 누락이다`() {
            val source = "오후 3시에 마감합니다."
            val different = "오후 4시에 마감해요."

            assertThat(findMissingFacts(source, different))
                .extracting("kind")
                .containsExactly(FactKind.TIME)
        }
    }

    @Nested
    @DisplayName("한글 수사 — 제한된 등가 (리뷰 HIGH-2)")
    inner class KoreanNumeralEquivalence {
        @Test
        @DisplayName("'3개월'과 '세 달'은 같은 사실이다")
        fun `개월과 달은 같은 값이면 보존이다`() {
            assertThat(findMissingFacts("3개월 안에 답합니다.", "세 달 안에 답해요.")).isEmpty()
        }

        @Test
        @DisplayName("'1,000원'과 '천 원'은 같은 사실이다")
        fun `천원 표기가 같은 값이면 보존이다`() {
            assertThat(findMissingFacts("수수료는 1,000원입니다.", "수수료는 천 원이에요.")).isEmpty()
        }

        @Test
        @DisplayName("'2명'과 '두 명'은 같은 사실이다")
        fun `두 명 표기가 같은 값이면 보존이다`() {
            assertThat(findMissingFacts("정원은 2명입니다.", "정원은 두 명이에요.")).isEmpty()
        }

        @Test
        @DisplayName("'30,000원'과 '삼만 원'은 같은 사실이다")
        fun `삼만원 표기가 같은 값이면 보존이다`() {
            assertThat(findMissingFacts("참가비는 30,000원입니다.", "참가비는 삼만 원이에요.")).isEmpty()
        }

        @Test
        @DisplayName("알려진 공백 — 11 이상의 합성 수사('열두')는 오탐 가능성으로 남는다")
        fun `11 이상의 한글 수사는 여전히 누락으로 보고될 수 있다`() {
            val missing = findMissingFacts("정원은 12명입니다.", "정원은 열두 명이에요.")

            assertThat(missing)
                .withFailMessage("이 사례는 문서화된 공백이다 — 통과하면 구현이 더 나아진 것이니 문서를 갱신하라")
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }
    }
}

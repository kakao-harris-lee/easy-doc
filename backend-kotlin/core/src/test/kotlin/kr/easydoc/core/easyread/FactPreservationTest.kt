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
        @DisplayName("원문에 연도가 있는데 변환문이 빼먹으면 누락이다 — 비대칭 비교(리뷰 MEDIUM-5)")
        fun `원문 연도를 변환문이 빼먹으면 누락이다`() {
            val source = "접수 마감은 2026.09.04입니다." // 원문에 연도가 있다.
            val droppedYear = "접수 마감은 9월 4일입니다." // 변환문이 연도를 뺐다.

            assertThat(findMissingFacts(source, droppedYear))
                .extracting("kind")
                .containsExactly(FactKind.DATE)
        }

        @Test
        @DisplayName("원문에 연도가 없으면 변환문의 연도 유무와 무관하게 월·일만 맞으면 보존이다")
        fun `원문에 연도가 없으면 월일만 비교한다`() {
            val source = "접수 마감은 9월 4일입니다." // 원문 자체에 연도가 없다.
            val kept = "접수 마감은 2026년 9월 4일입니다." // 변환문이 연도를 붙였다.

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

        @Test
        @DisplayName("같은 월-일이 연도 유무를 달리해 두 번 나오면 연도 누락을 여전히 잡는다(리뷰 blocker 재현, 2026-09-09)")
        fun `같은 월일이 중복이어도 연도 누락을 잡는다`() {
            // compareKey 는 MMDD 뿐이라 이 두 DATE 는 "distinctBy { kind to compareKey }" 기준으로
            // 같은 사실이다 — 연도 유무가 다른 두 표기가 원문에 함께 있는 흔한 공문 형태.
            val source = "9월 4일 이후 신청은 받지 않으며, 최종 마감은 2026년 9월 4일입니다."
            // 변환문이 연도를 빼먹었다 — sameDate() 의 비대칭 비교(원문에 연도가 있었으면
            // 변환문도 같은 연도를 적어야 한다)로는 누락이어야 한다.
            val droppedYear = "9월 4일 이후 신청은 받지 않으며, 최종 마감도 9월 4일입니다."

            assertThat(findMissingFacts(source, droppedYear))
                .withFailMessage(
                    "distinctBy 를 filterNot 보다 먼저 적용하면 연도 없는 표기가 대표로 뽑혀 " +
                        "연도 있는 표기의 sameDate() 비대칭 비교(year 비교)가 통째로 사라진다 — " +
                        "year 는 distinctBy 키(kind, compareKey)에 없기 때문이다.",
                ).extracting("kind")
                .containsExactly(FactKind.DATE)
        }
    }

    @Nested
    @DisplayName("연도 축약 — 아포스트로피 붙은 두 자리 연도 (실측 6차, 문서 106)")
    inner class ApostropheAbbreviatedYear {
        @Test
        @DisplayName("'’26년'을 원문대로 두고 변환문이 '2026년'으로 펴 써도 누락이 아니다")
        fun `아포스트로피 연도 단독 표기가 보존으로 인정된다`() {
            val source = "’26년 시행 예정입니다."
            val kept = "2026년에 시행할 예정입니다."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("'’26.9.1.'을 원문대로 두고 변환문이 '2026년 9월 1일'로 펴 써도 누락이 아니다")
        fun `아포스트로피 날짜 표기가 보존으로 인정된다`() {
            val source = "접수 기간은 ’26.9.1.까지입니다."
            val kept = "접수 기간은 2026년 9월 1일까지입니다."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName(
            "혼합형 — '’26년 9월 1일'처럼 축약 연도와 월·일이 붙어도 DATE 하나로 잡혀 누락되지 않는다" +
                "(리뷰 blocker, 2026-09-09 재현: findMissingFacts 실행 시 NUMBER(2026)가 짝을 잃어 누락으로 잡히던 문제)",
        )
        fun `혼합형 아포스트로피 날짜가 보존으로 인정된다`() {
            val source = "접수 기간은 ’26년 9월 1일까지입니다."
            val kept = "접수 기간은 2026년 9월 1일까지입니다."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("혼합형 — 연도가 다르면('2027년') 여전히 누락으로 잡힌다 — 정규화가 검사를 없애지 않는다")
        fun `혼합형 아포스트로피 날짜는 연도가 다르면 누락이다`() {
            val source = "접수 기간은 ’26년 9월 1일까지입니다."
            val wrongYear = "접수 기간은 2027년 9월 1일까지입니다."

            assertThat(findMissingFacts(source, wrongYear))
                .extracting("kind")
                .containsExactly(FactKind.DATE)
        }

        @Test
        @DisplayName("'’24년 ~ ’25년'을 원문대로 두고 변환문이 '2024년 ~ 2025년'으로 펴 써도 누락이 아니다")
        fun `연속된 아포스트로피 연도 두 개가 모두 보존으로 인정된다`() {
            val source = "사업 기간은 ’24년 ~ ’25년입니다."
            val kept = "사업 기간은 2024년부터 2025년까지입니다."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("아포스트로피가 없는 맨 '26년'은 여전히 숫자 26으로 다뤄진다 — 회귀 방지")
        fun `아포스트로피 없는 26년은 그대로 숫자로 남는다`() {
            val source = "26년 동안 근무했습니다." // 기간 표현 — 연도가 아니다.
            val dropped = "오래 근무했습니다."

            assertThat(findMissingFacts(source, dropped))
                .withFailMessage("아포스트로피가 없으면 세기 확장을 하지 않아야 한다 — 안 그러면 기간(26년)이 연도(2026년)로 오판된다")
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }

        @Test
        @DisplayName("원문이 '’26년'인데 변환문이 다른 연도('2027년')를 적으면 누락으로 잡는다 — 정규화가 검사를 없애지 않는다")
        fun `축약 연도와 다른 연도로 바뀌면 누락이다`() {
            val source = "’26년 시행 예정입니다."
            val wrongYear = "2027년에 시행할 예정입니다."

            assertThat(findMissingFacts(source, wrongYear))
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }

        @Test
        @DisplayName("ASCII 작은따옴표(')도 U+2019(’)와 똑같이 동작한다")
        fun `ASCII 아포스트로피도 동작한다`() {
            val source = "'26년 시행 예정입니다." // ASCII '
            val kept = "2026년에 시행할 예정입니다."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("세기 보정 경계 — 50 이상은 20NN 으로 펴지 않는다(1950년대 등과 헷갈릴 수 있어서)")
        fun `50 이상의 축약 연도는 세기를 보정하지 않는다`() {
            val source = "’50년 준공됐습니다." // 경계 밖 — 20NN 으로 확정하면 위험한 값.
            val wrongExpansion = "2050년에 준공됐습니다."

            assertThat(findMissingFacts(source, wrongExpansion))
                .withFailMessage("50~99는 세기가 애매해 확장하지 않는다 — 그래서 '50'과 '2050'은 다른 사실로 남아야 한다")
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }

        @Test
        @DisplayName("findMissingFacts 와 factCoverage 가 아포스트로피 연도에도 같은 비교 규칙을 공유한다")
        fun `factCoverage 도 같은 결과를 낸다`() {
            val source = "’26년 시행 예정입니다."
            val kept = "2026년에 시행할 예정입니다."

            val coverage = factCoverage(source, kept)

            assertThat(coverage.missing).isEqualTo(findMissingFacts(source, kept))
            assertThat(coverage.missing).isEmpty()
            assertThat(coverage.sourceFactCount).isEqualTo(1)
            assertThat(coverage.ratio).isEqualTo(1.0)
        }

        @Test
        @DisplayName(
            "세 번째 축약형 — 일(day) 없이 '’06. 1.'처럼 구분자만 이어져도 " +
                "변환문 '2006년 1월'로 펴 쓰면 누락이 아니다(8차 유료 측정, 문서 048)",
        )
        fun `구분자만 있는 아포스트로피 연도 표기가 보존으로 인정된다`() {
            val source = "’06. 1.  ∙ 5개 시·도 시범 운영"
            val kept = "2006년 1월, 5개 시·도에서 시범 운영을 시작했습니다."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("공백 없는 '’15.5'도 '2015년 5월'로 펴 써도 누락이 아니다")
        fun `공백 없는 구분자 전용 축약 연도도 보존으로 인정된다`() {
            val source = "’15.5  ∙ 학교 밖 청소년 지원에 관한 법률"
            val kept = "2015년 5월, 학교 밖 청소년 지원에 관한 법률이 제정됐습니다."

            assertThat(findMissingFacts(source, kept)).isEmpty()
        }

        @Test
        @DisplayName("구분자 전용 축약 연도도 변환문의 연도가 다르면('2007년') 여전히 누락이다")
        fun `구분자만 있는 축약 연도도 연도가 다르면 누락이다`() {
            val source = "’06. 1.  ∙ 5개 시·도 시범 운영"
            val wrongYear = "2007년 1월, 5개 시·도에서 시범 운영을 시작했습니다."

            assertThat(findMissingFacts(source, wrongYear))
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }

        @Test
        @DisplayName(
            "회귀 방지 — 완전한 날짜 '’09.11.27'이 구분자 전용 패턴에 쪼개지지 않고 " +
                "여전히 DATE 하나로 잡힌다(패턴 순서: 완전한 세 요소 패턴이 먼저 구간을 점유해야 한다)",
        )
        fun `완전한 아포스트로피 날짜는 구분자 전용 패턴에 쪼개지지 않는다`() {
            val source = "’09.11.27  ∙ 관련 규정 개정"
            val kept = "2009년 11월 27일, 관련 규정이 개정됐습니다."

            val coverage = factCoverage(source, kept)

            assertThat(coverage.missing)
                .withFailMessage(
                    "완전한 날짜가 연도(NUMBER)와 나머지(월.일)로 쪼개지면 구분자 전용 패턴이 " +
                        "먼저 구간을 채간 것이다 — PATTERNS 순서를 확인하라.",
                ).isEmpty()
            assertThat(coverage.sourceFactCount)
                .withFailMessage("완전한 날짜 하나가 여러 사실로 쪼개지면 sourceFactCount 가 1보다 커진다")
                .isEqualTo(1)
        }

        @Test
        @DisplayName("아포스트로피 없는 '05.4'는 연도로 확장하지 않는다 — 소수·순번·조 번호일 수 있어 손대지 않는다(기존 동작 유지)")
        fun `아포스트로피 없는 두 자리 숫자는 연도로 확장되지 않는다`() {
            val source = "05.4  ∙ 관련 사업 시작"
            val expandedAsIfYear = "2005년 4월, 관련 사업이 시작됐습니다."

            assertThat(findMissingFacts(source, expandedAsIfYear))
                .withFailMessage(
                    "아포스트로피가 없으면 두 자리 숫자를 연도로 확장하면 안 된다 — 확장하면 " +
                        "'05'와 '2005'가 같은 값으로 오판된다",
                ).extracting("kind")
                .containsExactly(FactKind.NUMBER)
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

    @Nested
    @DisplayName("한글 수사 — 왼쪽 경계·고유어 제한 (판단거리 9 ⓓ, 2026-09-10, 원문 58건 실측)")
    inner class KoreanNumeralWordBoundary {
        @Test
        @DisplayName("105 문서 재현 — '판매행사 개최'의 '사 개'는 수사로 잡히지 않는다")
        fun `행사 개최의 사 개는 수사가 아니다`() {
            val source = "이번 판매행사 개최를 알려드립니다."
            val draft = "이번 행사를 안내합니다."

            assertThat(factCoverage(source, draft).sourceFactCount)
                .withFailMessage("한자어 수사 '사'가 '행사'의 끝 음절과 겹쳐 오탐하면 안 된다")
                .isEqualTo(0)
        }

        @Test
        @DisplayName("022 문서 재현 — '교체한 개인'의 '한 개'는 수사로 잡히지 않는다")
        fun `교체한 개인의 한 개는 수사가 아니다`() {
            val source = "부품을 교체한 개인은 신고하세요."
            val draft = "부품을 바꾼 사람은 신고하세요."

            assertThat(factCoverage(source, draft).sourceFactCount)
                .withFailMessage("'교체한'의 끝 음절 '한'이 수사로 오탐하면 안 된다")
                .isEqualTo(0)
        }

        @Test
        @DisplayName("039 문서 재현 — '거주지가 속한 시･군･구'의 '한 시'는 수사로 잡히지 않는다")
        fun `속한 시의 한 시는 수사가 아니다`() {
            val source = "거주지가 속한 시･군･구를 확인하세요."
            val draft = "사는 지역을 확인하세요."

            assertThat(factCoverage(source, draft).sourceFactCount)
                .withFailMessage("'속한'의 끝 음절 '한'이 수사로 오탐하면 안 된다")
                .isEqualTo(0)
        }

        @Test
        @DisplayName("107 문서 재현 — '충분한 시간적 여유'의 '한 시'는 수사로 잡히지 않는다")
        fun `충분한 시간적 여유의 한 시는 수사가 아니다`() {
            val source = "충분한 시간적 여유를 두고 준비하세요."
            val draft = "여유 있게 준비하세요."

            assertThat(factCoverage(source, draft).sourceFactCount)
                .withFailMessage("'충분한'의 끝 음절 '한'이 수사로 오탐하면 안 된다")
                .isEqualTo(0)
        }

        @Test
        @DisplayName("022·042·105·106 문서 재현 — letter-spacing 된 표 머리글 '구 분'은 수사로 잡히지 않는다")
        fun `표 머리글 구 분은 수사가 아니다`() {
            val source = "구 분  대상  비대상"
            val draft = "구분  대상  비대상"

            assertThat(factCoverage(source, draft).sourceFactCount)
                .withFailMessage("한자어 수사 '구'가 표 머리글 '구 분'에서 오탐하면 안 된다")
                .isEqualTo(0)
        }

        @Test
        @DisplayName("여전히 잡히는 것 — 문장 시작·공백 뒤의 고유어 수사는 그대로 사실이다")
        fun `경계가 있으면 고유어 수사는 여전히 사실이다`() {
            val source = "한 달 안에 두 건을 처리하고 다섯 명과 한 번 더 만납니다."
            val dropped = "처리하고 만납니다."

            val coverage = factCoverage(source, dropped)

            assertThat(coverage.sourceFactCount)
                .withFailMessage("공백·문장 시작 뒤의 진짜 고유어 수사(한 달·두 건·다섯 명·한 번)는 여전히 사실이어야 한다")
                .isEqualTo(4)
            assertThat(coverage.missing).hasSize(4)
        }

        @Test
        @DisplayName("등가 회귀 — 원문 '3개월'과 변환문 '세 달'은 여전히 같은 사실이다")
        fun `개월과 달의 등가는 경계 제한 뒤에도 깨지지 않는다`() {
            assertThat(findMissingFacts("3개월 안에 답합니다.", "세 달 안에 답해요."))
                .withFailMessage("왼쪽 경계·고유어 제한이 '3개월'↔'세 달' 등가까지 깨면 ⓓ가 막으려던 오탐을 되살린 것이다")
                .isEmpty()
        }
    }

    @Nested
    @DisplayName("한글 수사 — '개월'과 '개' 단위 충돌 (리뷰 재검토 HIGH-1)")
    inner class MonthVersusPieceUnit {
        @Test
        @DisplayName("'3개'(낱개)는 '세 달'(개월)로 지켜지지 않는다 — 재현 사례")
        fun `개와 개월은 다른 단위다`() {
            val missing = findMissingFacts("물품은 3개입니다.", "기간은 세 달입니다.")

            assertThat(missing)
                .withFailMessage("'개'가 '개월'로도 정규화되면 무관한 3개(낱개)가 3개월(기간)로 지켜진 것처럼 오판된다")
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }

        @Test
        @DisplayName("'3개월'은 '세 달'과 같은 사실이다")
        fun `개월과 달은 같은 단위로 정규화된다`() {
            assertThat(findMissingFacts("계약 기간은 3개월입니다.", "계약 기간은 세 달입니다.")).isEmpty()
        }

        @Test
        @DisplayName("'3개'는 '세 개'와 같은 사실이다")
        fun `개는 개대로 보존된다`() {
            assertThat(findMissingFacts("물품은 3개입니다.", "물품은 세 개입니다.")).isEmpty()
        }
    }

    @Nested
    @DisplayName("한글 단위 동의어 — 번/회, 살/세 (7차 유료 측정 023 재현)")
    inner class UnitSynonyms {
        // 한 자리 Arabic 숫자(예: "3회"·"5세")와 한글 수사 형태(예: "세 번"·"다섯 살")만 이
        // 별칭의 영향을 받는다 — `numberCompareKey` 가 단위 문자를 비교 키에 담는 경우가 그
        // 둘뿐이기 때문이다([UNIT_ALIASES] KDoc "적용 범위의 한계" 참고). 아래 두 자리
        // 숫자(023 재현의 실제 값 "10회"·요청서의 "65세") 테스트는 별도 nested class
        // (RawTwoDigitReproduction)에서 그 한계를 문서화한다 — 별칭이 있든 없든 결과가 같다.

        @Test
        @DisplayName("'3회'(원문)를 '3번'(쉬운 글)으로 자연스럽게 바꿔 써도 누락이 아니다")
        fun `회를 번으로 바꿔 써도 보존이다`() {
            assertThat(findMissingFacts("장갑은 하루 3회 사용합니다.", "장갑은 하루 3번 사용해요."))
                .withFailMessage(
                    "쉬운 글로 자연스럽게 바꿔 쓴 '번'을 벌점으로 세면, 원문을 그대로 베낀 표기가 " +
                        "오히려 점수를 더 받는 역전이 생긴다(7차 유료 측정 023 문서 실측, 023의 실제 " +
                        "값은 두 자리라 이 파일만으로는 안 고쳐진다 — 위 KDoc 한계 참고. 이 테스트는 " +
                        "같은 뿌리(단위 불일치)의 한 자리 사례로 고정한다).",
                ).isEmpty()
        }

        @Test
        @DisplayName("'5세'(원문)를 '5살'(쉬운 글)로 바꿔 써도 누락이 아니다")
        fun `세를 살로 바꿔 써도 보존이다`() {
            assertThat(findMissingFacts("만 5세부터 신청할 수 있습니다.", "만 5살부터 신청할 수 있어요.")).isEmpty()
        }

        @Test
        @DisplayName("반대 방향('3번'을 '3회'로) 도 보존이다 — 별칭은 대칭이다")
        fun `번을 회로 바꿔 써도 보존이다`() {
            assertThat(findMissingFacts("하루 3번 복용하세요.", "하루 3회 복용하세요.")).isEmpty()
        }

        @Test
        @DisplayName("한글 수사 형태('다섯 살'↔'다섯 세')도 같은 별칭을 탄다")
        fun `한글 수사 나이 표현도 별칭이 적용된다`() {
            assertThat(findMissingFacts("다섯 세 어린이도 신청할 수 있습니다.", "다섯 살 어린이도 신청할 수 있어요.")).isEmpty()
        }

        @Test
        @DisplayName("단위는 같아도 값이 다르면 여전히 누락이다 — 별칭이 검사를 없애지 않는다")
        fun `값이 다르면 단위가 같아도 누락이다`() {
            val missing = findMissingFacts("하루 3회 복용하세요.", "하루 5번 복용하세요.")

            assertThat(missing)
                .withFailMessage("번/회 별칭이 단위만 맞추는 것이지 값(3 vs 5) 비교까지 없애면 안 된다")
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }

        @Test
        @DisplayName("뜻이 다른 단위(개·명)는 여전히 구분된다 — 회귀 방지")
        fun `개와 명은 여전히 다른 단위다`() {
            val missing = findMissingFacts("사탕은 3개입니다.", "사람은 3명입니다.")

            assertThat(missing)
                .withFailMessage("개↔명처럼 뜻이 다른 단위까지 번/회·살/세 별칭에 딸려 같아지면 안 된다")
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }
    }

    @Nested
    @DisplayName("한글 단위 동의어 — 두 자리 이상 값은 이 파일 범위 밖 (직접 확인, 2026-09-09)")
    inner class RawTwoDigitReproduction {
        // 7차 유료 측정 023 문서의 실제 값("10회"↔"10번")과 이번 요청서의 예시("65세"↔"65살")를
        // extractFacts 로 직접 찍어 확인한 결과, 두 자리 이상 Arabic 숫자는 raw match 가 숫자만
        // ("10"·"65")이고 단위 문자가 애초에 비교 키에 들어오지 않는다 — `FactPreservation.kt`
        // PATTERNS 의 NUMBER 정규식이 두 자리 이상에는 단위 소비 대체를 두지 않기 때문이다.
        // 그래서 아래 둘은 UNIT_ALIASES 변경 **전에도** 이미 보존으로 판정됐다(TDD 의 "먼저
        // 실패" 전제가 성립하지 않는다) — 별칭 유무와 무관한 결과라는 뜻이며, 이 사실을
        // 회귀 가드로 고정해 둔다. 두 자리 이상까지 단위를 비교 키에 담으려면 그 정규식
        // 자체를 넓혀야 하고, 이는 이번 변경 범위(이 파일의 UNIT_ALIASES map)를 벗어난다.

        @Test
        @DisplayName("'10회'와 '10번'은 오늘도 이미 같은 사실이다 — 단위가 비교 키에 없어서(별칭과 무관)")
        fun `두 자리 회번은 별칭과 무관하게 이미 보존이다`() {
            assertThat(findMissingFacts("장갑 500원×100개×10회 = 500천원", "장갑 500원씩 100개를 10번 사용하면 500천원입니다."))
                .isEmpty()
        }

        @Test
        @DisplayName("'65세'와 '65살'도 오늘도 이미 같은 사실이다 — 단위가 비교 키에 없어서(별칭과 무관)")
        fun `두 자리 세살은 별칭과 무관하게 이미 보존이다`() {
            assertThat(findMissingFacts("만 65세부터 신청할 수 있습니다.", "만 65살부터 신청할 수 있어요.")).isEmpty()
        }
    }

    @Nested
    @DisplayName("숫자·백분율 정체성 — 단위·소수점도 값이다 (리뷰 HIGH-1)")
    inner class NumberAndPercentIdentity {
        @Test
        @DisplayName("같은 숫자라도 단위가 다르면 다른 사실이다 — '3명'은 '3층'으로 지켜지지 않는다")
        fun `단위가 다르면 다른 사실이다`() {
            val missing = findMissingFacts("3명이 신청했습니다.", "3층에서 접수합니다.")

            assertThat(missing)
                .withFailMessage("단위가 정체성에 안 들어가면 '3명'이 '3층'으로도 지켜진 것처럼 잘못 판정된다")
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }

        @Test
        @DisplayName("단위가 같으면(고유어 수사로 바뀌어도) 그대로 보존이다")
        fun `단위가 같으면 보존이다`() {
            assertThat(findMissingFacts("3명이 신청했습니다.", "세 명이 신청했어요.")).isEmpty()
        }

        @Test
        @DisplayName("소수점을 뭉개면 다른 값이 된다 — '1.5%'는 '15%'로 지켜지지 않는다")
        fun `소수점이 다르면 다른 값이다`() {
            val missing = findMissingFacts("1.5% 인상됩니다.", "15% 인상돼요.")

            assertThat(missing)
                .withFailMessage("소수점을 버리면 1.5와 15가 같은 값(digitsOnly=15)으로 잘못 판정된다")
                .extracting("kind")
                .containsExactly(FactKind.PERCENT)
        }

        @Test
        @DisplayName("소수점이 같은 값으로 남아 있으면 보존이다")
        fun `소수점이 지켜지면 보존이다`() {
            assertThat(findMissingFacts("1.5% 인상됩니다.", "1.5퍼센트 인상돼요. 1.5% 로 조정합니다.")).isEmpty()
        }
    }

    @Nested
    @DisplayName("단위·구분자 없는 5자리 이상 맨 숫자열 (판단거리 9 ⓒ, 2026-09-10, 원문 58건 실측)")
    inner class BareLongDigitRuns {
        @Test
        @DisplayName("063 문서 재현 — 표 셀이 뭉개진 숫자열은 사실로 잡히지 않는다")
        fun `표 셀 뭉개짐 숫자열은 사실이 아니다`() {
            val source = "…추가검토대상20200 0 0 0 0 020211 1 0 0 0 02022140 14140 0…"
            val draft = "관련 내용이 사라졌습니다."

            assertThat(findMissingFacts(source, draft))
                .withFailMessage("표 셀 뭉개짐으로 생긴 5자리 이상 맨 숫자열까지 사실로 잡으면 안 된다")
                .isEmpty()
        }

        @Test
        @DisplayName("064 문서 재현 — '14858208' 같은 8자리 맨 숫자열은 사실로 잡히지 않는다")
        fun `8자리 맨 숫자열은 사실이 아니다`() {
            val source = "문서번호는 14858208입니다."
            val draft = "문서번호가 있습니다."

            assertThat(findMissingFacts(source, draft)).isEmpty()
        }

        @Test
        @DisplayName("003 문서 재현 — 주민등록번호 예시('850312-2345678')는 사실로 잡히지 않는다")
        fun `주민등록번호 예시는 사실이 아니다`() {
            val source = "주민등록번호 전체(예: 850312-2345678)를 적어주세요."
            val draft = "주민등록번호를 적어주세요."

            assertThat(findMissingFacts(source, draft)).isEmpty()
        }

        @Test
        @DisplayName("098 문서 재현 — 파일명 접두 '250114'는 사실에서 빠지고 연도 '2025'만 남는다")
        fun `파일명 접두 숫자열은 사실에서 빠진다`() {
            val source = "250114 2025년도 정부관리양곡 매출지침(알림).pdf 파일을 확인하세요."
            val draft = "파일을 확인해 주세요."

            val coverage = factCoverage(source, draft)

            assertThat(coverage.sourceFactCount)
                .withFailMessage("'250114'까지 사실로 잡히면 5자리 이상 맨 숫자열 제외가 안 된 것이다")
                .isEqualTo(1)
            assertThat(coverage.missing).extracting("kind").containsExactly(FactKind.NUMBER)
        }

        @Test
        @DisplayName("여전히 잡히는 것 — 4자리 연도·콤마 숫자·단위 붙은 5자리 이상 숫자는 그대로 사실이다")
        fun `짧은 숫자·콤마 숫자·단위 붙은 긴 숫자는 여전히 사실이다`() {
            val source = "2026년 기준 1,600대가 등록됐고 12000명이 신청했습니다."
            val dropped = "등록되고 신청했습니다."

            val coverage = factCoverage(source, dropped)

            assertThat(coverage.sourceFactCount)
                .withFailMessage("4자리 연도·콤마 숫자·단위 붙은 5자리 이상 숫자 셋 다 여전히 사실이어야 한다")
                .isEqualTo(3)
            assertThat(coverage.missing)
                .extracting("kind")
                .containsExactly(FactKind.NUMBER, FactKind.NUMBER, FactKind.NUMBER)
        }

        @Test
        @DisplayName("회귀 — '12000명'이 변환문에서 사라지면 여전히 누락으로 잡힌다(단위 붙은 5자리 이상 예외)")
        fun `단위 붙은 5자리 이상 숫자 누락은 여전히 검출된다`() {
            val source = "신청자는 12000명입니다."
            val dropped = "많은 사람이 신청했습니다."

            assertThat(findMissingFacts(source, dropped))
                .extracting("kind")
                .containsExactly(FactKind.NUMBER)
        }
    }

    @Nested
    @DisplayName("합성 한글 금액 — 억·만·천 배수 조합 (리뷰 HIGH-2)")
    inner class CompositeKoreanAmounts {
        @Test
        @DisplayName("'5천만원'은 '5천만 원'·'50,000,000원'과 같은 값이다 — 부분 매치가 아니다")
        fun `오천만원 등가 표기를 모두 인정한다`() {
            assertThat(findMissingFacts("보조금은 5천만원입니다.", "보조금은 5천만 원이에요.")).isEmpty()
            assertThat(findMissingFacts("보조금은 5천만원입니다.", "보조금은 50,000,000원이에요.")).isEmpty()
        }

        @Test
        @DisplayName("'5천만원'을 '1만원'으로 부분 매치해 값이 같다고 오판하지 않는다(리뷰 HIGH-2 재현)")
        fun `부분 매치로 다른 금액과 같다고 보지 않는다`() {
            val missing = findMissingFacts("보조금은 5천만원입니다.", "보조금은 1만원이에요.")

            assertThat(missing)
                .withFailMessage("'만원'만 부분 매치해 10,000으로 잘못 읽으면 이 사례가 보존으로 오판된다")
                .extracting("kind")
                .containsExactly(FactKind.AMOUNT)
        }

        @Test
        @DisplayName("'3,650천원'을 그대로 옮기면 보존이다")
        fun `삼천육백오십천원이 보존된다`() {
            assertThat(findMissingFacts("단가는 3,650천원입니다.", "단가는 3,650천원이에요.")).isEmpty()
        }

        @Test
        @DisplayName("'1억5천만원'처럼 두 자릿수 배수가 겹쳐도 정확히 합산한다")
        fun `억과 만이 함께 있어도 정확히 합산한다`() {
            assertThat(findMissingFacts("사업비는 1억5천만원입니다.", "사업비는 1억5천만원이에요.")).isEmpty()
            assertThat(findMissingFacts("사업비는 1억5천만원입니다.", "사업비는 150,000,000원이에요.")).isEmpty()
        }

        @Test
        @DisplayName("'1억5천만원'을 '1억5백만원'으로 조작하면 다른 값이라 누락으로 잡는다")
        fun `자릿수가 바뀐 변조를 다른 값으로 본다`() {
            val missing = findMissingFacts("사업비는 1억5천만원입니다.", "사업비는 1억5백만원이에요.")

            assertThat(missing)
                .withFailMessage("1억5천만원(1.5억)과 1억5백만원(1.05억)은 다른 값이다")
                .extracting("kind")
                .containsExactly(FactKind.AMOUNT)
        }

        @Test
        @DisplayName("배수 항 사이에 공백이 있어도 '1억 5천만원'은 통째로 소비된다(리뷰 재검토 HIGH-2 재현)")
        fun `항 사이 공백이 있어도 전체를 합산한다`() {
            assertThat(findMissingFacts("사업비는 1억 5천만원입니다.", "사업비는 150,000,000원이에요.")).isEmpty()
        }

        @Test
        @DisplayName("'1억 5천만원'을 '5천만원'만 옮긴 값으로 잘못 판정하지 않는다 — 앞이 잘리면 안 된다")
        fun `공백 뒤 앞부분이 잘려서 판정되지 않는다`() {
            val missing = findMissingFacts("사업비는 1억 5천만원입니다.", "사업비는 50,000,000원이에요.")

            assertThat(missing)
                .withFailMessage("'1억 5천만원'이 '5천만원'(공백 뒤)만 부분 매치되면 1억이 통째로 사라진 걸 못 잡는다")
                .extracting("kind")
                .containsExactly(FactKind.AMOUNT)
        }

        @Test
        @DisplayName("'5,000만원'은 '50,000,000원'과 같은 값이다")
        fun `콤마와 만이 섞여도 정확히 합산한다`() {
            assertThat(findMissingFacts("보조금은 5,000만원입니다.", "보조금은 50,000,000원이에요.")).isEmpty()
        }

        @Test
        @DisplayName("'만원권'처럼 뒤에 다른 글자가 이어져도 그 글자까지 삼키지 않는다")
        fun `만원권이 과잉 소비되지 않는다`() {
            // "만원권"의 "만원"만 AMOUNT 로 잡히고 "권"은 사실과 무관하게 그대로 남아야 한다 —
            // 값이 안 맞게 부풀거나 예외 없이 정상적으로 판정이 끝나는지만 본다(회귀 가드).
            assertThat(findMissingFacts("만원권을 준비하세요.", "만원권을 준비하세요.")).isEmpty()
            assertThat(findMissingFacts("만원권을 준비하세요.", "지폐를 준비하세요."))
                .extracting("kind")
                .containsExactly(FactKind.AMOUNT)
        }
    }

    @Nested
    @DisplayName("factCoverage — 보존율 관측 (계획 S1, 2026-09-09)")
    inner class FactCoverageObservation {
        @Test
        @DisplayName("사실이 모두 보존되면 보존 수는 전체와 같고 누락은 없다")
        fun `모두 보존되면 보존율은 1이다`() {
            val source = "9월 4일 오후 2시까지 02-1234-5678로 신청하세요. 참가비는 10,000원입니다."
            val draft =
                "신청 기간은 9월 4일까지이고, 시간은 오후 2시까지입니다. " +
                    "문의는 02-1234-5678로 하세요. 참가비는 10000원이에요."

            val coverage = factCoverage(source, draft)

            assertThat(coverage.sourceFactCount).isEqualTo(4)
            assertThat(coverage.missing).isEmpty()
            assertThat(coverage.keptCount).isEqualTo(4)
            assertThat(coverage.ratio).isEqualTo(1.0)
        }

        @Test
        @DisplayName("일부만 사라지면 보존율이 비례해 낮아진다")
        fun `일부 누락이면 보존율이 낮아진다`() {
            val source = "9월 4일까지 10,000원을 내고 02-1234-5678로 문의하세요."
            val draft = "10,000원을 내고 문의하세요."

            val coverage = factCoverage(source, draft)

            assertThat(coverage.sourceFactCount).isEqualTo(3)
            assertThat(coverage.missing).extracting("kind").containsExactlyInAnyOrder(FactKind.DATE, FactKind.PHONE)
            assertThat(coverage.keptCount).isEqualTo(1)
            assertThat(coverage.ratio).isEqualTo(1.0 / 3.0)
        }

        @Test
        @DisplayName("원문에 사실이 하나도 없으면 보존율은 null 이다 — 0%로 채우지 않는다")
        fun `원문에 사실이 없으면 보존율은 null 이다`() {
            val coverage = factCoverage("사실이 없는 문장입니다.", "역시 사실이 없습니다.")

            assertThat(coverage.sourceFactCount).isEqualTo(0)
            assertThat(coverage.missing).isEmpty()
            assertThat(coverage.ratio).isNull()
        }

        @Test
        @DisplayName("원문에 같은 사실이 중복으로 나오면 sourceFactCount 는 중복 제거 후 값이다")
        fun `중복 사실은 한 번만 센다`() {
            val source = "참가비는 10,000원입니다. 참가비는 10,000원으로 확정됐습니다."
            val kept = "참가비는 10000원입니다."
            val dropped = "참가비를 냅니다."

            val coverageKept = factCoverage(source, kept)
            val coverageDropped = factCoverage(source, dropped)

            // 같은 원문이므로 sourceFactCount 는 draft 와 무관하게 동일해야 한다(중복 제거 기준 일치).
            assertThat(coverageKept.sourceFactCount).isEqualTo(1)
            assertThat(coverageDropped.sourceFactCount).isEqualTo(1)
            assertThat(coverageKept.missing).isEmpty()
            assertThat(coverageDropped.missing).hasSize(1)
        }

        @Test
        @DisplayName("findMissingFacts 는 factCoverage 의 missing 과 항상 같다")
        fun `findMissingFacts 와 factCoverage 가 같은 결과를 낸다`() {
            val source = "9월 4일까지 10,000원을 내고 02-1234-5678로 문의하세요. 참가비는 10,000원입니다."
            val draft = "10,000원을 내고 문의하세요."

            assertThat(findMissingFacts(source, draft)).isEqualTo(factCoverage(source, draft).missing)
        }

        @Test
        @DisplayName("missing 의 크기는 sourceFactCount 를 넘지 않는다 — 같은 분모·분자")
        fun `missing 은 sourceFactCount 를 넘지 않는다`() {
            val source = "9월 4일까지 10,000원을 내고 02-1234-5678로 문의하세요."
            val coverage = factCoverage(source, "아무것도 안 남았습니다.")

            assertThat(coverage.missing.size).isLessThanOrEqualTo(coverage.sourceFactCount)
            assertThat(coverage.keptCount).isGreaterThanOrEqualTo(0)
        }
    }

    @Nested
    @DisplayName("성능 — 정규식 역추적(catastrophic backtracking) 회귀 가드 (리뷰 재검토 HIGH-3)")
    inner class Performance {
        // 재현: 20,000 자리 숫자열 하나가 findMissingFacts 를 21.6초(21,600ms) 걸리게 했다(배수
        // 단위가 하나도 안 나오는 긴 숫자열 앞에서 탐욕 수량자가 매 시작 위치마다 한 글자씩
        // 물러나며 재시도 — O(n²)). possessive 수량자로 고친 뒤에는 세 최악 사례 모두 워밍업
        // 후 2,000ms 안에 끝나야 한다. 이 경계는 원래 결함(21,600ms)보다 압도적으로 타이트해
        // 역추적 회귀는 여전히 잡아내면서, CI 러너의 속도 편차(로컬 대비 느린 공용 러너에서
        // 200ms 경계가 221ms 로 튀며 flaky 했던 사례)를 흡수하기 위한 값이다. 일반적인
        // 실사용 입력(짧은 문장 다수)이 아니라 **이 구현이 특히 취약했던 모양**만 고른
        // 사례들이다.

        @Test
        @DisplayName("구분자·배수 단위 없이 숫자만 20,000자 이어져도 2,000ms 안에 끝난다")
        fun `구분자 없는 대량 숫자도 빠르게 처리한다`() {
            assertFastEnough("3".repeat(20_000))
        }

        @Test
        @DisplayName("'1,' 을 20,000자만큼 반복해도 2,000ms 안에 끝난다")
        fun `콤마 반복 입력도 빠르게 처리한다`() {
            assertFastEnough("1,".repeat(10_000))
        }

        @Test
        @DisplayName("'억만천'과 숫자·공백이 뒤섞인 20,000자도 2,000ms 안에 끝난다")
        fun `배수 단위가 뒤섞인 입력도 빠르게 처리한다`() {
            val chunk = "억만천 123 "
            assertFastEnough(buildString { while (length < 20_000) append(chunk) })
        }

        /** [source] 와 그것을 그대로 옮긴 초안 양쪽에서 재는 것이 핵심이다 — 양쪽 다 같은 추출을 탄다. */
        private fun assertFastEnough(worstCase: String) {
            // 첫 호출은 JIT 워밍업으로 버리고, 계측은 워밍업 뒤 호출만 잰다.
            findMissingFacts(worstCase, worstCase)

            val elapsedMillis = kotlin.system.measureTimeMillis { findMissingFacts(worstCase, worstCase) }

            assertThat(elapsedMillis)
                .withFailMessage(
                    "findMissingFacts 가 %d ms 걸렸다 — 정규식 역추적(catastrophic backtracking) 의심" +
                        "(재현: 20,000자 숫자열에서 21,600ms). 경계는 2,000ms 로, 원래 결함보다 " +
                        "압도적으로 타이트하면서 CI 러너 속도 편차는 허용한다.",
                    elapsedMillis,
                ).isLessThan(2_000)
        }
    }
}
